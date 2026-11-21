<div align="center">

# Build MyVpn

**自分の VPS をワンタップで専用 VPN に。**

[English](../README.md) · [简体中文](./README.zh-CN.md) · [Русский](./README.ru.md) · [فارسی](./README.fa.md) · [العربية](./README.ar.md) · [Español](./README.es.md) · [Français](./README.fr.md) · **日本語**

[Android APK をダウンロード](https://github.com/Gouryella811/build-myvpn/releases/latest) · [公式サイト](https://build-myvpn.pages.dev) · [プライバシーポリシー](https://build-myvpn.pages.dev/privacy.html)

</div>

---

## Build MyVpn とは？

**Build MyVpn** は、ご自身で契約した VPS を使ってプライベート VPN を手軽に構築・接続できる、無料・オープンソースの Android クライアントです。

Linux のコマンド操作やサーバー設定、証明書管理は必要ありません。VPS の IP アドレスと SSH ログイン情報を入力するだけで、アプリが自動的にサーバーをセットアップし、完了直後に SSH パスワードをメモリから消去します。あとはワンタップで接続できます。

## どんな人に向いていますか？

- 自分の VPS を持っているが、Linux 操作やプロキシ構築を調べずに専用 VPN を使いたい方。
- アカウント登録や共有サーバー、通信ログのないクリーンなクライアントを求める方。
- 1 台または複数の個人 VPS ノードを Android スマホから簡単に構築・管理したい方。

## 主な機能

- **ワンタップ自動構築** — ドメインや手動証明書なしで、VPS 上に `VLESS + REALITY`（TCP 443）と TCP BBR 最適化を自動セットアップ。必要に応じて `Hysteria2`（UDP 443）にも対応。
- **プライバシー重視・完全オープンソース** — アカウント不要、仲介サーバーなし、クローズドな広告 SDK なし。SSH パスワードは構築時のみ使用し、保存せず即時消去されます。
- **毎日使いやすいシンプル設計** — ワンタップ接続、リアルタイムの上下速度表示、遅延テスト、サーバーごとの稼働状態・通信量集計、スマートルーティング。
- **17 言語に対応** — 日本語・英語・中国語をはじめ、RTL レイアウトを含む 17 言語を内蔵。

## クイックスタート

1. **[GitHub Releases](https://github.com/Gouryella811/build-myvpn/releases/latest)** から最新の Android APK をダウンロードしてインストールします。
2. **Build MyVpn** を開き、「**サーバー → VPS を追加**」から IP/ドメインと SSH 情報を入力してデプロイします。
3. 「**接続**」画面に戻り、ボタンをタップすると接続開始です。

## ライセンス

**GNU General Public License v3.0 or later (GPLv3+)** の下で公開されています。
