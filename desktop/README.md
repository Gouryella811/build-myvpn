# Build MyVpn — Desktop Client (Windows & macOS)

Built with Tauri v2 (Rust) and official `sing-box` v1.12.12.

## Requirements

- Rust (stable) + `aarch64-apple-darwin` & `x86_64-apple-darwin` targets for macOS Universal builds
- Node.js + `@tauri-apps/cli` v2
- Official `sing-box` v1.12.12 binary placed in `desktop/src-tauri/binaries/` with the Tauri target triple suffix

## Build

```bash
cd desktop/src-tauri
npx @tauri-apps/cli@2 build --target universal-apple-darwin --bundles dmg   # macOS Universal
npx @tauri-apps/cli@2 build --config tauri.windows.conf.json --bundles nsis # Windows x64
```
