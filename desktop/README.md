# Owenclave desktop (macOS / Windows / Linux)

Compose Multiplatform client that reuses the Android app's protocol layer and runs
the same Go proxy engine as a child process.

```
┌────────────────────────────── Owenclave desktop (JVM, Compose) ─────────────────────────────┐
│  UI (Compose Multiplatform)                                                                 │
│  ProfileStore  ──  shared beans / parsers  (app/src/main/java/.../fmt, .../group)            │
│  DesktopConfigBuilder ──► V2Ray-format JSON ──► CoreRunner ──► owenclave-core (child proc)   │
│                                                     │                                        │
│                                                     └──► naive (child proc, local SOCKS)     │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

* `owenclave-core` is the `exclave-core` CLI, i.e. exactly the engine the Android
  app links through gomobile (`libowenclavecore.aar`). It consumes the same
  V2Ray-format JSON that `fmt/ConfigBuilder.kt` produces for Android.
* `naive` is the upstream NaiveProxy binary. Like on Android it is an external
  plugin: it listens on a local SOCKS port and the core dials into it.
* Profiles, share links, Clash YAML, V2Ray JSON and sing-box JSON are parsed by
  the **shared Android code** (`:desktop:shared` compiles
  `app/src/main/java/io/nekohasekai/sagernet/{fmt,group,ktx}` directly), so
  import behaviour is identical on both platforms.

## Modules

| Module | Purpose |
| --- | --- |
| `:desktop:shared` | JVM view of the Android protocol model + desktop shims (`desktop/shared/src/main/kotlin/desktopshim`) |
| `:desktop:app` | Compose Multiplatform application, core/plugin supervision, packaging |
| `desktop/core/dist/<os>-<arch>` | cross compiled `owenclave-core` binaries |
| `desktop/naive/dist/<os>-<arch>` | downloaded NaiveProxy binaries |

The Android build is untouched: the shims only exist inside the desktop modules,
and the shared sources are consumed read-only through Gradle `srcDirs`.

## Prerequisites

* JDK 21
* Go 1.26+ (only to build the core)
* `python3` and `curl` (only to download the NaiveProxy binaries)

## Build

```sh
# whole client for the current platform (core + plugin + installer)
./run desktop build

# just the cross platform binaries for every target
./run desktop build all
```

Individual steps:

```sh
TARGETS="linux/amd64"   ./run desktop core build     # or: all
TARGETS="darwin/arm64"  ./run desktop naive download # or: all
./gradlew :desktop:app:packageDistributionForCurrentOS
```

Artifacts land in `desktop/app/build/compose/binaries/main/`:

| Artifact | Path | Notes |
| --- | --- | --- |
| Application image | `app/Owenclave/` | unpack and run, produced by `createDistributable` |
| **Portable archive** | `portable/owenclave-desktop-<version>-<os>-<arch>-portable.zip` | `:desktop:app:packagePortable`, no installation needed |
| Installer | `deb/`, `dmg/`, `msi/` | `:desktop:app:packageDistributionForCurrentOS` |

```sh
# portable only
./gradlew :desktop:app:packagePortable

# installer for the host OS (needs dpkg/dpkg-deb on Linux, WiX on Windows)
./gradlew :desktop:app:packageDistributionForCurrentOS
```

### Portable build

Unpack the archive and start the launcher:

```text
Windows   Owenclave\Owenclave.exe
Linux     Owenclave/bin/Owenclave
macOS     Owenclave.app/Contents/MacOS/Owenclave     (unzip keeps the bundle)
```

The archive contains a `data` directory next to the launcher. As soon as it
exists, the client keeps profiles, settings, the generated core config and the
extracted core/NaiveProxy binaries inside the unpacked folder instead of
`%APPDATA%` / `~/Library/Application Support` / `~/.config`, so the whole thing is
self contained and can live on a USB stick. Remove `data/` to fall back to the
per user location.

`jpackage` can only build for the host OS, which is why
`.github/workflows/desktop.yml` runs the build on `ubuntu-latest`, `macos-13`
(x64), `macos-14` (arm64) and `windows-latest`, and publishes both the portable
archives and the installers.

## Run from the sources

```sh
./gradlew :desktop:app:run
```

`run` resolves the binaries straight from `desktop/core/dist` and
`desktop/naive/dist` for the current host.

## Verification

```sh
# what is bundled / resolvable
./gradlew :desktop:app:run --args="--version"

# parse an input and print the generated core config
./gradlew :desktop:app:run --args="--print-config /path/to/subscription.txt"

# end to end smoke test, no external server required:
# starts the core as a local Shadowsocks server, connects through the client
# and fetches an HTTP page over the local SOCKS inbound
./gradlew :desktop:app:run --args="--selftest"

# same, but from the packaged application image / portable archive
desktop/app/build/compose/binaries/main/app/Owenclave/bin/Owenclave --selftest

# NaiveProxy plugin integration (config accepted by the real naive binary)
./gradlew :desktop:app:run --args="--selftest-naive"

# unit tests of the importers and the config builder
./gradlew :desktop:app:test
```

The self test prints `[selftest] PASS` and exits with code 0 on success, which
makes it usable on Linux and Windows (including under Wine) in CI.

## Supported protocols

naive (external plugin), Shadowsocks (incl. 2022 methods and SIP003 plugins),
VMess, VLESS, Trojan, SOCKS, HTTP, Hysteria2, plus direct/block outbounds.

Transports: tcp, ws, grpc, h2/http, httpupgrade, quic, kcp, splithttp/xhttp with
TLS, uTLS, REALITY, ALPN, certificate pinning, ECH and mux/smux.

Profiles using protocols that are not wired up yet (Mieru, TUIC, Juicy,
AnyTLS, Snell, SSH, ShadowQUIC, TrustTunnel, HTTP3, WireGuard, OLCRTC) are
imported and shown, but marked as unsupported and cannot be connected. A raw
V2Ray JSON config can always be added as a custom profile and is passed to the
core unchanged.

Android-only concepts have no desktop equivalent and are intentionally absent:
TUN/VpnService (the core uses the SOCKS/HTTP inbounds instead), per-app proxy,
WorkManager based subscription refresh and the AIDL plugin discovery.

## Layout

```
desktop/
├── app/          Compose application, packaging, self test
├── shared/       JVM module: shared Android sources + desktop shims
├── core/dist/    cross compiled owenclave-core
└── naive/dist/   downloaded NaiveProxy binaries
```
