# 前端本机发布

## 计划（已获用户确认）

- [x] 本机沿用 npm build 编译静态文件，生成 tar.gz 与 SHA256。
- [x] 新增只包含 Nginx 的生产 Dockerfile，固定下载版本并校验 SHA256；两份 Compose 默认使用它。
- [x] 本地验证静态包、生产镜像、HTTP 页面，以及下载失败不替换服务。
- [x] 上传独立 GitHub Release 资源，确认公网可下载后推送 beta；记录后续默认发布规范。

生产操作仍为 `git pull --ff-only origin beta` 后运行 `./panel_install.sh`，选择 2。后端仍在生产服务器构建。前端下载或校验失败直接终止，运行中的服务继续保留，不回退到源码编译。

## 默认发布约定

今后前端改动必须在本机编译、验证和发布静态资源后才算交付完成，不使用 CI。生产使用 `vite-frontend/Dockerfile.prebuilt`；`Dockerfile` 保留作为本地源码构建入口。

前端资源使用独立版本，例如 `frontend-2026.10.16.1`，不覆盖已有 Agent Release。包包含 `dist` 内容以及记录前端源码树 ID 的 `build-info.json`。生产 Dockerfile 固定版本和 SHA256，禁止使用 `latest` 或覆盖已发布资源。

本机沿用现有构建命令：

```bash
NODE_OPTIONS=--max-old-space-size=768 npm --prefix vite-frontend run build
```

随后将 dist 内容用系统 tar 打包，记录 SHA256，把版本与校验值写入生产 Dockerfile。发布上传 tar.gz 和校验文件后，确认匿名下载与 SHA256 一致，再推送部署配置。安装及更新只从该固定地址取包；旧安装使用选项 2 时会自动下载新 Compose 切换到此方式。


## 首次发布验证

版本：`frontend-2026.10.16.1`，前端源码基准 `c6ddc03`。包大小 1,512,722 字节。

```bash
bash -n panel_install.sh
git diff --check
docker build -f vite-frontend/Dockerfile.prebuilt -t flux-frontend-prebuilt-check:local vite-frontend
curl -fL https://github.com/pkhosn/flux-panel/releases/download/frontend-2026.10.16.1/flux-frontend.tar.gz -o /tmp/flux-frontend-public.tar.gz
sha256sum /tmp/flux-frontend-public.tar.gz
```

SHA256：`dc2031203c2084d7efce6e201d17499df84c1a552e2d73ca7dbcf96fdcfa7763`。

本机实际构建生产镜像成功；临时容器仅绑定 `127.0.0.1:26367`，检查 `/forward` 返回页面、`/build-info.json` 返回对应版本后已清理。两份 Compose `config -q` 通过；规则交互浏览器检查通过。传入全零 FRONTEND_SHA256 时构建按预期失败；模拟前端构建失败时原配置不变，未执行接管或启动。未连接生产数据库。

本地打包使用系统 `tar -czf /tmp/flux-frontend.tar.gz -C vite-frontend/dist .`，然后执行 `sha256sum /tmp/flux-frontend.tar.gz`。`build-info.json` 须在打包前写入本次版本和前端源码树 ID（`git rev-parse HEAD:vite-frontend`）；若前端修改尚未提交，应先提交已验证的源码再生成版本信息。每次重新打包须以实际生成包的 SHA256 更新生产 Dockerfile。


后续版本 `frontend-2026.10.16.2`：源码提交 `413aa49`；移除用户与目标筛选，批量切换使用弹窗。完成本地构建、批量弹窗浏览器检查、匿名下载 SHA256 校验和生产 Dockerfile 构建。SHA256：`48342598b48d4a4d81535f85d697b8191a11ff463e146f707a4e8f28307311d2`。

版本 `frontend-2026.10.16.3`：配套后端与源码 `3e0e232`，保持监听端口迁移及失败结果弹窗；本地 15 项后端测试与浏览器检查通过，匿名包校验及生产镜像构建通过。SHA256：`e3ae0c2635545489f5ec5e69052b4a01917f799e382151ea1a23780650d31fd6`。

版本 `frontend-2026.10.16.4`：源码 `357af1f`，移除规则分组界面，保留历史数据及节点分组。本地 npm 构建、浏览器分组移除与旧 groupId 保存检查、迁移结果回归通过；匿名下载校验及生产镜像构建通过。SHA256：`da602daeba4f9a6936fda25f3c5d0194a4248bb666f3fd11152c461446d785ec`。

版本 `frontend-2026.10.18.1`：源码 `4c33217`，转发表单移除入口节点分组，删除独立重命名操作。本地 npm 构建、浏览器新增/编辑检查、全部隧道可选与保存回归通过；匿名资源包 SHA256 校验及生产 Dockerfile 构建通过。SHA256：`99de8813c0e24f7d990bdbfbce603db76f6cbad7712430d0d728a3d499ca65aa`。

版本 `frontend-2026.10.18.2`：源码 `41d7994`，规则行移除 ID 与统计图标。本地构建及浏览器详情 ID/对应规则统计/顶部统计检查通过；匿名资源下载 SHA256 与生产 Dockerfile 构建通过。SHA256：`24e8bad632bad6df8d0fedd1d33d39e4201d2f3a5e850f5daec0d14d13bb370d`。

版本 `frontend-2026.10.18.3`：源码及配套后端 `32a6fac`，节点卡片手动三网延迟。本机地址实测任务完成但三网无 ICMP 回包；本地 Maven、新增三项缓存/权限/解析测试、前端构建及模拟 API 浏览器检查通过。匿名下载 SHA256 与生产 Dockerfile 构建通过。SHA256：`52c725b70be571110052ce655775963293e7ad642bfc9d122043b2259adc81d9`。

版本 `frontend-2026.10.18.4`：源码 `0f2641d`，绿色延迟与卡片 IP/IPv4/IPv6 点击复制。本地构建、剪贴板完整值/绿色样式/空值/窄屏浏览器检查通过；匿名下载校验与生产 Dockerfile 构建通过。SHA256：`40795a649776c01924b9e1fad23c9ee7f218d1deaa43e3d07840b82485b8468d`。

版本 `frontend-2026.10.18.5`：源码及配套后端 `00cc8ab`，每次手动测速创建新任务。4 项 NodeLatencyTest、前端构建与浏览器连续两次测试更新结果通过；匿名下载 SHA256 校验与生产镜像构建通过。SHA256：`9ffa0efae9fd46ccf0af5c06264c0cab9f025d46cbf86dcaf84ef4bbee744190`。

版本 `frontend-2026.10.18.6`：源码及配套后端 `d18a165`，节点分组管理、批量加入/移出、卡片切换。Maven clean test、npm build 与浏览器权限/分组/窄屏验证通过；匿名资源下载校验与生产镜像构建通过。SHA256：`a8d4b0a22021496006f7def36223a2d7ce3ab04eb12759af1bcf915befb893b3`。
