# flux-panel

基于 `gost` 的转发管理面板，支持多用户、多隧道、转发管理、限速与流量统计。

## 最新部署教程（自有仓库版）

当前部署链路已切换到你的仓库 `pkhosn/flux-panel`，不依赖上游 `bqlpfy` 仓库。

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
