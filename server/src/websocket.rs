use super::*;
use crate::protocol::WireFrame;
use axum::extract::ws::CloseFrame;
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
    tunnel: Tunnel,
    _permit: tokio::sync::OwnedSemaphorePermit,
}
impl Drop for Guard {
    fn drop(&mut self) {
        self.tunnel.streams.lock().unwrap().remove(&self.id);
        self.tunnel.pending.lock().unwrap().remove(&self.id);
        let tx = self.tunnel.tx.clone();
        let id = self.id.clone();
        tokio::spawn(async move {
            let _ =
                tokio::time::timeout(Duration::from_secs(5), tx.send(Packet::WsClose { id })).await;
        });
    }
}
pub async fn upgrade(
    tunnel: Tunnel,
    req: Request,
    permit: tokio::sync::OwnedSemaphorePermit,
) -> Response {
    let (mut parts, _) = req.into_parts();
    let ws = match WebSocketUpgrade::from_request_parts(&mut parts, &()).await {
        Ok(ws) => ws,
        Err(e) => return e.into_response(),
    };
    let offered: Vec<String> = parts
        .headers
        .get_all("sec-websocket-protocol")
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .map(|v| v.trim().to_owned())
        .collect();
    let id = Uuid::new_v4().simple().to_string();
    let (tx, rx) = oneshot::channel();
    tunnel.pending.lock().unwrap().insert(id.clone(), tx);
    let (in_tx, in_rx) = mpsc::channel(MAX_PENDING);
    tunnel.streams.lock().unwrap().insert(id.clone(), in_tx);
    let guard = Guard {
        id: id.clone(),
        tunnel: tunnel.clone(),
        _permit: permit,
    };
    let packet = Packet::WsOpen {
        id: id.clone(),
        path: parts
            .uri
            .path_and_query()
            .map(|p| p.as_str())
            .unwrap_or("/")
            .to_owned(),
        headers: protocol::headers(&parts.headers),
        protocols: offered.clone(),
    };
    if tunnel.tx.try_send(packet).is_err() {
        return reply(StatusCode::BAD_GATEWAY, "Tunnel disconnected or busy");
    }
    let opened = tokio::time::timeout(Duration::from_secs(20), rx).await;
    let (headers, selected) = match opened {
        Ok(Ok(Packet::WsOpened {
            status: 101,
            headers,
            protocol,
            ..
        })) => (headers, protocol),
        Ok(Ok(Packet::WsOpened { status, .. })) => {
            return reply(
                StatusCode::from_u16(status)
                    .ok()
                    .filter(|s| s.is_client_error() || s.is_server_error())
                    .unwrap_or(StatusCode::BAD_GATEWAY),
                "Local WebSocket handshake failed",
            );
        }
        Err(_) => return reply(StatusCode::GATEWAY_TIMEOUT, "WebSocket handshake timed out"),
        _ => return reply(StatusCode::BAD_GATEWAY, "Tunnel disconnected"),
    };
    if selected.as_ref().is_some_and(|p| !offered.contains(p)) {
        return reply(StatusCode::BAD_GATEWAY, "Invalid subprotocol selection");
    }
    let Ok(headers) = protocol::restore(headers) else {
        return reply(StatusCode::BAD_GATEWAY, "Invalid handshake headers");
    };
    let ws = ws
        .max_message_size(MAX_BODY)
        .max_frame_size(MAX_BODY)
        .protocols(selected);
    let mut response = ws.on_upgrade(move |socket| bridge(socket, in_rx, guard));
    response.headers_mut().extend(headers);
    response
}
async fn bridge(socket: WebSocket, mut rx: mpsc::Receiver<WireFrame>, guard: Guard) {
    let (mut sink, mut source) = socket.split();
    loop {
        tokio::select! {
            message=source.next()=> {
                let frame=match message {
                    Some(Ok(Message::Text(s)))=>WireFrame::Text{data:STANDARD.encode(s.as_bytes())},
                    Some(Ok(Message::Binary(b)))=>WireFrame::Binary{data:STANDARD.encode(b)},
                    Some(Ok(Message::Close(c)))=> {
                        let frame=WireFrame::Close{code:c.as_ref().map(|c|c.code).unwrap_or(1000),reason:c.map(|c|c.reason.to_string()).unwrap_or_default()};
                        let _=tokio::time::timeout(Duration::from_secs(5),guard.tunnel.tx.send(Packet::WsFrame{id:guard.id.clone(),frame})).await;
                        break;
                    }
                    Some(Ok(Message::Ping(b)))=> {if !send(&mut sink,Message::Pong(b)).await {break;}continue;}
                    Some(Ok(Message::Pong(_)))=>continue,
                    _=>break,
                };
                if !matches!(tokio::time::timeout(Duration::from_secs(10),guard.tunnel.tx.send(Packet::WsFrame{id:guard.id.clone(),frame})).await,Ok(Ok(()))) {break;}
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
            ensure!(
                (1000..=4999).contains(&code)
                    && ![1004, 1005, 1006, 1015].contains(&code)
                    && !(1016..=2999).contains(&code)
                    && reason.len() <= 123,
                "Invalid close frame"
            );
            Message::Close(Some(CloseFrame {
                code,
                reason: reason.into(),
            }))
        }
    })
}
