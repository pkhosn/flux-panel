# Flux 转发规则页面与底层能力复刻计划

状态：用户已确认实施。各阶段按可验证的小步迭代推进；阶段一、二、三和五均有部分能力已推送到 beta。已完成本地 Agent 环回转发检查；面板到 Agent 的完整下发与生产环境未验证。

## 目标

按 Nekopass `4fed7f4` 的转发规则页实现其可见操作及对应底层能力，同时延续 Flux 的用户、隧道、配额、Agent、数据库和生产数据。目标包括规则分组、协议选择、批量管理、入口切换、流量统计与清空展示值、Proxy Protocol、回源 TLS、共享 TLS 入口及 SNI 分流、完整规则导入导出。保留 Flux 的诊断、强制删除和旧版导入导出。

对照资料：Nekopass `web/src/RulesPage.vue`、`web/src/rule-transfer.ts`、`docs/guide/rules.md`、`docs/security/tls-inbound.md`、`internal/control/rules.go`、`internal/control/tls_inbound.go`；Flux `vite-frontend/src/pages/forward.tsx`、`springboot-backend/src/main/java/com/admin/service/impl/ForwardServiceImpl.java`、`springboot-backend/src/main/java/com/admin/common/utils/GostUtil.java`、`springboot-backend/src/main/java/com/admin/controller/FlowController.java`、`springboot-backend/src/main/resources/schema.sql`、`springboot-backend/src/main/java/com/admin/config/SQLiteConfig.java`。

## 已核实的架构差异

- Nekopass 一条规则选择 TCP 或 UDP；Flux `GostUtil.AddAndUpdateService` 对每条转发同时下发 TCP、UDP 服务，旧规则依赖这个行为。迁移需给旧规则保留双协议语义，新规则可单选 TCP/UDP；更新、暂停、删除、端口冲突检查都要适配。
- Nekopass 的 `rule_groups`、原子批量事务、`rule_events`、TLS 入口与 SNI 数据结构在 Flux `forward` 表及转发 API 中不存在。Flux 的“分组”目前只是用户/隧道展示，不是持久化规则分组。
- Flux 已有逐条流量累计，见 `FlowController.processFlowData`；没有每规则逐小时事件或“清空展示流量但不返还用户已用额度”的基线数据。两者应单独建模。
- Nekopass 的共享 TLS 入口会在 Agent 侧读取 ClientHello 并按 SNI 选择目标；Flux 当前下发的是独立监听的 GOST TCP/UDP 服务。此能力需要 Agent 协议/配置和服务生命周期变更，不能只加表单字段。
- Flux GOST 的 TCP listener 已有可信来源 CIDR 校验，普通 TCP handler 已能按配置发送 Proxy v1/v2；面板尚未开放这些选项，需先发布支持能力的 Agent 并设置版本门槛。回源 TLS 需核对 GOST 节点连接及证书校验链路，不能把普通转发目标字符串直接当 TLS 配置。
- Flux 目前使用 SQLite、Spring Boot、React、GOST Agent；Nekopass 使用不同的存储、服务和前端架构。以行为对照逐项实现，不直接替换项目模块。

## 任务分解和 TODO

- [x] 阅读页面、接口、规则模型、Agent 下发和数据库迁移方式；记录关键差异。
- [x] 用户确认完整范围、兼容与升级顺序。
- [ ] 阶段一：扩充 SQLite 模型、迁移、规则 DTO/API。补规则分组和独立协议；旧记录迁移为 TCP+UDP，确保升级后运行方式一致。完成本地后端/Agent验证。
  - [x] 兼容迁移及规则列表字段：旧规则协议默认 `tcp_udp`；预建用户规则分组表。
  - [x] 规则分组 API、规则创建/编辑保存分组，以及 Agent 单协议下发联动。
  - [x] 本地 Maven 测试通过；SQLite 新库与旧库迁移检查通过。
  - [x] 编辑规则可在 TCP、UDP 与兼容模式之间切换，复用删除旧服务、创建新服务和失败补偿流程；本地 Maven 测试及前端构建通过。真实 Agent 故障注入仍待做。
  - [x] 修正创建失败分支：Agent 下发失败时总是返回错误，并尝试清理所有此前成功的入口节点；尚未做真实 Agent 故障注入。
  - [x] 创建规则前先核对所有入口节点存在，避免第二个节点缺失时遗留第一节点服务和数据库记录；本地 Maven 测试通过。
  - [x] 创建失败时也清理当前已尝试的节点；若节点清理失败，保留规则 ID 供诊断和强制删除，避免数据库记录消失但 Agent 服务仍在。本地 Maven 测试通过，真实故障注入仍待做。
  - [x] 创建或编辑规则时拒绝无入口节点的隧道，避免只写数据库却没有监听服务；本地 Maven 测试通过。
  - [x] 编辑规则在写数据库或删除旧服务前预先核对目标端口和全部入口节点；本地 Maven 测试通过。下发过程中失败的跨节点回滚仍待实现。
  - [x] 同隧道编辑改为先下发各入口节点、失败时逆序尝试恢复旧服务，全部成功后再保存规则与端口；本地 Maven 测试通过。尚未进行真实 Agent 故障注入；切换隧道和数据库写入失败后的补偿仍待处理。
  - [x] 切换隧道时以原端口记录确定旧入口，逐节点删除旧服务、创建新服务；失败时逆序清理新服务并尝试恢复旧服务，成功后才写规则和端口。本地 Maven 测试通过；真实 Agent 故障注入及数据库写入失败补偿仍待做。
  - [x] 编辑暂停规则时明确要求先恢复，避免变更误将其激活；切换隧道缺少原端口记录时拒绝迁移。本地 Maven 测试通过。
  - [x] Agent 创建一批服务时，后续服务解析或注册失败会关闭本批已解析的监听器；本地 Go 测试确认失败后端口可重新绑定。面板到 Agent 的完整故障注入仍待做。
  - [x] 本地 Go 环回测试确认旧式 TCP/UDP 两个服务可共享监听端口并分别转发；单 TCP handler 也完成实际转发检查。尚未验证面板数据库迁移后对真实 Agent 的完整下发。
  - [ ] 本地端到端验证旧规则升级后的面板到 Agent 下发，以及新规则 TCP/UDP 创建流程。
