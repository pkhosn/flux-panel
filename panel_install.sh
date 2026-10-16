#!/bin/bash
set -e

# 解决 macOS 下 tr 可能出现的非法字节序列问题
export LANG=en_US.UTF-8
export LC_ALL=C



# 全局下载地址配置
REPO_OWNER="pkhosn"
REPO_NAME="flux-panel"
REPO_BRANCH="beta"
COMPOSE_PROJECT_NAME="flux-panel"
SCRIPT_VERSION="2026-10-16.1"
DOCKER_COMPOSEV4_URL="https://raw.githubusercontent.com/${REPO_OWNER}/${REPO_NAME}/refs/heads/${REPO_BRANCH}/docker-compose-v4.yml"
DOCKER_COMPOSEV6_URL="https://raw.githubusercontent.com/${REPO_OWNER}/${REPO_NAME}/refs/heads/${REPO_BRANCH}/docker-compose-v6.yml"

COUNTRY=$(curl -fsS --connect-timeout 5 --max-time 8 https://ipinfo.io/country 2>/dev/null || true)
if [ "$COUNTRY" = "CN" ]; then
    # 拼接 URL
    DOCKER_COMPOSEV4_URL="https://ghfast.top/${DOCKER_COMPOSEV4_URL}"
    DOCKER_COMPOSEV6_URL="https://ghfast.top/${DOCKER_COMPOSEV6_URL}"
fi



# 根据IPv6支持情况选择docker-compose URL
get_docker_compose_url() {
  if check_ipv6_support > /dev/null 2>&1; then
    echo "$DOCKER_COMPOSEV6_URL"
  else
    echo "$DOCKER_COMPOSEV4_URL"
  fi
}

# 检查 docker-compose 或 docker compose 命令
check_docker() {
  if command -v docker-compose &> /dev/null; then
    DOCKER_CMD="docker-compose"
  elif command -v docker &> /dev/null; then
    if docker compose version &> /dev/null; then
      DOCKER_CMD="docker compose"
    else
      echo "错误：检测到 docker，但不支持 'docker compose' 命令。请安装 docker-compose 或更新 docker 版本。"
      exit 1
    fi
  else
    echo "错误：未检测到 docker 或 docker-compose 命令。请先安装 Docker。"
    exit 1
  fi
  echo "检测到 Docker 命令：$DOCKER_CMD"
}

# 固定 Compose 项目名，确保从任意部署目录更新时都复用原有容器、
# gost-network、sqlite_data 和 backend_logs。
compose() {
  COMPOSE_BAKE=false COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT_NAME" $DOCKER_CMD "$@"
}

# Build services separately so Maven and Vite do not compete for memory.
# Do not stop or replace running services before both builds succeed.
build_panel() {
  echo "🏗️ 构建后端（1/2）..."
  compose "$@" build backend || return 1
  echo "🏗️ 构建前端（2/2）..."
  compose "$@" build frontend || return 1
}

backend_container_id() {
  docker ps -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" --filter "label=com.docker.compose.service=backend" | head -n1
}

prepare_shared_resources() {
  if ! docker network inspect gost-network >/dev/null 2>&1; then
    docker network create --driver bridge gost-network >/dev/null
  fi
  docker volume inspect sqlite_data >/dev/null 2>&1 || docker volume create sqlite_data >/dev/null
  docker volume inspect backend_logs >/dev/null 2>&1 || docker volume create backend_logs >/dev/null
}

recover_existing_config() {
  local backend_port frontend_port jwt backend_id frontend_id
  if [[ ! -f .env ]]; then
    touch .env
    chmod 600 .env
  fi
  backend_id=$(docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" --filter "label=com.docker.compose.service=backend" | head -n1)
  frontend_id=$(docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" --filter "label=com.docker.compose.service=frontend" | head -n1)
  backend_id=${backend_id:-springboot-backend}
  frontend_id=${frontend_id:-vite-frontend}
  backend_port=$(docker port "$backend_id" 6365/tcp 2>/dev/null | head -n1 | sed -E 's/.*:([0-9]+)$/\1/' || true)
  frontend_port=$(docker port "$frontend_id" 80/tcp 2>/dev/null | head -n1 | sed -E 's/.*:([0-9]+)$/\1/' || true)
  jwt=$(docker inspect "$backend_id" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | sed -n 's/^JWT_SECRET=//p' | head -n1 || true)
  [[ -n "$backend_port" ]] || backend_port=6365
  [[ -n "$frontend_port" ]] || frontend_port=6366
  [[ -n "$jwt" ]] || jwt=$(grep -E '^JWT_SECRET=' .env | cut -d= -f2- || true)
  [[ -n "$jwt" ]] || jwt=$(generate_random)
  # 保留已有非空值；空值从当前运行容器恢复，避免随机映射端口。
  for key in BACKEND_PORT FRONTEND_PORT JWT_SECRET; do
    if ! grep -Eq "^${key}=.+" .env; then
      sed -i "/^${key}=/d" .env
      case "$key" in
        BACKEND_PORT) echo "BACKEND_PORT=$backend_port" >> .env ;;
        FRONTEND_PORT) echo "FRONTEND_PORT=$frontend_port" >> .env ;;
        JWT_SECRET) echo "JWT_SECRET=$jwt" >> .env ;;
      esac
    fi
  done
  set -a
  . ./.env
  set +a
}

