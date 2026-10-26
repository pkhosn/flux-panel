# 隧道新增入口服务重名修复

2026-10-26：隧道拓扑更新会先删除旧规则服务，再创建新服务。Agent 的 `deleteServices` 先验证整批服务存在，任一不存在则整批不执行；原后端把包含 `not found` 的回复统一转为成功，可能留下实际存在的 TCP/UDP 服务，随后创建时报 `already exists`。

修复：后端逐个删除服务，仅将对应服务的精确 `service <name> not found` 回复视为可忽略。隧道重建清理旧、新入口节点上该规则的 TCP/UDP 服务，覆盖此前失败下发的残留；节点离线等真实错误仍返回失败。不修改 Agent 协议，也不把重名创建直接当成功。

本地验证（仓库根目录）：

```bash
docker run --rm \
  -v "$PWD/springboot-backend:/app" -v flux-maven-cache:/maven-cache \
  -w /app -e MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m' \
  -e LOG_DIR=/tmp/flux-test-logs -e JWT_SECRET=local-test-secret -e DB_PATH=/tmp/flux-test.db \
  maven:3.9-eclipse-temurin-21 mvn -q -Dmaven.repo.local=/maven-cache clean package
git diff --check
```

新增 `GostUtilDeleteServiceTest` 模拟 Agent 整批验证行为，验证只有 TCP 或只有 UDP 存在时均清理完成、可重新创建，以及 `node not found` 不被误判为成功。使用隔离测试数据库，不连接生产环境。生产是否还有其他残留需根据实际重试结果判断。

## 2026-10-26：编辑入口配置的数据库锁冲突

`updateTunnel` 原先在事务中先查询隧道和节点，最后才 `UPDATE tunnel`。本地文件 SQLite WAL 双连接测试中，在节点准备期间另一连接提交模拟心跳写入，原流程稳定出现 `SQLITE_BUSY`：读快照过期后无法升级写事务，单纯延长 busy_timeout 无法解决。

在首次查询之前对指定隧道执行 `UPDATE tunnel SET id=id WHERE id=?`，在现有 Spring 事务中先获取写锁，使后续查询与拓扑更新使用可写快照。Hikari `connection-init-sql` 为每个连接设置 30 秒 busy_timeout，替代启动时仅设置单一连接的 5 秒等待。持锁超过等待上限仍会失败，不无限重试节点下发。

本地 `TunnelUpdateConcurrencyTest` 实际调用新增入口的 `updateTunnel` 流程，以文件数据库、Hikari 多连接和独立线程模拟心跳，验证保存与心跳最终均提交；删除首个写锁语句的对照运行复现 SQLITE_BUSY，恢复后通过。第一次测试因测试数据缺少必填 flow 失败，补全后通过。复验沿用上面的 `mvn clean package`，或指定 `-Dtest=TunnelUpdateConcurrencyTest test`。YAML 沿用 CRLF，差异检查使用 `git -c core.whitespace=cr-at-eol diff --check`。