- [ ] 阶段二：实现批量启停、删除、分组、入口切换及一致的失败回滚；加入逐小时规则流量记录与展示基线，确保清空展示流量不返还用户配额。完成本地验证。
  - [x] 小步交付：规则小时流量汇总、展示基线、统计和清空接口；流量写入同一数据库事务，清空不改用户额度。
  - [x] 本地 Maven 测试、SQLite 聚合与清空语义验证通过。
  - [x] 批量分组与批量清空：校验规则所有权及分组归属，本地 SQL 验证通过。
  - [x] 批量删除安全修正：仅删除当前可见选中规则；普通删除失败时保留规则及错误信息，不自动强制删除；后端删除收到 Agent 失败响应时保留数据库记录。前端构建与后端 Maven 测试通过；真实 Agent 故障注入尚未完成。
  - [x] 批量启停接入现有单条服务：预先校验所选规则归属，跳过目标状态已一致的规则；中途失败时尝试逆序回退已成功的规则，并返回回退失败 ID。前端构建与后端 Maven 测试通过；尚未做 Agent 故障注入，不能视为全局原子操作。
  - [x] 修正 Agent 暂停批次的回退：重建前注销同名已关闭服务，回退失败时返回具体错误；本地 Go 测试模拟第二个服务配置缺失并确认第一个服务恢复监听。
  - [x] 修正 Agent 恢复批次失败后服务注册表丢失：失败时保留原已暂停服务，回退此前已恢复服务；本地 Go 测试模拟第二个服务配置无效并确认两个服务仍可管理。
  - [x] 修正 Agent 批量更新中第二个服务解析失败会留下部分新配置：保存旧配置，失败时恢复旧监听及注册表；本地 Go 测试验证失败恢复，以及正常更新后的新配置和监听端口。面板跨节点编辑流程仍待修复。
  - [x] 批量切换入口：预校验规则状态、归属、目标隧道授权及旧入口端口，逐条迁移；中途失败逆序按原端口恢复此前规则，并报告恢复失败 ID。界面入口已接通；本地 Maven 测试及前端构建通过。真实多 Agent 故障注入仍待做。
  - [ ] 批量启停、删除、入口切换及端到端流量上报验证。
- [ ] 阶段三：实现规则级 Proxy Protocol 接收/发送、可信来源配置、回源 TLS 与相关校验；核查 Flux/GOST 现成能力并复用。完成本地网络行为验证。
  - [x] Agent 的普通 TCP 转发 handler 增加可选 PROXY v1/v2 头发送，默认关闭；头部格式本地 Go 测试通过。面板尚未下发此字段，需先落实 Agent 版本门槛。
  - [x] 使用项目 Go 测试在本地环回端口验证普通 TCP handler：默认模式转发原始数据，v1/v2 模式目标端收到对应 PROXY 头。该检查尚未覆盖面板下发、UDP 或生产节点。
  - [x] TCP Agent 监听器接收 Proxy 头时要求配置可信 CIDR，并在实际连接建立时校验对端来源及头部；本地 Go 测试覆盖可信来源有头、可信来源无头和非可信来源。面板尚未开放接收配置。
  - [x] 规则表单和 API 增加 Proxy 接收自动识别、发送 v1/v2、可信 CIDR；SQLite 旧记录默认关闭。后端校验 CIDR、协议及 Agent 2.0.12-beta 起的版本门槛；本地 Maven 测试核对下发字段位置，前端构建通过。当前安装脚本和 Release 仍为 2.0.11-beta，所以旧节点会收到升级提示；本地构建并发布新 Agent 后才能实际启用。
  - [x] Agent 版本升至 2.0.12-beta；本机 Docker 交叉编译 amd64/arm64 并核对 ELF、amd64 版本和 SHA256。安装脚本优先获取校验过的 Release 资产；Release 缺失时走既有 beta 源码编译后备路径，仍检查 ELF 和版本。新 Release 资产尚未上传。
