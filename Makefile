.DEFAULT_GOAL := help
SHELL := /bin/bash

ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
CARGO ?= cargo
DOCKER ?= docker
PYTHON ?= python3
GH ?= gh
IMAGE ?= wss-tunnel:local
PORT ?= 3000
LOCAL_TARGET ?=
NAME ?=
TOKEN_FILE ?= ./secrets/token
TARGET ?= $(shell rustc -vV | sed -n 's/^host: //p')
COMPOSE = $(DOCKER) compose --project-directory "$(ROOT)/server" --env-file "$(ROOT)/server/.env" -f "$(ROOT)/server/compose.yaml"
COMPOSE_IMAGE = $(COMPOSE) -f "$(ROOT)/server/compose.image.yaml"

.PHONY: help init build release fmt fmt-check lint test check e2e protocols-test admin-test real-ip-test revoke-test client server \
	docker-build docker-test compose-check up down logs ps pull up-image package tag publish clean

help: ## 显示常用命令
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_-]+:.*## / {printf "  make %-16s %s\n", $$1, $$2}' "$(ROOT)/Makefile"
	@printf '\n参数：PORT=3000 NAME=demo IMAGE=wss-tunnel:local TARGET=<Rust target>\n发布：make tag VERSION=0.1.0；重试发布：make publish TAG=v0.1.0\n'

init: ## 初始化两端配置和服务端密钥（不覆盖已有文件）
	@cd "$(ROOT)" && bash server/init.sh
	@cd "$(ROOT)" && if [ ! -f client/.env ]; then cp client/.env.example client/.env; fi
	@printf '请编辑两端 .env，并将服务端密钥安全复制到客户端配置的密钥路径。\n'

build: ## 构建客户端和服务端（debug）
	cd "$(ROOT)" && $(CARGO) build --workspace --locked

release: ## 构建客户端和服务端（release，不发布）
	cd "$(ROOT)" && $(CARGO) build --release --workspace --locked

fmt: ## 格式化 Rust 源码
	cd "$(ROOT)" && $(CARGO) fmt --all

fmt-check: ## 检查 Rust 格式
	cd "$(ROOT)" && $(CARGO) fmt --all --check

lint: ## 运行 Clippy，警告视为错误
	cd "$(ROOT)" && $(CARGO) clippy --workspace --locked -- -D warnings

test: ## 运行 Rust 测试
	cd "$(ROOT)" && $(CARGO) test --workspace --locked

check: fmt-check lint test ## 格式、静态检查和 Rust 测试

e2e: build ## 本机服务端经 Traefik 的 WSS 集成测试（需要 Docker）
	cd "$(ROOT)" && env -u TUNNEL_TEST_IMAGE $(PYTHON) -u tests/e2e.py

admin-test: build ## 管理权限、动态名额和详细审计集成测试
	cd "$(ROOT)" && $(PYTHON) -u tests/admin.py

real-ip-test: build ## Traefik 真实 IP 解析及 HTTP/WebSocket 转发（需要 Docker 和 websockets）
	cd "$(ROOT)" && $(PYTHON) -u tests/real_ip.py

revoke-test: build ## 禁用账号立即断开已有隧道和转发连接（需要 websockets）
	cd "$(ROOT)" && $(PYTHON) -u tests/revoke.py

protocols-test: build ## 协议矩阵测试（PYTHON 指向安装了 tests/requirements-protocols.txt 的环境）
	cd "$(ROOT)" && $(PYTHON) -u tests/protocols.py

client: ## 启动客户端：PORT=3000 或 LOCAL_TARGET=https://localhost:8443；可传 NAME
	cd "$(ROOT)/client" && $(CARGO) run --release --locked -p tunnel-client -- $(if $(strip $(LOCAL_TARGET)),--target "$(LOCAL_TARGET)","$(PORT)") $(if $(strip $(NAME)),--name "$(NAME)")

server: ## 本机运行服务端（可传 TOKEN_FILE，域名从 server/.env 读取）
	cd "$(ROOT)/server" && $(CARGO) run --release --locked -p tunnel-server -- --token-file "$(TOKEN_FILE)"

docker-build: ## 构建服务端镜像
	cd "$(ROOT)" && $(DOCKER) build -f server/Dockerfile -t "$(IMAGE)" .

docker-test: build docker-build ## 验证容器服务端与 Traefik bridge 链路
	cd "$(ROOT)" && TUNNEL_TEST_IMAGE="$(IMAGE)" $(PYTHON) -u tests/e2e.py

compose-check: ## 校验实际部署配置（先 make init）
	$(COMPOSE) config --quiet

up: compose-check ## 从源码构建并启动服务端
	$(COMPOSE) -f "$(ROOT)/server/compose.build.yaml" up -d --build

down: ## 停止服务端容器（保留配置和密钥）
	$(COMPOSE) down

logs: ## 跟踪服务端日志
	$(COMPOSE) logs --tail=100 -f tunnel

ps: ## 查看服务端容器状态
	$(COMPOSE) ps

pull: ## 拉取 server/.env 中 TUNNEL_IMAGE 指定的发布镜像
	$(COMPOSE_IMAGE) pull tunnel

up-image: ## 使用发布镜像启动，不执行本地构建
	$(COMPOSE_IMAGE) config --quiet
	$(COMPOSE_IMAGE) up -d --no-build --pull always

package: ## 构建并打包双端二进制与校验文件（可传 TARGET）
	cd "$(ROOT)" && $(CARGO) build --release --workspace --locked --target "$(TARGET)"
	cd "$(ROOT)" && $(PYTHON) scripts/package.py --target "$(TARGET)"

tag: ## 触发创建版本 tag 的 Action：make tag VERSION=0.1.0
	@test -n "$(VERSION)" || { printf '缺少 VERSION，例如 make tag VERSION=0.1.0\n' >&2; exit 1; }
	cd "$(ROOT)" && $(GH) workflow run create-tag.yml --ref main -f version="$(VERSION)"

publish: ## 对已有 tag 触发 Release Action：make publish TAG=v0.1.0
	@test -n "$(TAG)" || { printf '缺少 TAG，例如 make publish TAG=v0.1.0\n' >&2; exit 1; }
	cd "$(ROOT)" && $(GH) workflow run release.yml --ref "$(TAG)"

clean: ## 清理 Cargo 构建缓存（保留 dist、配置和密钥）
	cd "$(ROOT)" && $(CARGO) clean
