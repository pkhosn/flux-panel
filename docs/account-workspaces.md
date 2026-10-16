# 管理员个人使用与后台分离

依据 data.sql，初始管理员为用户 ID 1。用户已确认同一账号切换前台／后台，无需新增账号或修改 JWT 权限。

- [x] 增加持久化页面模式及头像菜单切换；普通用户始终前台。
- [x] 桌面和移动导航按页面模式显示，前台可购买及查看个人额度。
- [x] 默认规则列表只查自己；管理员从用户管理显式查看指定用户规则，服务端校验权限。
- [x] 统计按当前规则归属查询；管理他人规则时禁止误建自己的规则。
- [x] 本地构建、后端权限边界测试与浏览器两种模式验证；通过后推送。

## 验证

- 现有前端构建：`NODE_OPTIONS=--max-old-space-size=768 npm --prefix vite-frontend run build`。
- 本机 Maven Docker 执行既有 `mvn -q clean test`；环境 LOG_DIR=/tmp/flux-test-logs、JWT_SECRET=local-test-secret、DB_PATH=/tmp/flux-account-test.db。新增 ForwardAccountScopeTest：默认本人列表、管理员指定用户、普通用户越权拒绝、统计归属；全量通过。首次测试 JWT 缺少 name 字段，补齐测试数据后重跑通过。
- `/tmp/flux-browser-check/workspace.cjs`：模拟管理员切换、个人购买／订单 API、刷新保留模式、管理路由重定向、指定用户查询、本人查询恢复及 390px 窄屏；通过。
- `git diff --check` 通过。

已有账户不迁移、不改密码或角色；初始化 data.sql 已创建 ID 1 管理员。页面模式仅为显示偏好，不降低或赋予 API 权限。查看他人规则时禁止新增／导入和修改分组，其他已有管理操作保留。
