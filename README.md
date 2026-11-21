# Build MyVpn

> 面向新手用户的免费开源 Android 自建 VPN 客户端（GPLv3+）——只需输入自备 VPS 的地址和 SSH 凭据，即可自动部署并一键连接。

## 项目结构

```text
build-myvpn/
├── docs/                # 产品与技术开发方案（V1.1 定稿）
├── deploy-scripts/      # VPS 幂等一键部署脚本（JSON Lines 协议 + 按系统自动 BBR）及测试
├── rules-mirror/        # GitHub Actions 每日分流规则 (.srs) 镜像与校验流水线
└── android/             # 全新 Jetpack Compose + libbox.aar Android 客户端
```

## 核心特性（V1.1 定稿）

- **极简双页交互**：仅保留「连接」与「服务器」两个主页面 +「设置」页，无注册登录、无公共节点、无内置闭源广告 SDK。
- **核心协议策略**：
  - 默认唯一启用 **`VLESS + REALITY`（TCP 443）**：免域名、免手动证书、自动优选 SNI、抗封锁稳定。
  - 按需可选 **`Hysteria2`（UDP 443 同端口共存）**：仅在用户明确确认高封锁风险提醒后启用；纯 IPv4 强制采用 `certificate_sha256` 证书固定，严禁 `insecure: true`；支持一键切回 REALITY 或彻底关闭 UDP 监听。
- **按系统自适应 BBR 加速**：根据 VPS 发行版、内核版本与虚拟化类型自动写入 `/etc/sysctl.d/99-build-myvpn-bbr.conf`，容器/旧内核安全跳过，绝不强换内核重启机器。
- **安全底线**：SSH 主机指纹校验（TOFU）、SSH 密码默认“用后即焚”不落盘、分流规则本地缓存 + Fail-Closed 防真实 IP 泄漏。
- **国际化**：原生支持 RTL，V1.0 首发精校 4 门核心语言（English、简体中文、Русский、فارسی）。

## Phase 1 验证指南

### 1. 运行部署脚本自动化测试（无需真实 VPS 或 Docker）
```bash
bash deploy-scripts/tests/test_install_script.sh
```

### 2. 在测试 VPS 上试跑（示例）
```bash
# 默认模式：仅部署 VLESS + REALITY (TCP 443) + 自动 BBR
sudo bash deploy-scripts/install_singbox.sh --host 203.0.113.10 --protocol reality

# 双协议模式（需用户确认风险后启用）：TCP 443 REALITY + UDP 443 Hysteria2
sudo bash deploy-scripts/install_singbox.sh --host 203.0.113.10 --protocol dual

# 关闭 Hysteria2 UDP 监听，仅保留 REALITY
sudo bash deploy-scripts/install_singbox.sh --disable-hy2

# 彻底卸载并清理 BBR/systemd 配置
sudo bash deploy-scripts/install_singbox.sh --uninstall
```

### 3. 构建 Android 客户端
需本地准备 **JDK 17**、**Android SDK (API 34)** 及编译好的 **`android/app/libs/libbox.aar`**（参考 `android/README.md`）。

## License

GNU General Public License v3.0 or later (GPLv3+).
