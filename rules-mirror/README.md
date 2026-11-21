# Build MyVpn 分流规则镜像说明

每日定时拉取、校验并发布 `.srs` 分流规则集（CN / IR / RU）的 GitHub Actions 工作流位于仓库根目录的 `.github/workflows/build-rules.yml`。

推送到 GitHub 后，既可等待每日 03:30 UTC 自动执行，也可在仓库 **Actions → Build and Mirror Build MyVpn Rule Sets (.srs)** 中手动点击 **Run workflow** 立即发布 `rules-latest` Release。
