#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
umask 077
mkdir -p secrets
chmod 700 secrets
if [ ! -f secrets/token ]; then
  openssl rand -hex 32 > secrets/token
  # The directory protects the host file; the read-only Docker secret must also
  # be readable by container UID 10001 (Compose bind secrets preserve modes).
  chmod 444 secrets/token
fi
if [ ! -f .env ]; then cp .env.example .env; fi
echo '初始化完成。编辑 .env，配置 DNS 后执行 docker compose up -d --build。'
