<div align="center">

# Build MyVpn

**Turn your own VPS into a private VPN in one tap.**

**English** · [简体中文](./docs/README.zh-CN.md) · [Русский](./docs/README.ru.md) · [فارسی](./docs/README.fa.md) · [العربية](./docs/README.ar.md) · [Español](./docs/README.es.md) · [Français](./docs/README.fr.md) · [日本語](./docs/README.ja.md)

[Download Android APK](https://github.com/Gouryella811/build-myvpn/releases/latest) · [Official Website](https://build-myvpn.pages.dev) · [Privacy Policy](https://build-myvpn.pages.dev/privacy.html)

</div>

---

## What is Build MyVpn?

**Build MyVpn** is a free, open-source Android client for setting up and using a private VPN on your own VPS.

Instead of manually logging into a Linux server, editing configuration files, or managing certificates, you simply enter your VPS IP address and SSH credentials. Build MyVpn automatically deploys the server for you, removes your SSH password from memory immediately afterward, and lets you connect in one tap.

## Who is it for?

- People who rent their own VPS and want a private, reliable VPN without learning Linux or proxy server setup.
- Everyday users who want a clean, account-free client with no shared public servers or traffic logs.
- Anyone looking for a fast, open-source tool to deploy and manage one or more personal VPS nodes from Android.

## Key Features

- **Zero-Config Server Deployment** — Automatically installs and configures `VLESS + REALITY` (TCP 443) and adaptive TCP BBR acceleration on your VPS. No domain name or manual SSL certificate required. Optional `Hysteria2` (UDP 443) is available when needed.
- **Private & Open Source** — No registration, no centralized servers, no closed-source ad SDKs. SSH passwords are used once during deployment and immediately erased — never saved to disk.
- **Simple Everyday Controls** — One-tap connect, live upload/download speed, latency check, server online status, per-server traffic counter, and automatic regional split-routing.
- **17 Languages Built In** — Ready to use across 17 languages, including full right-to-left (RTL) layout support.

## Quick Start

1. Download and install the latest Android APK from **[GitHub Releases](https://github.com/Gouryella811/build-myvpn/releases/latest)**.
2. Open **Build MyVpn**, go to **Servers → Add VPS**, enter your VPS IP/domain and SSH login, and tap **Deploy**.
3. Switch to **Connect** and tap the power button to start.

## License

Licensed under the **GNU General Public License v3.0 or later (GPLv3+)**.
