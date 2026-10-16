use crate::{
    protocol::{self, MAX_BODY, MAX_PENDING, Packet, WireFrame},
    target::Target,
};
use anyhow::{Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use futures_util::{SinkExt, StreamExt};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::mpsc;
use tokio_tungstenite::{
    Connector, connect_async_tls_with_config,
    tungstenite::{
        Message,
        client::IntoClientRequest,
        protocol::{CloseFrame, WebSocketConfig, frame::coding::CloseCode},
    },
};
pub type Streams = Arc<Mutex<HashMap<String, mpsc::Sender<WireFrame>>>>;
pub fn dispatch(streams: &Streams, id: &str, frame: WireFrame) {
    let mut map = streams.lock().unwrap();
    if let Some(tx) = map.get(id)
        && tx.try_send(frame).is_err()
    {
        map.remove(id);
    }
}
struct Guard {
    id: String,
    streams: Streams,
    tx: mpsc::Sender<Packet>,
}
impl Drop for Guard {
    fn drop(&mut self) {
        self.streams.lock().unwrap().remove(&self.id);
        let tx = self.tx.clone();
        let id = self.id.clone();
        tokio::spawn(async move {
            let _ =
                tokio::time::timeout(Duration::from_secs(5), tx.send(Packet::WsClose { id })).await;
        });
    }
}
pub async fn bridge(
    target: Target,
    tls: Arc<rustls::ClientConfig>,
    packet: Packet,
    streams: Streams,
    tx: mpsc::Sender<Packet>,
    mut rx: mpsc::Receiver<WireFrame>,
) {
    let Packet::WsOpen {
        id,
        path,
        headers,
        protocols,
    } = packet
    else {
        return;
    };
    let _guard = Guard {
        id: id.clone(),
        streams,
        tx: tx.clone(),
    };
    let result: Result<_> = async {
        let mut request = target.url(&path, true)?.as_str().into_client_request()?;
        request.headers_mut().extend(protocol::restore(headers)?);
        if !protocols.is_empty() {
            request
                .headers_mut()
                .insert("sec-websocket-protocol", protocols.join(", ").parse()?);
        }
        let mut config = WebSocketConfig::default();
        config.max_message_size = Some(MAX_BODY);
        config.max_frame_size = Some(MAX_BODY);
        let result = tokio::time::timeout(
            Duration::from_secs(15),
            connect_async_tls_with_config(
                request,
                Some(config),
                false,
                Some(Connector::Rustls(tls)),
            ),
        )
        .await??;
        Ok(result)
    }
    .await;
    // If the public request was cancelled during the handshake, do not retain a socket.
    if rx.is_closed() {
        return;
    }
    let (socket, response) = match result {
        Ok(v) => v,
        Err(e) => {
            eprintln!("Local WebSocket connection failed: {e}");
            let _ = tx
                .send(Packet::WsOpened {
                    id,
                    status: 502,
                    headers: vec![],
                    protocol: None,
                })
                .await;
            return;
        }
    };
    let selected = response
        .headers()
        .get("sec-websocket-protocol")
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);
    if selected.as_ref().is_some_and(|v| !protocols.contains(v)) {
        return;
    }
    if tx
        .send(Packet::WsOpened {
            id: id.clone(),
            status: 101,
            headers: protocol::headers(response.headers()),
            protocol: selected,
        })
        .await
        .is_err()
    {
        return;
    }
    let (mut sink, mut source) = socket.split();
    loop {
        tokio::select! {
            message=source.next()=> {
                let frame=match message {
                    Some(Ok(Message::Text(s)))=>WireFrame::Text{data:STANDARD.encode(s.as_bytes())},
                    Some(Ok(Message::Binary(b)))=>WireFrame::Binary{data:STANDARD.encode(b)},
                    Some(Ok(Message::Close(c)))=> {
                        let frame=WireFrame::Close{code:c.as_ref().map(|c|u16::from(c.code)).unwrap_or(1000),reason:c.map(|c|c.reason.to_string()).unwrap_or_default()};
                        let _=tokio::time::timeout(Duration::from_secs(5),tx.send(Packet::WsFrame{id:id.clone(),frame})).await;
                        break;
                    }
                    Some(Ok(Message::Ping(b)))=> {if !send(&mut sink,Message::Pong(b)).await {break;}continue;}
                    Some(Ok(Message::Pong(_)))=>continue,
                    _=>break,
                };
                if !matches!(tokio::time::timeout(Duration::from_secs(10),tx.send(Packet::WsFrame{id:id.clone(),frame})).await,Ok(Ok(()))) {break;}
            }
            frame=rx.recv()=> {
                let Some(frame)=frame else {break;};
                let close=matches!(frame,WireFrame::Close{..});
                let Ok(message)=decode(frame) else {break;};
                if !send(&mut sink,message).await || close {break;}
            }
        }
    }
    let _ = tokio::time::timeout(Duration::from_secs(2), sink.close()).await;
}
fn decode(frame: WireFrame) -> Result<Message> {
    Ok(match frame {
        WireFrame::Text { data } => {
            let data = STANDARD.decode(data)?;
            ensure!(data.len() <= MAX_BODY, "WebSocket message too large");
            Message::Text(String::from_utf8(data)?.into())
        }
        WireFrame::Binary { data } => {
            let b = STANDARD.decode(data)?;
            ensure!(b.len() <= MAX_BODY, "WebSocket message too large");
            Message::Binary(b.into())
        }
        WireFrame::Close { code, reason } => {
            let code = CloseCode::from(code);
            ensure!(
                code.is_allowed() && reason.len() <= 123,
                "Invalid close frame"
            );
            Message::Close(Some(CloseFrame {
                code,
                reason: reason.into(),
            }))
        }
    })
}
async fn send<S>(sink: &mut S, message: Message) -> bool
where
    S: futures_util::Sink<Message> + Unpin,
{
    matches!(
        tokio::time::timeout(Duration::from_secs(10), sink.send(message)).await,
        Ok(Ok(()))
    )
}
pub fn channel() -> (mpsc::Sender<WireFrame>, mpsc::Receiver<WireFrame>) {
    mpsc::channel(MAX_PENDING)
}
