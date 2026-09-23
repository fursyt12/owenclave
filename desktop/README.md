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

Desktop artifacts are versioned `1.a.b` for an Android app version `0.a.b`:
`jpackage` refuses an app-version whose first component is zero on macOS
("The first number in an app-version cannot be zero or negative"), and all three
desktop platforms should carry the same version. `--version` and the UI still show
the application version from `version.properties`.

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

# unit tests of the importers, the rule builder and the config builder
./gradlew :desktop:app:test

# open a specific tab right away, handy when checking that every screen renders
./gradlew :desktop:app:run -Psection=RULES
```

The self test prints `[selftest] PASS` and exits with code 0 on success, which
makes it usable on Linux and Windows (including under Wine) in CI.

## UI

The window is a left navigation rail with the screens modelled after the Android
app:

| Tab | What it does |
| --- | --- |
| **Profiles** | imported profiles (with the subscription they came from), one click connect, delete; paste/share-link/raw-config import on the right |
| **Subscriptions** | subscription URLs with a name and a per-subscription HWID switch, "Update" / "Update all", last update time and the last error |
| **Rules** | routing rules (`Proxy` / `Direct` / `Block`) with domains (including the `domain:` / `full:` / `keyword:` / `regexp:` prefixes), IP CIDR, port, network and sniffed protocol; rules apply on the next connect |
| **Settings** | SOCKS/HTTP ports, routing mode, LAN bypass, log level, HWID (global switch, current value, "generate a new identity"), fetch-through-the-tunnel switch, and the runtime paths |
| **Log** | everything the core and the plugins print, with copy and clear |

Subscriptions are refreshed through the connected profile when one is running and
`Settings → Fetch through the connected profile` is on, which also gets around
local filtering that would break a plain HTTPS request.

HWID reporting follows the Android implementation: the same `x-hwid`,
`x-device-os`, `x-ver-os` and `x-device-model` headers, sent when the global
switch or the per-subscription switch is on.

### Not there yet compared to Android

* no per-protocol profile editor: profiles are imported, renamed via their import
  source, connected and deleted, but the protocol forms of the Android app are not
  rebuilt on desktop;
* rules have no per-app / package matching (SSID and package rules are Android
  concepts) and there is no rule reordering UI yet;
* no subscription auto-update scheduling, no QR scanning and no TUN / system wide
  transparent proxying (the SOCKS and HTTP inbounds are the integration point).

## Transparent mode (TUN)

The core CLI cannot create a TUN device in this fork: on Android the app creates one
through `VpnService` and hands the fd to the gomobile library. Desktop transparent
mode is therefore assembled from two pieces:

```
system traffic -> TUN device -> tun2socks -> SOCKS 127.0.0.1:<port> -> core -> upstream
```

* `tun2socks` (v2.7.0, downloaded by `./run desktop tun2socks download`) owns the
  device and pumps it into the core's local SOCKS inbound;
* the core's outbound is pinned to the physical interface with
  `streamSettings.sockopt.bindToDevice`, which the core implements on Linux
  (`SO_BINDTODEVICE`), macOS (`IP_BOUND_IF`) and Windows (`IP_UNICAST_IF`). Without
  that pin the upstream connection would follow the tunnel's default route and loop;
* addresses and routes follow the upstream tun2socks recipes: split defaults
  (`0.0.0.0/1` + `128.0.0.0/1`) on Linux, the documented route list on macOS,
  `netsh` address/DNS/route on Windows. Everything is removed again on disconnect.

Enable it in **Settings → Transparent mode (TUN)**; it applies on the next connect.

### Privileges

TUN mode needs administrator rights:

| OS | How |
| --- | --- |
| Linux | run the client as root, or grant `CAP_NET_ADMIN` |
| macOS | start it with `sudo` |
| Windows | start it as Administrator (plus `wintun.dll`, see below) |

Windows additionally needs `wintun.dll` next to `tun2socks.exe`; the download script
fetches it from wintun.net when that host is reachable, otherwise put the DLL into
the runtime directory by hand (the app says so explicitly).

### Verification

```sh
# no privileges needed if user namespaces are available: a namespace gives the TUN
# device without touching the host routes, and a stub SOCKS server answers
sudo unshare -rn bash -c "ip link set lo up; ip route add default dev lo || true; \
  ./desktop/app/build/compose/binaries/main/app/Owenclave/bin/Owenclave --selftest-tun-stub"

# the real thing: takes over the default route and fetches a public URL through it
sudo ./desktop/app/build/compose/binaries/main/app/Owenclave/bin/Owenclave --selftest-tun
```

CI runs both: the namespace variant on Linux, the real one (last step, non blocking)
on Linux, macOS and Windows.

## Arch Linux

The client is packaged as `owenclave-desktop` for `/opt/owenclave` (the jpackage
application image, so no system JRE is needed) with a launcher in `/usr/bin`, a
desktop entry and an icon.

One command adds the pacman repository and installs the client:

```sh
sudo bash -c "$(curl -fsSL https://raw.githubusercontent.com/fursyt12/owenclave/dev/packaging/arch/setup-repo.sh)"
```

The repository lives in the moving `desktop-repo` GitHub release, so the same URL
keeps working and `pacman -Syu` picks up new builds. The packages are not GPG
signed, which the installer reflects with `SigLevel = Optional TrustAll` (the files
come over HTTPS from that single release URL). Remove the repository entry again
with `... setup-repo.sh --uninstall`.

Building the package locally (needs `base-devel`):

```sh
./run desktop arch package    # writes desktop/arch/dist/*.pkg.tar.zst and owenclave.db
```

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
