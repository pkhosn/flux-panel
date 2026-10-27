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

版本 `frontend-2026.10.18.7`：源码 `2f982fb`，卡片分组改静态名称。npm build、分组管理与批量操作浏览器回归通过；匿名资源下载校验及生产镜像构建通过。SHA256：`a895511dd2b5f9edd67f0a9b1879f00cd2bb822a7ae4b70a7845dd95bc9b6472`。

版本 `frontend-2026.10.18.8`：源码及后端 `36a57fa`，批量文本修改规则。本地 Maven 定向测试、npm build、浏览器预填/校验/结果/窄屏检查通过；匿名下载 SHA256 与生产镜像构建通过。SHA256：`9fc7e0e355a5c7b0dd4f829af26e9ae92490f51f5285cbe193da5419974440d0`。

版本 `frontend-2026.10.21.1`：源码 `550ed94`，批量导出仅保留标准文本与 ny JSONL（dest、listen_port、name），默认标准格式。本地 npm build、浏览器两种格式及多规则/多目标/UDP 验证通过；匿名下载 SHA256 与生产 Dockerfile 构建通过。SHA256：`b80088526e1e3b92edf792c7a53eb7bce1706ccf5fc205ff4bb840ff5b0e8126`。

版本 `frontend-2026.10.26.1`：源码 `8e3b37b`，私有服务器探针页面、安装命令、本人遥测、启用转发和删除。本机 Node 20 npm build、33 项 Maven 测试、Go socket/service、真实隔离面板/Agent 注册与启用转发重启联调、生产包浏览器桌面/窄屏暗色交互通过；匿名下载 SHA256、生产 Dockerfile 构建与 HTTP build-info 检查通过。SHA256：`617e7329c7c957fe172d604cd27c6b1317343baf311247c7ba1d2e0e1b5d9d12`。本机仅4GB且无交换空间时构建被系统终止，启用临时2GB交换空间后使用 NODE_OPTIONS=--max-old-space-size=1024 构建成功；生产仍只下载静态资源。

版本 `frontend-2026.10.26.2`：源码 `7278d03`，修复 Modal 焦点锁导致 HTTP 降级复制为空，探针与转发节点复用 ServerCard/布局，探针增加实时速率。本机 Node20 npm build、浏览器实际剪贴板原生/降级完整内容、原焦点问题复现、节点选择、IP复制、遥测速率与暗色窄屏回归通过；匿名下载 SHA256、生产镜像构建与镜像内页面回归通过。SHA256：`f26f6102cec17c2c9d4e42bfb40fc2950be2a382cef9596cebda4e91c4d6da42`。

版本 `frontend-2026.10.26.3`：源码 `e022d76`，节点/探针当前范围总上传下载速率置于标签右侧，手机独立行靠右，悬停累计流量与覆盖数。本机 npm build、浏览器范围/分组/离线排除/数据等待/桌面位置/320及390px暗色适配与复制回归通过；匿名下载、生产镜像构建、镜像HTTP和浏览器回归通过。SHA256：`5c1c58134d8c62d4b20ca367dc4dc6a6c7013ac3f99bc6aeda612860334eb344`。

版本 `frontend-2026.10.26.4`：源码 `8924429`，网站管理配置规则入口地址隐藏和替代文字，列表/详情保留端口，隐藏时禁用入口复制并关闭旧地址弹窗。Node20 npm build、浏览器设置保存/管理员用户/多入口IPv4IPv6端口/动态切换/目标导出/暗色窄屏通过；匿名下载校验、生产镜像构建和镜像内回归通过。SHA256：`77876f204e92e924eb30848bd489358cec85304257b101f44aec16b62a159a87`。

版本 `frontend-2026.10.26.5`：源码 `9033373`，网站配置五类侧导航与主题卡片、固定保存撤销、草稿保留及离开提醒，复用支付弹窗。Node20本地构建、分类依赖/失败成功保存/撤销/支付编辑/站内离开取消与320/390真实暗色浏览器验证通过；匿名下载校验、生产镜像构建与镜像页面回归通过。SHA256：`966a878ec94cdf2e07a02ef9509647eec8fb827eabdf72cbcfa4f9e36773977a`。

版本 `frontend-2026.10.27.1`：源码 `161d296`，设置分类改顶部标签，移除max-width和第二侧栏，桌面输入扩宽，说明及间距压缩。Node20本地构建、320/390/1024/1440布局、1280输入宽度>600px与原保存支付回归通过；匿名下载校验、生产镜像构建及镜像浏览器回归通过。SHA256：`ce18d89a19cebe169e96119e0d528901dd0e98ac26c8b3585c3302d11cfcfe7a`。
