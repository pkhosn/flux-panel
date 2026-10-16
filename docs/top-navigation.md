# 顶部导航栏

使用现有 React、HeroUI、Tailwind/CSS、React Router。桌面和 H5 共用 PanelHeader。网站配置提供应用名称、Logo 和公告；铃铛通过现有配置 API 打开纯文本公告。

## 本次修正

- [x] 桌面收起后品牌区同步缩窄，隐藏折叠按钮；悬停临时展开，离开收起，点击展开时的按钮固定展开。
- [x] 头像取用户名首个字符，拉丁字母大写；仅启用且套餐到期时间晚于当前时间时显示 AVIP 边框，每分钟及窗口重新激活时刷新。
- [x] 深色导航背景、品牌区、工具按钮和分割线跟随现有主题。
- [x] 静态界面文字使用本地中英词典；切换语言重新挂载页面。用户填写内容和服务端消息不翻译；部分动态拼接提示仍沿用中文。
- [x] 所有 Select 共用组件：禁止弹层挂载时的失焦和自动滚动关闭，保留选择、Esc、点击外部关闭。

## 本地验证

仓库根目录执行 `NODE_OPTIONS=--max-old-space-size=768 npm --prefix vite-frontend run build` 和 `git diff --check`。

浏览器验证使用本机构建产物和模拟 API，不改生产数据：在转发页面添加规则，展开协议菜单，等待超过一秒后选择 UDP；重新展开，分别按 Esc 和点击弹窗空白处，菜单应关闭。检查侧栏收起、悬停、离开和固定展开；切换主题、语言；用无有效套餐及有效套餐的用户分别检查头像边框；320px 视口检查导航按钮不溢出。

临时自动化检查：`/tmp/flux-browser-check/select-regression.cjs`、`/tmp/flux-browser-check/fixes.cjs`。组件滚动关闭行为依据现有 HeroUI popover 的 shouldCloseOnScroll 选项修正。

## 下拉动画闪烁修正

Select 弹层和顶部 Dropdown 关闭缩放淡入动画。逐帧采样曾记录到菜单透明度从 0.997891 重置为 0，随后恢复为 1，说明仅验证一秒后菜单存在会漏检闪烁。

本地验证沿用上述构建命令，并运行临时浏览器检查 `/tmp/flux-browser-check/no-flicker.cjs`：连续三次展开弹窗协议选择框，每次在菜单挂载后逐帧检查 1.3 秒，要求菜单始终存在且所有祖先透明度至少为 0.99；同样检查语言和用户菜单，并验证选项可选择。关闭操作由 `select-regression.cjs` 验证。

## 侧栏折叠修正

侧栏增加 shrink-0，防止主内容挤压侧栏；品牌区和侧栏统一为 150ms 宽度过渡。两区共用悬停处理，离开延迟 120ms，避免跨区移动立即收起。文字禁止换行，收起时隐藏版权信息，紧凑图标按钮取消多余横向内边距。

本次本地验证：生产构建及 git diff --check 通过；临时 `/tmp/flux-browser-check/sidebar-check.cjs` 验证收起、悬停、离开、固定展开；`dashboard-clean.cjs` 验证两种身份不再显示入口、背景色一致及窄屏；`rules-layout.cjs` 验证字号调整后的规则操作、搜索、批量选择、新增及窄屏。
