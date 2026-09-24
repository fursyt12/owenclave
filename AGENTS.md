# AGENTS.md

## Build

This is an Android proxy client app (fork of exclave/sagernet).

### Prerequisites
- JDK 21
- Go 1.26+ and Go Mobile
- Android SDK Platform 37.0, Build-Tools 37.0.0, Platform-Tools, NDK r29
- Or use Nix: `nix develop`

### Build steps
1. Build libowenclavecore: `./run lib core` or `./library/core/build.sh`
2. Download assets: `./gradlew :app:downloadAssets`
3. Build APK: `./gradlew :app:assembleOssRelease`
4. APK output: `./app/build/outputs/apk/oss/release/`

### Nix
```sh
nix develop
./run lib core
./gradlew :app:assembleOssRelease
```

### Lint/Typecheck
- Lint: `./gradlew :app:lintOssRelease`
- No dedicated typecheck command; use `./gradlew :app:compileOssReleaseKotlin`

### Testing
- `./gradlew test`
- Desktop smoke test: `./gradlew :desktop:app:run --args="--selftest"` (starts the core
  as a local Shadowsocks server and tunnels an HTTP request through it)
- Desktop naive plugin test: `./gradlew :desktop:app:run --args="--selftest-naive"`
- Desktop protocol tests (real core as local server, end to end):
  `./gradlew :desktop:app:run --args="--selftest-protocols"` covers
  vless, vmess, trojan, anytls, shadowsocks, socks and http;
  `--selftest-vless` and `--selftest-protocol <name>` run a single one.
- Desktop settings parity: the settings screen is generated from the Android
  `app/src/main/res/xml/global_preferences.xml` (copied into the application
  resources by a Gradle task) - `./gradlew :desktop:app:run --args="--print-settings"`
  dumps it, and `SettingsParityTest`/`SettingsConfigEffectTest` keep it in sync,
  including that a changed setting reaches the generated config.

- `:desktop:app:test` also feeds every generated protocol config (including the
  client-only ones) to `owenclave-core test` when the host core binary is present.

### Desktop (macOS / Windows / Linux)
- `./run desktop build` - core + naive + olcrtc + installer for the current OS
- `./run desktop build all` - cross platform binaries in `desktop/core/dist`,
  `desktop/naive/dist`, `desktop/olcrtc/dist` and `desktop/tun2socks/dist`
- olcrtc is built from a pinned upstream commit and is not published as a prebuilt
  binary: `./run desktop olcrtc build` (pure Go, `CGO_ENABLED=0`); `all` builds
  linux/amd64, darwin/amd64+arm64 and windows/amd64+arm64
- `:desktop:shared` compiles the Android `fmt`/`group`/`ktx` sources read-only; the
  Android bound files are excluded and replaced by the Kotlin shims in
  `desktop/shared/src/main/kotlin/desktopshim` (same packages, so the Android app is
  unaffected). Keep both sides compiling when touching shared model code.
- Docs: `desktop/README.md`

### Key files
- `Constants.kt` - connection test URL, key constants
- `DataStore.kt` - settings defaults and storage
- `global_preferences.xml` - settings UI layout
- `ProxyEntity.kt` - protocol types and entity management
- `ConfigBuilder.kt` - V2Ray config generation (Android, shared with desktop)
- `V2RayInstance.kt` - external plugin management (naive, shadowquic, olcrtc)
- `desktop/app/.../DesktopConfigBuilder.kt` - desktop adapter over `ConfigBuilder.kt`
- `desktop/app/.../ExternalPlugins.kt` - external engine (naive, olcrtc) supervision
- `desktop/app/.../CoreRunner.kt` - core + plugin process supervision (desktop)
- `desktop/app/.../ProtocolSelfTest.kt` - end to end protocol self tests
- `themes.xml` / `styles.xml` - UI theme and dialog styling
- `Theme.kt` - theme application logic

### Package
- Application ID: `org.owenewans.owenclave`
- Namespace: `io.nekohasekai.sagernet`
- Go library: `libowenclavecore` (built from `github.com/owenewans/libowenclavecore`)