take_over_existing_containers() {
  for container in springboot-backend vite-frontend; do
    if docker inspect "$container" >/dev/null 2>&1; then
      echo "🔁 接管现有容器：$container"
      docker stop -t 30 "$container" >/dev/null 2>&1 || true
      docker rm -f "$container" >/dev/null 2>&1 || true
    fi
  done
}

# 检测系统是否支持 IPv6
check_ipv6_support() {
  echo "🔍 检测 IPv6 支持..."

  # 检查是否有 IPv6 地址（排除 link-local 地址）
  if ip -6 addr show | grep -v "scope link" | grep -q "inet6"; then
    echo "✅ 检测到系统支持 IPv6"
    return 0
  elif ifconfig 2>/dev/null | grep -v "fe80:" | grep -q "inet6"; then
    echo "✅ 检测到系统支持 IPv6"
    return 0
  else
    echo "⚠️ 未检测到 IPv6 支持"
    return 1
  fi
}



# 配置 Docker 启用 IPv6
configure_docker_ipv6() {
  echo "🔧 配置 Docker IPv6 支持..."

  # 检查操作系统类型
  OS_TYPE=$(uname -s)

  if [[ "$OS_TYPE" == "Darwin" ]]; then
    # macOS 上 Docker Desktop 已默认支持 IPv6
    echo "✅ macOS Docker Desktop 默认支持 IPv6"
    return 0
  fi

  # Docker daemon 配置文件路径
  DOCKER_CONFIG="/etc/docker/daemon.json"

  # 检查是否需要 sudo
  if [[ $EUID -ne 0 ]]; then
    SUDO_CMD="sudo"
  else
    SUDO_CMD=""
  fi

  # 检查 Docker 配置文件
  if [ -f "$DOCKER_CONFIG" ]; then
    # 检查是否已经配置了 IPv6
    if grep -q '"ipv6"' "$DOCKER_CONFIG"; then
      echo "✅ Docker 已配置 IPv6 支持"
    else
      echo "📝 更新 Docker 配置以启用 IPv6..."
      # 备份原配置
      $SUDO_CMD cp "$DOCKER_CONFIG" "${DOCKER_CONFIG}.backup"

      # 使用 jq 或 sed 添加 IPv6 配置
      if command -v jq &> /dev/null; then
        $SUDO_CMD jq '. + {"ipv6": true, "fixed-cidr-v6": "fd00::/80"}' "$DOCKER_CONFIG" > /tmp/daemon.json && $SUDO_CMD mv /tmp/daemon.json "$DOCKER_CONFIG"
      else
        # 如果没有 jq，使用 sed
        $SUDO_CMD sed -i 's/^{$/{\n  "ipv6": true,\n  "fixed-cidr-v6": "fd00::\/80",/' "$DOCKER_CONFIG"
      fi

      echo "🔄 重启 Docker 服务..."
      if command -v systemctl &> /dev/null; then
        $SUDO_CMD systemctl restart docker
      elif command -v service &> /dev/null; then
        $SUDO_CMD service docker restart
      else
        echo "⚠️ 请手动重启 Docker 服务"
      fi
      sleep 5
    fi
  else
    # 创建新的配置文件
    echo "📝 创建 Docker 配置文件..."
    $SUDO_CMD mkdir -p /etc/docker
    echo '{
  "ipv6": true,
  "fixed-cidr-v6": "fd00::/80"
}' | $SUDO_CMD tee "$DOCKER_CONFIG" > /dev/null

    echo "🔄 重启 Docker 服务..."
    if command -v systemctl &> /dev/null; then
      $SUDO_CMD systemctl restart docker
    elif command -v service &> /dev/null; then
      $SUDO_CMD service docker restart
    else
      echo "⚠️ 请手动重启 Docker 服务"
    fi
    sleep 5
  fi
}