- [ ] 阶段四：实现共享 TLS 入口、SNI 唯一性与允许范围校验、Agent TLS ClientHello 路由和密文透传；加 Agent 能力版本门槛、同步状态及端口冲突检查。完成本地双规则同端口、未知 SNI 和旧 Agent 测试。
- [ ] 阶段五：实现 Nekopass 风格规则页面、详情、状态、分组、搜索、分页、快捷栏和高级表单；保留 Flux 诊断、强制删除、用户/隧道信息。完成本地前端构建与交互验证。
  - [x] 现有表单先接入 TCP/UDP 选择和规则分组，旧规则显示 TCP+UDP；分组创建与删除入口已接通，前端构建通过。
  - [x] 默认规则表格、搜索、分组筛选、分页、单条操作、复制、重命名、小时统计及批量分组/清空入口；保留卡片视图，前端构建通过。
  - [x] 复核修正：管理员表格显示全部用户规则及各自分组；筛选后的批量删除只提交弹窗确认时的可见选中规则；管理员统计标题标明全站范围。本地前端构建通过，尚未完成浏览器交互验证。
  - [x] 表格新增规则详情，显示已有规则字段并可进入小时统计；本地前端构建通过，尚未完成浏览器交互验证。
  - [x] 分组管理弹窗接通现有分组改名接口；本地前端构建通过。
  - [ ] 完整规则页布局与其余页面操作。
- [ ] 阶段六：新增 Nekopass 完整 JSON/nyanpass 兼容导入导出，保留 Flux 原有 `目标地址|名称|端口` 格式。检查旧备份可导入、新格式导入失败时整批不落库。
  - [x] 新增 Flux JSON 和 nyanpass JSONL 导出、Flux/Nekopass 普通规则及 nyanpass JSON 导入，保留旧文本导入导出。新格式统一导入到所选 Flux 隧道，保留支持的协议、策略、端口、Flux 分组和 Proxy 设置；暂停规则、TLS/SNI 和最少连接策略明确拒绝，待后续能力完成后扩充。
  - [x] 新增后端整批预校验：参数、配额、权限、分组、端口和 Agent 版本；逐条创建失败时逆序清理此前规则，清理失败明确报告并保留记录供诊断。此为补偿流程，尚不满足跨 Agent 完全原子导入；数据库事务、并发端口分配及真实 Agent 故障验证仍待完善。
  - [x] 前端构建、后端 Maven 测试和本地解析检查；解析检查使用项目 TypeScript 编译器转译后运行，覆盖 JSON 往返、nyanpass 多行、IPv6 和无效协议/端口/TLS/Proxy 拒绝。
- [ ] 阶段七：完整回归、迁移前后数据检查、升级顺序和回退说明；本地验证通过后分阶段提交并推送 Flux `beta`。

## 兼容与发布原则

旧规则保留原 TCP+UDP、端口、目标、排序、累计流量、启停状态、隧道与用户归属。新增字段给旧记录安全默认值；迁移沿用 `SQLiteConfig.ensureSchema` 机制，新增表放进现有 `schema.sql`。新能力在旧 Agent 上明确显示需要升级，不默默下发无效配置。面板和 Agent 分阶段升级，部署前保留 SQLite 备份；不在本轮直接改生产服务。

## 验证门槛

使用项目现有 Maven、npm、Go 构建与测试命令；无需 CI。每阶段验证数据库迁移、权限、配置下发、旧规则继续转发和对应新功能，再提交。最终在本机独立测试环境验证 TCP/UDP、批量操作、分组、流量统计、Proxy Protocol、TLS/SNI、导入导出、诊断及移动端。验证失败立即停止提交；连续三次失败暂停并重新评估。

## 需确认的范围

默认理解为：Nekopass 当前规则页面上可见的底层能力都纳入，分阶段交付；Flux 现有能力保留。Nekopass 的套餐/节点授权体系不会整体移植，规则能力与 Flux 当前的用户、隧道和配额体系对接。具体实现过程中发现规则行为依赖其他 Nekopass 子系统时，先记录依赖并调整阶段边界。

