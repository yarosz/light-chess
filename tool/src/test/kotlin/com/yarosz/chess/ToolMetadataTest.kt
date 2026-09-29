package com.yarosz.chess

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the committed tool metadata that Light builds releases from. */
class ToolMetadataTest {

    private val lines = File("lighttool.toml").readLines()

    private fun value(key: String): String? =
        lines.map { it.trim() }
            .firstOrNull { it.startsWith("$key ") || it.startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.trim('"')

    @Test
    fun `committed server package is LightOS on the phone, not the emulator`() {
        // Light's builder uses this value as-is. Emulator builds swap it at build time instead
        // (scripts/emulator-build.sh), so the committed file must never point at the emulator.
        // Exact line: the wrapper's sed and light-build.sh's grep match this form verbatim.
        assertTrue("serverPackage = \"com.lightos\"" in lines)
    }

    @Test
    fun `chess is locked to portrait like LightOS`() {
        assertEquals("portrait", value("orientation"))
    }

    @Test
    fun `tool id never changes once published`() {
        assertEquals("com.yarosz.chess", value("id"))
        assertEquals("Chess", value("label"))
    }

    @Test
    fun `v1 declares no permissions of its own (D5)`() {
        // Light's SDK still merges INTERNET and others into the APK; scripts/release-check.sh apk pins them.
        assertEquals("[]", value("permissions"))
    }

    private val notices = File("src/main/assets/${UiCopy.NOTICES_ASSET}").readText()

    private fun words(text: String) = text.trim().split(Regex("\\s+")).joinToString(" ")

    @Test
    fun `About shows the version Light builds (D7)`() {
        assertEquals(value("versionName"), UiCopy.VERSION)
        assertEquals("Chess ${UiCopy.VERSION}", UiCopy.about(null, notices).first())
    }

    @Test
    fun `About states the licence, the source, the privacy line and the Pack's dump as plain text (D7)`() {
        val about = UiCopy.about("2026-09-09", notices).joinToString("\n")
        assertTrue("GNU General Public License, version 3 or later" in about)
        assertTrue("Source: github.com/yarosz/light-chess" in about)
        assertTrue(UiCopy.PRIVACY in about)
        assertTrue("(lichess.org), CC0, from the dump of 2026-09-09." in about)
        assertTrue("cburnett" in about && "Apache License, Version 2.0" in about)
    }

    @Test
    fun `About reproduces the cburnett licence verbatim (BSD-3 clause 2)`() {
        val licence = words(File("../third_party/cburnett/LICENSE").readText())
        assertTrue(licence in UiCopy.about(null, notices).joinToString(" "))
    }

    @Test
    fun `About's notices name the same libraries as NOTICE`() {
        val notice = words(File("../NOTICE").readText())
        for (library in listOf("kotlinx.serialization", "kotlinx.coroutines", "Jetpack Compose and AndroidX", "OkHttp",
            "Protocol Buffers", "SLF4J", "ML Kit", "Light's SDK")) {
            assertTrue(library in notices && library in notice, library)
        }
    }
}
