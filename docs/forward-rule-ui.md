# 规则页面布局

参考用户提供的 `img/photo_2026-10-15_18-28-21 (2).jpg`，沿用 React、HeroUI 和现有转发接口。

- [x] 紧凑标题工具行、统计和分组行、批量操作行、表格及底部分页。
- [x] 搜索按钮展开搜索输入；可识别的操作使用 SVG 图标，并提供 title 和 aria-label。
- [x] 协议并入规则名单元格，保留所有九项行操作。
- [x] 选中规则后显示批量移动分组与目标隧道控件。
- [x] 桌面和窄屏检查；表格区域横向滚动，页面不溢出。

不虚构截图中的套餐额度和到期数据，当前统计沿用现有页面的规则数及流量来源。

本地验证：`NODE_OPTIONS=--max-old-space-size=768 npm --prefix vite-frontend run build`、`git diff --check`。临时 Playwright 检查 `/tmp/flux-browser-check/rules-layout.cjs` 使用模拟规则数据验证行操作图标与标签、搜索、批量选中、新增弹窗及 390px 页面无横向溢出；截图保存在同目录 rules-desktop.png 和 rules-mobile.png。
