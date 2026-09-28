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
    fun `v1 declares no permissions, so no INTERNET (D5)`() {
        assertEquals("[]", value("permissions"))
    }
}
