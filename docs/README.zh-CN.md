<div align="center">

<img src="../assets/brand/icon-rounded-1024.png" width="84" height="84" alt="Build MyVpn" />

# Build MyVpn

**一键将你的自有 VPS 变成专属私密 VPN。**

[English](../README.md) · **简体中文** · [Русский](./README.ru.md) · [فارسی](./README.fa.md) · [العربية](./README.ar.md) · [Español](./README.es.md) · [Français](./README.fr.md) · [日本語](./README.ja.md)

[下载 Android APK](https://github.com/Gouryella811/build-myvpn/releases/latest) · [官方网站](https://build-myvpn.pages.dev) · [隐私政策](https://build-myvpn.pages.dev/privacy.html)

</div>

---

## Build MyVpn 是什么？

**Build MyVpn** 是一款免费开源的 Android 自建 VPN 客户端，帮助你用自己的 VPS 快速搭建并连接专属私密网络。

无需学习 Linux 命令行，无需手动配置服务端和证书。只需在手机上输入你的 VPS 地址与 SSH 登录信息，App 会自动完成服务端部署，并在部署结束后立即清除 SSH 密码，随后一键即可连接。

## 适合谁使用？

- 自己租了 VPS、想搭建专属 VPN，但不想折腾 Linux 命令和复杂代理配置的新手与普通用户。
- 不想使用公共共享节点、不希望注册账号、重视隐私与连接稳定性的用户。
- 需要直接在手机上快速部署、切换与管理多台个人 VPS 节点的用户。

## 核心功能

- **一键自动部署** — 自动在你的 VPS 上安装配置 `VLESS + REALITY`（TCP 443）并按系统环境自动开启 TCP BBR 加速，免域名、免手动证书；亦支持按需开启 `Hysteria2`（UDP 443）。
- **隐私优先，完全开源** — 无账号注册、无公共中转服务器、无闭源追踪 SDK；SSH 密码“用后即焚”，永不保存到本地或上传第三方。
- **简单直观的日常体验** — 一键连接、实时上下行网速显示、延迟测速、节点在线状态与地区标识、单节点已用流量统计及智能分流。
- **内置 17 种语言** — 支持简体中文、English 等 17 种界面语言，原生适配 RTL 排版。

## 快速开始

1. 前往 **[GitHub Releases](https://github.com/Gouryella811/build-myvpn/releases/latest)** 下载并安装最新版 Android APK。
2. 打开 **Build MyVpn**，进入「**服务器 → 添加 VPS**」，填写你的 VPS IP/域名与 SSH 登录信息并点击部署。
3. 回到「**连接**」页面，点击按钮即可一键上网。

## 开源协议

本项目基于 **GNU General Public License v3.0 or later (GPLv3+)** 协议开源。
