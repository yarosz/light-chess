package com.yarosz.chess.relay

import com.yarosz.chess.BuildConfig

/**
 * Where the Relay is (W8). Light builds releases from the public commit, so the deployed Relay's
 * root URL is a committed constant, and it stays empty until the maintainer deploys the Relay
 * (relay/README.md). While it is empty, Play a friend is off: no Menu entry, no LightWork job, no
 * request, and About keeps the "never uses the network" line (W1).
 */
object RelayConfig {
    /** The deployed Relay, HTTPS only. Empty: not deployed yet. */
    const val URL = ""

    /**
     * The URL this build talks to: [URL], or a debug build's `-Prelay.url` override, which lives only
     * in the debug variant's generated BuildConfig (tool/build.gradle.kts). Every other build type's
     * BuildConfig.RELAY_URL is empty.
     */
    val url: String get() = BuildConfig.RELAY_URL.ifEmpty { URL }

    val enabled: Boolean get() = url.isNotEmpty()

    /**
     * Whether [url] may be a Relay's: HTTPS, or plain HTTP to this machine (127.0.0.1, localhost) or to
     * the Android emulator's alias for it (10.0.2.2), which only a debug build's network security
     * config lets through (tool/src/debug).
     */
    fun allowed(url: String): Boolean = url.startsWith("https://") || LOCAL.matches(url.trimEnd('/'))

    private val LOCAL = Regex("""http://(127\.0\.0\.1|localhost|10\.0\.2\.2)(:\d+)?""")
}
