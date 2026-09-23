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

### Desktop (macOS / Windows / Linux)
- `./run desktop build` - core + naive + installer for the current OS
- `./run desktop build all` - cross platform `desktop/core/dist` + `desktop/naive/dist`
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
- `ConfigBuilder.kt` - V2Ray config generation (Android)
- `V2RayInstance.kt` - external plugin management (naive, shadowquic, olcrtc)
- `desktop/app/.../DesktopConfigBuilder.kt` - V2Ray config generation (desktop)
- `desktop/app/.../CoreRunner.kt` - core + plugin process supervision (desktop)
- `themes.xml` / `styles.xml` - UI theme and dialog styling
- `Theme.kt` - theme application logic

### Package
- Application ID: `org.owenewans.owenclave`
- Namespace: `io.nekohasekai.sagernet`
- Go library: `libowenclavecore` (built from `github.com/owenewans/libowenclavecore`)
