# Cloudflare 动态 DNS 同步

状态：实现、本地验证与不可变资源发布完成。2026-11-04。

## 目标

在网站配置增加管理员可见的“动态 DNS”分类。将来源 DDNS 域名的当前 IPv4，同步到 Cloudflare 中明确绑定的一条 A 记录。同名其他记录不修改，不创建或删除 DNS 记录，不提供负载均衡或健康检查。

## 现有依据

- `ViteConfigController` 的 list/get 公开，`ViteConfigServiceImpl` 返回配置原值，因此凭证不能放进现有网站配置数据。
- `SQLiteConfig.ensureSchema` 已承担向后兼容建表，可增加独立凭证与同步任务表。
- `common/task/OrderExpiry` 等使用 Spring Scheduled，可复用后台调度机制。
- `pages/config.tsx` 已有顶部分类，可加入独立组件，凭证及同步任务独立保存，不混入原配置草稿。
- 前端交付遵循 AGENTS.md 和 frontend-release.md，全部验证在本机完成。

## 界面与操作

管理员先保存 Cloudflare API Token 和 Zone ID。Token 只显示“已配置”，留空保留；替换需显式提交。使用限定该 Zone 的 DNS 读取、编辑权限。

新增同步任务：填写名称与来源 DDNS 域名，读取指定 Zone 的 A 记录供选择，展示目标域名和当前 IP，最终保存不可变的记录 ID 绑定。允许同域名的不同记录分别绑定，禁止多个任务抢占同一记录。

任务列表显示来源域名、目标域名、启用状态、来源/目标 IP、检查及更新时刻和简洁错误。提供编辑、启停、删除同步任务、立即同步。删除任务不删除 Cloudflare DNS 记录。手机布局与暗色样式沿用项目组件。

## 任务与验收

- [x] 1. 存储与权限：增加独立凭证及任务存储、管理员接口。凭证后端保存并加密，使用部署已有稳定密钥派生加密密钥，不回显、不写请求日志。确认备份恢复所需密钥说明。验证未登录和普通用户无法访问，公共配置不泄露凭证。
- [x] 2. Cloudflare 对接：固定官方 API 地址，验证 Token/Zone，分页读取 A 记录，按记录 ID 获取和更新。更新前核对 Zone、名称、类型及仅 DNS 状态；仅修改 content，保留 TTL 等设置。记录被删除、改名或启用代理时明确报错，不自动猜测替代记录。
- [x] 3. 同步任务：每分钟检查，IP 未变跳过更新；立即同步跳过等待。来源仅接受域名，通过有超时的公共 DNS 查询获取 IPv4；无地址、多个不同 IPv4、解析/API失败均保留旧记录并报错。避免同任务并发，限制单次网络等待，网络调用不包入数据库事务。
- [x] 4. 网站配置界面：新增分类、凭证配置、记录选择、任务管理、状态及手动同步反馈，管理员可用，适配窄屏与暗色模式。
- [x] 5. 本地验证：沿用 Maven/JUnit，模拟 DNS 与 Cloudflare API 覆盖变化/不变、错误、同名其他记录不变、并发与凭证隐私；本机前端构建及浏览器检查。使用隔离数据库，不改真实 DNS。真实 Cloudflare 联调需配置凭证及明确绑定记录后执行。
- [x] 6. 发布：本机编译静态包、上传不可变 GitHub Release、匿名下载校验 SHA256、验证生产镜像，再推送 beta；保持 panel_install.sh 选项 2 更新方式。

每步验证通过后更新记录，失败时停止提交。首次绑定时需用户核实对应记录，运行后不依靠旧 IP 猜测记录归属。DNS 缓存可能使变更延迟生效，界面明确这一限制。

## 实施与验证记录

2026-11-04：独立 ddns_account/ddns_task 表启动迁移；全部接口复用 JWT 与 RequireRole，省略请求日志注解。凭证使用 JWT_SECRET 派生 AES-GCM 密钥，原 panel_install.sh 导出/导入已包含 .env。更换密钥须重填 Token。

复用 JDK HttpClient，固定 Cloudflare 官方 API 和 Google DNS-over-HTTPS；连接 3 秒、请求 5 秒、不跟随重定向。来源必须唯一公网 IPv4；每条任务绑定 Zone/记录 ID/记录名，仅 PATCH content，保留 TTL 等字段。单 worker 异步调度，每分钟发起检查；任务多或远端延迟时一轮可超过一分钟。任务上限 50，Zone A 记录上限 1000；全局 tryLock 阻止编辑与网络更新竞争，不持数据库事务等待网络。最多 50 条手动任务排队，重复任务不重复入队。

本地 Maven clean package 全部 41 项通过，其中 DdnsServiceTest 7 项覆盖隔离、失败、重复/并发、加密、权限及解析，AdminApplicationTests 验证真实迁移与匿名 HTTP 拒绝。首次测试重设 Mockito 抛错桩时误触发异常，改用 doReturn 后通过。未访问真实 Cloudflare 记录。

复验后端：沿用 docs/tunnel-service-rebuild.md 的 Docker Maven clean package；定向运行可传 -Dtest=DdnsServiceTest,AdminApplicationTests test。前端沿用 docs/frontend-release.md 的 Node20 npm run build。浏览器通过本地 Playwright mock API 验证凭证失败/成功、清空 Token、同名记录按 IP/ID 选择、任务编辑、同步错误、暂停、删除及 320/390/1280px 暗色布局；原分类/草稿/支付/保存回归一并执行。测试脚本位于本机 /tmp/flux-browser-check/ddns.cjs 和 settings-ddns-regression.cjs，不作为项目新增构建脚本。

发布版本 frontend-2026.11.04.1（源码 763bf34），匿名包逐字节比对通过，固定 SHA256 的生产镜像构建、镜像内 DDNS 浏览器检查与 build-info 通过。动态 DNS 分类隐藏通用配置保存栏，独立操作保存；分类切换保留凭证输入草稿。
