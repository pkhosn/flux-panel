# 顶部导航栏

使用现有 React、HeroUI、Tailwind/CSS、React Router 实现。桌面 AdminLayout 和 H5 布局共用 PanelHeader；桌面侧栏宽度与 240px 品牌区对齐。H5 延续底部导航，没有侧栏折叠按钮。

网站配置提供应用名称、品牌 Logo 地址、公告标题和公告内容。Logo 留空或加载失败使用现有 Logo。公告通过现有配置 API 读取，以纯文本显示并保留换行；未配置显示“暂无公告”，网络失败显示重试说明。头像沿用已有个人中心、修改密码和退出功能；D/AVIP 按视觉要求固定显示，不作为账户权限判断。

主题按钮沿用 saveTheme/applyTheme/useTheme。语言菜单保存 panel_locale，切换导航栏文本、HTML lang 和 React Aria 组件语言；业务页面正文维持现有中文，未实现全站翻译。

本地验证：vite-frontend 中 `NODE_OPTIONS=--max-old-space-size=768 npm run build`；仓库根目录 `git diff --check`。临时 Playwright 浏览器检查验证 56px 高、240px 品牌、32px 工具、42px 头像、无阴影，以及侧栏折叠、公告纯文本、多语言、主题、修改密码。1280/768/390/320px 检查通过，无按钮越界或浏览器运行时错误；API 响应用浏览器路由拦截模拟，未修改生产数据。检查文件 /tmp/flux-browser-check/header.cjs、截图 header-desktop.png 和 header-{768,390,320}.png。首次浏览器检查使用已过期的本地测试 JWT 跳转登录，更新临时 JWT 后通过。
