package io.nekohasekai.sagernet.fmt

/*
 * Top level constants of `ConfigBuilder.kt` that are shared by the protocol code.
 * `ConfigBuilder.kt` itself is Android bound (it builds the VPN service config)
 * and is replaced on desktop by `DesktopConfigBuilder`.
 */

const val LOCALHOST = "127.0.0.1"
const val LOCALHOST6 = "::1"