# 显示菜单
show_menu() {
  echo "==============================================="
  echo "          面板管理脚本"
  echo "==============================================="
  echo "请选择操作："
  echo "1. 安装面板"
  echo "2. 更新面板"
  echo "3. 卸载面板"
  echo "4. 导出备份"
  echo "5. 导入备份"
  echo "6. 退出"
  echo "==============================================="
}

generate_random() {
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c16
}

# 删除脚本自身
delete_self() {
  if [[ "${AUTO_DELETE_SCRIPT:-0}" != "1" ]]; then
    return 0
  fi
  echo ""
  echo "🗑️ 操作已完成，正在清理脚本文件..."
  SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
  sleep 1
  rm -f "$SCRIPT_PATH" && echo "✅ 脚本文件已删除" || echo "❌ 删除脚本文件失败"
}



# 获取用户输入的配置参数
get_config_params() {
  echo "🔧 请输入配置参数："

  read -p "前端端口（默认 6366）: " FRONTEND_PORT
  FRONTEND_PORT=${FRONTEND_PORT:-6366}

  read -p "后端端口（默认 6365）: " BACKEND_PORT
  BACKEND_PORT=${BACKEND_PORT:-6365}

  # 生成JWT密钥
  JWT_SECRET=$(generate_random)
}

# 安装功能
install_panel() {
  echo "🚀 开始安装面板..."
  check_docker
  get_config_params

  echo "🔽 下载必要文件..."
  DOCKER_COMPOSE_URL=$(get_docker_compose_url)
  echo "📡 选择配置文件：$(basename "$DOCKER_COMPOSE_URL")"
  curl -L -o docker-compose.yml "$DOCKER_COMPOSE_URL"
  echo "✅ 文件准备完成"

  # 自动检测并配置 IPv6 支持
  if check_ipv6_support; then
    echo "🚀 系统支持 IPv6，自动启用 IPv6 配置..."
    configure_docker_ipv6
  fi

  cat > .env <<EOF
JWT_SECRET=$JWT_SECRET
FRONTEND_PORT=$FRONTEND_PORT
BACKEND_PORT=$BACKEND_PORT
EOF

  echo "🚀 构建并启动 docker 服务..."
  build_panel || return 1
  compose up -d --no-build

  echo "🎉 部署完成"
  echo "🌐 访问地址: http://服务器IP:$FRONTEND_PORT"
  echo "📖 部署完成后请阅读下使用文档，求求了啊，不要上去就是一顿操作"
  echo "📚 文档地址: https://tes.cc/guide.html"
  echo "💡 默认管理员账号: admin_user / admin_user"
  echo "⚠️  登录后请立即修改默认密码！"


}

# 更新功能
update_panel() {
  echo "🔄 开始更新面板..."
  echo "📌 更新脚本版本：$SCRIPT_VERSION"
  check_docker

  echo "🔽 下载最新配置文件..."
  recover_existing_config
  DOCKER_COMPOSE_URL=$(get_docker_compose_url)
  echo "📡 选择配置文件：$(basename "$DOCKER_COMPOSE_URL")"
  local candidate
  candidate=$(mktemp ./docker-compose.update.XXXXXX)
  if ! curl -fL --connect-timeout 10 --max-time 120 --retry 2 -o "$candidate" "$DOCKER_COMPOSE_URL"; then
    rm -f "$candidate"
    echo "❌ 配置下载失败，现有服务继续运行"
    return 1
  fi
  if ! compose -f "$candidate" config -q; then
    rm -f "$candidate"
    echo "❌ 配置校验失败，现有服务继续运行"
    return 1
  fi
  echo "🏗️ 构建新版本（现有服务继续运行）..."
  if ! build_panel -f "$candidate"; then
    rm -f "$candidate"
    echo "❌ 构建失败，现有服务继续运行"
    return 1
  fi
  [[ ! -f docker-compose.yml ]] || cp -p docker-compose.yml docker-compose.yml.previous
  mv -f "$candidate" docker-compose.yml
  prepare_shared_resources
  take_over_existing_containers
  echo "🚀 替换服务，保留原数据库、密钥和端口..."
  if ! compose up -d --no-build; then
    echo "❌ 启动失败，请检查下方日志及 docker-compose.yml.previous"
    compose logs --tail=50 || true
    return 1
  fi

  # 等待服务启动
  echo "⏳ 等待服务启动..."

  # 检查后端容器健康状态
  echo "🔍 检查后端服务状态..."
  for i in {1..90}; do
    backend_container=$(backend_container_id)
    if [[ -n "$backend_container" ]]; then
      BACKEND_HEALTH=$(docker inspect -f '{{.State.Health.Status}}' "$backend_container" 2>/dev/null || echo "unknown")
      if [[ "$BACKEND_HEALTH" == "healthy" ]]; then
        echo "✅ 后端服务健康检查通过"
        break
      elif [[ "$BACKEND_HEALTH" == "starting" ]]; then
        # 继续等待
        :
      elif [[ "$BACKEND_HEALTH" == "unhealthy" ]]; then
        echo "⚠️ 后端健康状态：$BACKEND_HEALTH"
      fi
    else
      echo "⚠️ 后端容器未找到或未运行"
      BACKEND_HEALTH="not_running"
    fi
    if [ $i -eq 90 ]; then
      echo "❌ 后端服务启动超时（90秒）"
      echo "🔍 当前状态: $(docker inspect -f '{{.State.Health.Status}}' "$backend_container" 2>/dev/null || echo '容器不存在')"
      echo "🛑 更新终止"
      return 1
    fi
    # 每15秒显示一次进度
    if [ $((i % 15)) -eq 1 ]; then
      echo "⏳ 等待后端服务启动... ($i/90) 状态：${BACKEND_HEALTH:-unknown}"
    fi
    sleep 1
  done

  echo "🔍 检查网页与后端 HTTP 访问..."
  if ! curl --noproxy '*' -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:${BACKEND_PORT}/flow/test" >/dev/null; then
    echo "❌ 后端端口 ${BACKEND_PORT} 无法访问"
    compose logs --tail=50 || true
    return 1
  fi
  if ! curl --noproxy '*' -fsS --retry 5 --retry-connrefused --retry-delay 2 --connect-timeout 5 --max-time 15 "http://127.0.0.1:${FRONTEND_PORT}/" >/dev/null; then
    echo "❌ 网页端口 ${FRONTEND_PORT} 无法访问"
    compose logs --tail=50 || true
    return 1
  fi
  echo "✅ 更新完成，网页端口：${FRONTEND_PORT}，后端端口：${BACKEND_PORT}"
  echo "📌 请使用原服务器地址和网页端口访问面板"
}



