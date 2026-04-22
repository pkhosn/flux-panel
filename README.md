# flux-panel

基于 `gost` 的转发管理面板，支持多用户、多隧道、转发管理、限速与流量统计。

## 最新部署教程（自有仓库版）

### 1) 面板端安装

```bash
curl -L https://raw.githubusercontent.com/pkhosn/flux-panel/refs/heads/beta/panel_install.sh -o panel_install.sh && chmod +x panel_install.sh && ./panel_install.sh
```

执行后按提示输入：
- 前端端口（默认 `6366`）
- 后端端口（默认 `6365`）

脚本会自动：
- 选择 v4/v6 compose
- 生成 `.env`
- `docker compose up -d --build` 构建并启动

默认管理员账号：
- 用户名：`admin_user`
- 密码：`admin_user`

首次登录请立即修改默认密码。

### 2) 面板端更新

在面板部署目录再次运行：

```bash
./panel_install.sh
```

菜单选择 `2. 更新面板` 即可。  
更新流程会自动重新拉取最新 compose 并执行 `up -d --build`。

### 3) 面板端卸载

在面板部署目录运行：

```bash
./panel_install.sh
```

菜单选择 `3. 卸载面板`。

## 导入导出

已在 `panel_install.sh` 中提供菜单功能：
- `4. 导出备份`
- `5. 导入备份`

### 导出备份

在面板部署目录运行：

```bash
./panel_install.sh
```

选择 `4. 导出备份`。  
会在当前目录生成：

```bash
flux-panel-backup-YYYYmmdd-HHMMSS.tar.gz
```

### 导入备份

在目标机器的面板部署目录运行：

```bash
./panel_install.sh
```

选择 `5. 导入备份`，输入备份包路径并确认。

### 备份实际包含内容

- `docker-compose.yml`（若导出时当前目录存在）
- `.env`（若导出时当前目录存在）
- Docker Volume: `sqlite_data`（核心数据库）
- Docker Volume: `backend_logs`（日志）

### 无法通过该方式完全恢复的内容

以下内容不在当前备份包内，需额外处理：

- 服务器系统级配置：防火墙规则、内核参数、系统用户与权限、时区等
- Docker daemon 配置（如 `/etc/docker/daemon.json`）
- 反向代理/证书配置（如 Nginx、Caddy、TLS 证书）
- 节点机 `flux_agent` 的系统服务与配置（节点端需单独备份/恢复）
- 非默认路径下你手工新增的自定义文件（除非你自行纳入备份）

### 导入后建议执行的额外动作

1. 检查容器状态：`docker ps`
2. 检查后端健康：`docker inspect -f '{{.State.Health.Status}}' springboot-backend`
3. 登录面板确认：用户、隧道、转发、限速规则是否完整
4. 若有反代域名，验证外部访问与证书状态
5. 在节点机器确认 `flux_agent` 服务状态并重连

### 版本建议

- 建议在相同主版本间导入（避免跨大版本结构差异）。
- 若你修改过 compose 或 volume 名称，请先校验脚本中的默认卷名是否匹配。

### 4) 节点端安装（flux_agent）

```bash
curl -L https://raw.githubusercontent.com/pkhosn/flux-panel/refs/heads/beta/install.sh -o install.sh && chmod +x install.sh && ./install.sh
```

可选非交互参数：

```bash
./install.sh -a <面板地址:端口> -s <节点密钥>
```

例如：

```bash
./install.sh -a 1.2.3.4:6365 -s your_secret
```

### 5) 节点端更新/卸载

在节点机器运行：

```bash
./install.sh
```

菜单选择：
- `2. 更新`
- `3. 卸载`

## 常用排查

查看容器状态：

```bash
docker ps
```

查看面板后端日志：

```bash
docker logs -f springboot-backend
```

查看面板前端日志：

```bash
docker logs -f vite-frontend
```

查看节点服务状态：

```bash
systemctl status flux_agent
```

查看节点服务日志：

```bash
journalctl -u flux_agent -f
```

## 说明

- 本项目仅供合法合规场景使用。
- 使用前请确认符合当地法律法规。
