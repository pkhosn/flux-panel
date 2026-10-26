# 隧道入口变更修复（2026-10-26）

用户已要求一并处理历史端口残留、增减入口慢和超时。

证据：生产 13:03–13:06 保存返回规则 398/397 的 22166/26566 bind address already in use；WebSocket 日志显示每条规则在所有入口逐个删除和新增。当前端口转发拓扑每次全量重建，HTTP 客户端等待 30 秒。

实施与验证清单：

- [x] 端口转发隧道仅变更新增/删除入口，保留其他入口服务、原端口记录。
- [x] 复用 Agent 2.0.16 的 ReconcileForwards，每个变更节点一次同步，以数据库全部有效规则重建并移除孤立转发服务；保留非面板服务。
- [x] 隧道更新接入现有持久化恢复和操作锁，失败回滚后恢复旧配置。
- [x] 本地测试新增/删除入口、固定端口、无关节点不下发、失败返回及持久化恢复，后端构建。
- [x] 推送 beta，交付既有脚本更新方式。

边界：链式隧道仍沿用原拓扑重建流程；本次生产为 type=1。占用端口如来自其他进程，返回明确节点错误，不杀进程、不更换端口。不通过生产修改验证代码。恢复命令会重建变更节点上其他面板规则，可能短暂断连；保留入口不重建。

诊断优化（用户追加要求）：规则和隧道诊断统一以最多 8 个并行任务执行独立探测，保持原展示顺序；每项 TCP 探测从 4×5 秒改为 2×1.5 秒，避免单任务探测超过 10 秒 WebSocket 回复等待。失败和离线仍逐项返回。通过双任务门闩测试断言真实规则诊断并发、顺序和离线结果。

本地验证：使用 `docs/tunnel-service-rebuild.md` 中 Docker Maven 命令执行 `clean package`，26 项测试、0 失败、0 错误。新增入口增量测试覆盖增加/移除、保留端口及未变节点不下发，模拟端口占用返回失败。恢复测试改为 TunnelUpdateDto 入口操作，验证数据库回滚、恢复持久化和重启重试。Agent 现有 `TestReconcileRemovesOrphansAndRestoresPausedState` 本地通过；首次因仓库 go.sum 缺依赖失败，复验用临时 `-modfile` 和 `-mod=mod`，未修改 Agent 依赖文件。

Agent 复验：本机 Go 容器挂载 go-gost/x 为只读，复制 go.mod/go.sum 到 /tmp/check.mod、/tmp/check.sum 后运行 `go test -mod=mod -modfile=/tmp/check.mod ./socket -run TestReconcileRemovesOrphansAndRestoresPausedState -count=1`。全部验证均在本地隔离环境；生产仅查看日志和只读数据库，没有以生产操作代替测试。
