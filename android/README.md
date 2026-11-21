# Build MyVpn Android Client (Jetpack Compose + libbox)

## 本地环境要求
1. **JDK 17**（例如 Eclipse Temurin 17 / Android Studio 自带 JBR）。
2. **Android SDK**（已检测到本机存在 `/Users/ayanga/Library/Android/sdk`，包含 `android-34`）。
3. **`libbox.aar`**（`sing-box 1.12.12` 官方 Android Go 绑定库）：
   - 方式 A（推荐）：在 GitHub Actions 中使用 Go 1.23+ 与 `gomobile` 构建 `libbox.aar` 并放到 `android/app/libs/libbox.aar`。
   - 方式 B（本机编译）：
     ```bash
     git clone --branch v1.12.12 --depth 1 https://github.com/SagerNet/sing-box.git
     cd sing-box
     go run ./cmd/internal/build_libbox -target android -platform android/arm64,android/amd64
     cp libbox.aar <build-myvpn>/android/app/libs/libbox.aar
     ```

## 构建命令
```bash
cd android
./gradlew :app:assembleDebug
```
