#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f .env ]; then echo '请先 cp .env.example .env 并编辑服务地址、密钥文件路径。' >&2; exit 1; fi
exec ../target/release/tunnel-client "$@"
