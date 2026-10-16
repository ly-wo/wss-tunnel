FROM rust:1.98.1-bookworm AS builder
WORKDIR /app
COPY Cargo.toml Cargo.lock protocol.rs rust-toolchain.toml ./
COPY client ./client
COPY server ./server
RUN cargo build --release --locked -p tunnel-server

FROM debian:bookworm-slim
COPY --from=builder /app/target/release/tunnel-server /usr/local/bin/tunnel-server
RUN install -d -m 0700 -o 10001 -g 10001 /data
ENV TUNNEL_DATABASE=/data/auth.sqlite3
VOLUME ["/data"]
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["tunnel-server"]