## 2026-10-15 可更新版本

本次交付现有规则表格、分组、协议、批量操作、统计、Proxy 设置和普通 JSON/nyanpass 导入导出。完整范围继续按上面的 TODO 推进，不能视为 Nekopass 全部能力已复刻。

更新入口仍为 `./panel_install.sh` 选项 2。脚本版本 `2026-10-15.1`：按 Compose 标签恢复现有端口及密钥，补齐空环境变量；新配置先下载至临时文件并校验，构建成功后再替换服务，不再构建前执行 down；更新结束检查后端及网页 HTTP。数据卷继续使用 sqlite_data/backend_logs。原 Compose 文件保留为 docker-compose.yml.previous，此文件不构成数据库回退备份。

本地验证方式：`bash -n panel_install.sh`；沿用项目 `mvn -q test`、`npm run build` 和既有 Go 测试。脚本额外在临时目录载入函数，以 shell 函数替换 curl/compose/docker，分别模拟成功、HTTP 下载失败、配置无效、构建失败；断言失败时原配置不变且未调用容器接管或启动，成功时构建先于接管。模拟当前 Compose 容器与空 .env，核对恢复原端口和 JWT。该模拟不代表生产 Docker 更新或公网连通性验证。

使用限制：TLS 回源和共享 TLS/SNI 尚未实现；暂停规则与 TLS/SNI JSON 导入会拒绝；跨节点失败采用尽力补偿，尚未完成完整端到端故障验证。Proxy 功能要求 Agent 2.0.12-beta，新 Agent 已本地编译但 Release 资产尚未上传，安装脚本目前可能需要源码编译后备路径。普通规则和页面更新不要求先升级 Agent。

## 2026-10-15 更新耗时优化

生产日志显示前端构建约 220 秒、nginx 下载约 64 秒，npm 安装已命中构建层缓存。将 Maven/npm 的 BuildKit 缓存挂载修正到显式指定的 /var/cache/flux-maven 和 /var/cache/flux-npm，以便源码或依赖变化后复用下载缓存；首次使用新缓存仍需要填充。更新脚本 2026-10-15.2 取消 build --pull，使用本机已有基础镜像，源码仍通过 beta Git context 解析；基础镜像升级需另行维护。此改动不承诺固定更新时间，也不会省去前端源码变化后的编译。

验证使用既有 Dockerfile：本地 docker build 分别构建后端与前端；bash -n panel_install.sh 及 git diff --check。

## 继续实施：回源 TLS

沿用已确认阶段三，先完成规则级回源 TLS：开关、校验名称、自定义根证书、ALPN；默认开启证书校验，不提供跳过校验选项。Agent 在路由连接建立后发起 TLS 握手，再在加密流中发送可选 Proxy 头及业务数据；这也覆盖经过现有隧道的连接。规则限定单 TCP，旧规则默认关闭。同步保存、编辑、批量入口切换、复制及 JSON 导入导出；nyanpass 不支持时明确拒绝导出。增加 Agent 能力版本门槛，并用本地可信证书、错误名称、未受信任证书网络测试验证。共享 TLS/SNI 仍按独立阶段推进。

- [x] Agent TLS 握手及证书验证测试；可信证书、错误名称、不可信证书及 TLS 内 Proxy 头均通过本地环回验证。
- [x] 面板迁移、配置下发、表单及格式兼容；复制/批量切换保留字段，JSON 可往返；nyanpass 拒绝导出含回源 TLS 的规则。
- [x] 本地 Maven 测试、前端构建、Go 测试通过；amd64/arm64 Agent 2.0.13-beta 本地交叉编译并核对 ELF/SHA256，amd64 版本执行验证通过。安装脚本已更新目标版本；Release 上传尚未完成，当前仍可能走源码编译后备路径。

验证命令：后端 `mvn -q test`；前端 `NODE_OPTIONS=--max-old-space-size=1024 npm run build`；Go x 模块 `go test -mod=mod -p=2 ./handler/forward/local -count=1`，使用 golang:1.23 在本机 Docker 内运行。首次前端构建与其他验证并发时被系统终止；减少并发、限制 Node 堆后通过。旧表增加四列的 SQLite 默认值检查与 TypeScript 转译后的 JSON 往返、UDP+TLS 拒绝检查通过。面板到真实 Agent 的完整下发与隧道模式网络验证尚未完成。

Agent 编译产物：/tmp/flux-agent-2.0.13-beta/gost-amd64、gost-arm64。SHA256 分别为 b746232f5e17ca283902f561ffa65dbe94ef05e734f78903d508c1d60d499317、c9a793695954ae4afde158561ecc6cd33431bcfa6f2d838d6ae7c2512f7be669。