# 卸载功能
uninstall_panel() {
  echo "🗑️ 开始卸载面板..."
  check_docker

  if [[ ! -f "docker-compose.yml" ]]; then
    echo "⚠️ 未找到 docker-compose.yml 文件，正在下载以完成卸载..."
    DOCKER_COMPOSE_URL=$(get_docker_compose_url)
    echo "📡 选择配置文件：$(basename "$DOCKER_COMPOSE_URL")"
    curl -L -o docker-compose.yml "$DOCKER_COMPOSE_URL"
    echo "✅ docker-compose.yml 下载完成"
  fi

  read -p "确认卸载面板吗？此操作将停止并删除所有容器和数据 (y/N): " confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "❌ 取消卸载"
    return 0
  fi

  echo "🛑 停止并删除容器、镜像、卷..."
  compose down --rmi all --volumes --remove-orphans
  echo "🧹 删除配置文件..."
  rm -f docker-compose.yml .env
  echo "✅ 卸载完成"
}

# 导出备份
export_backup() {
  echo "📦 开始导出备份..."
  check_docker

  BACKUP_DIR="${PWD}"
  TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
  BACKUP_FILE="${BACKUP_DIR}/flux-panel-backup-${TIMESTAMP}.tar.gz"
  TMP_DIR="$(mktemp -d)"

  mkdir -p "${TMP_DIR}/volumes/sqlite_data" "${TMP_DIR}/volumes/backend_logs"

  # 备份关键文件
  [[ -f "docker-compose.yml" ]] && cp -f docker-compose.yml "${TMP_DIR}/" || true
  [[ -f ".env" ]] && cp -f .env "${TMP_DIR}/" || true

  # 备份 sqlite_data volume
  if docker volume inspect sqlite_data >/dev/null 2>&1; then
    docker run --rm \
      -v sqlite_data:/from \
      -v "${TMP_DIR}/volumes/sqlite_data":/to \
      alpine:3.20 sh -c "cp -a /from/. /to/ 2>/dev/null || true"
  else
    echo "⚠️ 未找到 volume: sqlite_data，跳过"
  fi

  # 备份 backend_logs volume
  if docker volume inspect backend_logs >/dev/null 2>&1; then
    docker run --rm \
      -v backend_logs:/from \
      -v "${TMP_DIR}/volumes/backend_logs":/to \
      alpine:3.20 sh -c "cp -a /from/. /to/ 2>/dev/null || true"
  else
    echo "⚠️ 未找到 volume: backend_logs，跳过"
  fi

  cat > "${TMP_DIR}/manifest.txt" <<EOF
backup_time=${TIMESTAMP}
repo=${REPO_OWNER}/${REPO_NAME}
branch=${REPO_BRANCH}
host=$(hostname)
pwd=${PWD}
EOF

  tar -czf "${BACKUP_FILE}" -C "${TMP_DIR}" .
  rm -rf "${TMP_DIR}"

  echo "✅ 备份导出完成: ${BACKUP_FILE}"
  echo "📌 备份包含: docker-compose.yml / .env / sqlite_data / backend_logs"
}

