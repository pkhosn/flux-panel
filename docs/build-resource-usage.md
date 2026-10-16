# 更新构建资源占用

2026-10-16：生产更新曾在 Vite `rendering chunks` 阶段等待约 41 分钟，最终报告 `failed to execute bake: signal: killed`。此日志无法单独证明 OOM；混合导入 `site.ts` 的提示是警告。

## 调整

- `panel_install.sh` 关闭 Compose Bake，依次构建 backend、frontend，减少并行资源占用。
- 前端 Node 堆上限 768MB，后端 Maven 堆上限 512MB、Metaspace 上限 256MB。这些不是整个构建进程的内存上限。
- Vite 关闭仅用于日志展示的 gzip 大小计算，生成的网页功能不变。
- 更新仍先完成两项构建，再替换服务；构建失败时保留当前 Compose 文件和运行中的服务。

## 本地复验

在仓库根目录执行，依次运行，不要并行：

```bash
bash -n panel_install.sh
git diff --check
NODE_OPTIONS=--max-old-space-size=768 npm --prefix vite-frontend run build
docker run --rm \
  -v "$PWD/springboot-backend:/app" \
  -v flux-maven-cache:/root/.m2 \
  -w /app \
  -e MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m' \
  maven:3.9-eclipse-temurin-21 mvn -q -DskipTests package
```

更新路径另经本地 Bash 模拟验证：分别令后端、前端构建失败，断言函数返回失败、当前 Compose 内容未变、临时配置被删除，且没有执行停止或替换容器的操作；验证构建顺序为 backend → frontend。

若生产服务器再次出现 killed，需要在该服务器执行 `free -h`、`docker stats --no-stream` 和 `journalctl -k --since '1 hour ago'` 判断资源情况；本机验证不能保证所有服务器都有足够资源。
