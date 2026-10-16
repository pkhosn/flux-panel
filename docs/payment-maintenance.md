# 支付与规则优化

## 本轮计划与进度

- [x] 阅读现有刷新、分组、订单续付和回调，以及本机 nekopass 支付文档和源码。
- [x] 手动刷新全部成功后显示绿色成功 toast；部分失败显示错误。
- [x] 分组列表接口仅返回当前用户分组；管理员不再显示其他用户分组。
- [x] 默认支付有效期 15 分钟，30 秒定时关闭，并在查询和续付前即时关闭；禁止非所有者续付及关闭订单续付。
- [x] 保存支付链接时校验订单仍待付且未到期，避免覆盖定时关单或已支付状态。
- [x] 前端每 30 秒同步订单状态，续付失败后刷新列表。
- [x] 本地测试、构建与浏览器回归。

用户未指定期限前采用 15 分钟。保留现有已验签付款回调处理：真实到账仍结算，避免收款后不发额度。停止续付和清除本地链接不能撤销第三方已打开的收银台；网关关单需按实际使用通道单独集成。

## nekopass 对照建议（本轮不移植）

依据本机 `nekopass/docs/guide/payments.md`、`docs/guide/orders.md` 与 `internal/control/payments.go`：

1. 支付方式独立管理：多商户、启停和排序；Flux 当前是全局 provider 搭配支付类型。
2. 订单保存支付配置快照：更换商户密钥后旧订单回调仍按原配置验证；Flux 当前读取全局配置，建议优先改造。
3. 创建请求幂等及待付订单数量限制：减少重复下单与重复网关请求。
4. 回调核验订单金额、币种、流水号，并用交易唯一约束防重复结算；适合优先引入，需要覆盖各支付通道的实际响应。
5. 订单详情和历史快照：创建时套餐额度、有效期及平台流水，方便对账。

不建议立即引入余额充值再购买模式，会改变 Flux 现有直接购买流程。建议下一轮先做配置快照、金额核验与结算幂等，再做支付方式管理 UI。

## 验证记录

前端：`NODE_OPTIONS=--max-old-space-size=768 npm --prefix vite-frontend run build`；首次与 Maven 并发时被系统终止，串行重跑通过。

后端在本机 Maven Docker 容器执行既有 `mvn -q test`，环境 `LOG_DIR=/tmp/flux-test-logs JWT_SECRET=local-test-secret DB_PATH=/tmp/flux-payment-test.db`，挂载源码至 /app 和现有 flux-maven-cache 至 Maven 缓存目录；全量通过。随后补充续付归属边界，发现 Maven 增量编译未重新编译新增方法后，执行 `mvn -q clean -Dtest=OrderExpiryTest test`，3 项测试通过：SQLite 超时关闭、管理员分组隔离、关闭／过期／非本人续付拒绝及有效续付允许。

浏览器：临时 `/tmp/flux-browser-check/refresh-groups.cjs` 使用模拟 API 验证刷新成功 toast 和其他用户分组不显示。`git diff --check` 通过。未向实际网关发起交易，第三方远端关单未集成。