# 导入备份
import_backup() {
  echo "📥 开始导入备份..."
  check_docker

  read -p "请输入备份文件路径（.tar.gz）: " BACKUP_FILE
  if [[ -z "${BACKUP_FILE}" || ! -f "${BACKUP_FILE}" ]]; then
    echo "❌ 备份文件不存在"
    return 1
  fi

  TMP_DIR="$(mktemp -d)"
  tar -xzf "${BACKUP_FILE}" -C "${TMP_DIR}"

  if [[ ! -f "${TMP_DIR}/docker-compose.yml" ]]; then
    echo "⚠️ 备份中未包含 docker-compose.yml，将继续使用当前目录配置"
  fi

  read -p "⚠️ 导入会覆盖当前数据卷，确认继续？(y/N): " confirm
  if [[ "${confirm}" != "y" && "${confirm}" != "Y" ]]; then
    rm -rf "${TMP_DIR}"
    echo "❌ 取消导入"
    return 0
  fi

  # 先优雅停止再 down
  docker stop -t 30 springboot-backend 2>/dev/null || true
  docker stop -t 10 vite-frontend 2>/dev/null || true
  sleep 3
  compose down || true

  # 恢复配置文件
  [[ -f "${TMP_DIR}/docker-compose.yml" ]] && cp -f "${TMP_DIR}/docker-compose.yml" ./docker-compose.yml
  [[ -f "${TMP_DIR}/.env" ]] && cp -f "${TMP_DIR}/.env" ./.env

  # 确保 volumes 存在
  docker volume create sqlite_data >/dev/null
  docker volume create backend_logs >/dev/null

  # 清空并恢复 sqlite_data
  docker run --rm -v sqlite_data:/to alpine:3.20 sh -c "rm -rf /to/* /to/.[!.]* /to/..?* 2>/dev/null || true"
  if [[ -d "${TMP_DIR}/volumes/sqlite_data" ]]; then
    docker run --rm \
      -v "${TMP_DIR}/volumes/sqlite_data":/from \
      -v sqlite_data:/to \
      alpine:3.20 sh -c "cp -a /from/. /to/ 2>/dev/null || true"
  fi

  # 清空并恢复 backend_logs
  docker run --rm -v backend_logs:/to alpine:3.20 sh -c "rm -rf /to/* /to/.[!.]* /to/..?* 2>/dev/null || true"
  if [[ -d "${TMP_DIR}/volumes/backend_logs" ]]; then
    docker run --rm \
      -v "${TMP_DIR}/volumes/backend_logs":/from \
      -v backend_logs:/to \
      alpine:3.20 sh -c "cp -a /from/. /to/ 2>/dev/null || true"
  fi

  rm -rf "${TMP_DIR}"

  echo "🚀 启动服务..."
  build_panel || return 1
  compose up -d --no-build

  echo "🔍 检查后端服务状态..."
  for i in {1..90}; do
    backend_container=$(backend_container_id)
    if [[ -n "$backend_container" ]]; then
      BACKEND_HEALTH=$(docker inspect -f '{{.State.Health.Status}}' "$backend_container" 2>/dev/null || echo "unknown")
      if [[ "${BACKEND_HEALTH}" == "healthy" ]]; then
        echo "✅ 导入完成，后端服务健康"
        return 0
      fi
    fi
    if [ $i -eq 90 ]; then
      echo "❌ 导入后服务启动超时，请检查日志"
      return 1
    fi
    sleep 1
  done
}

# 主逻辑
main() {

  # 显示交互式菜单
  while true; do
    show_menu
    read -p "请输入选项 (1-6): " choice

    case $choice in
      1)
        install_panel
        delete_self
        exit 0
        ;;
      2)
        update_panel
        delete_self
        exit 0
        ;;
      3)
        uninstall_panel
        delete_self
        exit 0
        ;;
      4)
        export_backup
        delete_self
        exit 0
        ;;
      5)
        import_backup
        delete_self
        exit 0
        ;;
      6)
        echo "👋 退出脚本"
        delete_self
        exit 0
        ;;
      *)
        echo "❌ 无效选项，请输入 1-6"
        echo ""
        ;;
    esac
  done
}

# 执行主函数
main
