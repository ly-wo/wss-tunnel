//! Bounded outgoing queues. Control has a bounded priority burst; regular and
//! SSE queues alternate. A stream can enqueue at most its four-frame window.
use crate::protocol::Packet;
use tokio::sync::mpsc;
pub enum Out {
    Packet(Packet),
    Ping(Vec<u8>),
    Pong(Vec<u8>),
}
pub struct Receiver {
    regular: mpsc::Receiver<Packet>,
    data: mpsc::Receiver<Packet>,
    control: mpsc::Receiver<Out>,
    controls: usize,
    prefer_data: bool,
}
pub fn channel() -> (
    mpsc::Sender<Packet>,
    mpsc::Sender<Packet>,
    mpsc::Sender<Out>,
    Receiver,
) {
    let (tx, regular) = mpsc::channel(32);
    let (sse, data) = mpsc::channel(40); // 8 streams * (4 data + terminal)
    let (ctrl, control) = mpsc::channel(64);
    (
        tx,
        sse,
        ctrl,
        Receiver {
            regular,
            data,
            control,
            controls: 0,
            prefer_data: false,
        },
    )
}
impl Receiver {
    pub async fn next(&mut self) -> Option<Out> {
        if self.controls < 4
            && let Ok(p) = self.control.try_recv()
        {
            self.controls += 1;
            return Some(p);
        }
        self.controls = 0;
        let packet = if self.prefer_data {
            self.data.try_recv().or_else(|_| self.regular.try_recv())
        } else {
            self.regular.try_recv().or_else(|_| self.data.try_recv())
        };
        self.prefer_data = !self.prefer_data;
        if let Ok(p) = packet {
            return Some(Out::Packet(p));
        }
        tokio::select! {
            Some(p) = self.control.recv() => { self.controls = 1; Some(p) },
            Some(p) = self.regular.recv() => Some(Out::Packet(p)),
            Some(p) = self.data.recv() => Some(Out::Packet(p)),
            else => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn packet(id: &str) -> Packet {
        Packet::SseCancel { id: id.into() }
    }
    #[tokio::test]
    async fn control_bursts_do_not_starve_regular_or_sse_traffic() {
        let (regular, data, control, mut rx) = channel();
        for _ in 0..12 {
            control
                .try_send(Out::Ping(vec![]))
                .unwrap_or_else(|_| panic!("control capacity"));
        }
        regular.send(packet("regular")).await.unwrap();
        data.send(packet("data")).await.unwrap();
        for _ in 0..4 {
            assert!(matches!(rx.next().await, Some(Out::Ping(_))));
        }
        assert!(
            matches!(rx.next().await, Some(Out::Packet(Packet::SseCancel { id })) if id == "regular")
        );
        for _ in 0..4 {
            assert!(matches!(rx.next().await, Some(Out::Ping(_))));
        }
        assert!(
            matches!(rx.next().await, Some(Out::Packet(Packet::SseCancel { id })) if id == "data")
        );
    }
}
