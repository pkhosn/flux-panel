# Flux 维护约定

前端发布默认采用本机编译后上传 GitHub Release 的方式，生产服务器只下载并校验静态包，不运行 npm 构建。具体流程和验证见 [前端发布文档](docs/frontend-release.md)。

每次涉及前端交付，必须完成本地构建与必要交互验证，发布新的不可变资源版本，更新 `vite-frontend/Dockerfile.prebuilt` 的版本和 SHA256，并确认发布包匿名可下载后才推送 beta。只推源码不算完成前端发布。不得启用 CI 或远程构建替代本地验证。

保持用户使用 `./panel_install.sh` 选项 2 的更新入口，构建或下载失败时不得停止当前运行服务。
