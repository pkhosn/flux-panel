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
