package com.yarosz.chess

import com.yarosz.chess.relay.RelayConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two boundaries, held on the source (replacing v3 PR 1's NoNetworkYetTest):
 *
 * - The network (ADR 0004, W1, W8): only [FriendOwner] makes the Relay's client, and only once
 *   RelayConfig.url is set; nothing of Puzzles or the computer reaches it. FriendOwnerTest shows that
 *   an empty store sends nothing and schedules no job, and that an empty URL makes no owner at all.
 * - The engine (W10, contradiction 6): no Correspondence Game code reaches the engine, the Book, the
 *   Game Hint, the evals or a Takeback.
 */
class BoundaryTest {

    private val main = File("src/main/kotlin/com/yarosz/chess")

    private fun sources(): Sequence<File> = main.walkTopDown().filter { it.isFile && it.extension == "kt" }

    private fun lines(file: File) = file.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }

    @Test
    fun `only FriendOwner constructs the Relay's client, the transport and the sync engine`() {
        val constructions = Regex("""\b(OkHttpTransport|OkHttpLiveConnector|RelayClient|Correspondence|LiveOwner)\(""")
        val definitions = setOf("relay/RelayTransport.kt", "relay/RelayClient.kt", "correspondence/Correspondence.kt", "correspondence/LiveOwner.kt")
        val uses = sources()
            .filter { it.relativeTo(main).path !in definitions }
            .flatMap { file -> lines(file).filter { constructions.containsMatchIn(it) }.map { "${file.relativeTo(main)}: ${it.trim()}" } }
            .toList()
        assertEquals(listOf("FriendOwner.kt"), uses.map { it.substringBefore(':') }.distinct(), "$uses")
    }

    @Test
    fun `FriendOwner makes a client and the live connector only after checking that the URL is set`() {
        val owner = File(main, "FriendOwner.kt").readText()
        val check = owner.indexOf("if (url.isEmpty()) return null")
        val client = owner.indexOf("RelayClient(OkHttpTransport(url, http))")
        val live = owner.indexOf("OkHttpLiveConnector(url, ")
        assertTrue(check in 0 until client, "the URL check comes before the client")
        assertTrue(check in 0 until live, "the URL check comes before the live connector")
    }

    @Test
    fun `only a Correspondence Game's board asks for a live socket (ADR 0004 rule 2, U6)`() {
        val callers = { call: Regex ->
            sources()
                .filter { file -> file.name != "FriendOwner.kt" && !file.relativeTo(main).path.startsWith("correspondence/") }
                .filter { file -> lines(file).any { call.containsMatchIn(it) } }
                .map { it.relativeTo(main).path }
                .sorted()
                .toList()
        }
        assertEquals(listOf("FriendGameScreen.kt"), callers(Regex("""\.(watch|unwatch)\(""")))
        // A pause only closes: every Play a friend screen that can be on top over a board, or just after one, calls it.
        assertEquals(
            listOf("FriendGameScreen.kt", "FriendListScreen.kt", "FriendScreen.kt"),
            callers(Regex("""\bfriends\??\.pause\(""")),
        )
    }

    @Test
    fun `Puzzles and Games against the computer never reach the network`() {
        val network = Regex("""\b(FriendOwner|OkHttpTransport|OkHttpLiveConnector|LiveConnector|LiveOwner|RelayClient|Correspondence|okhttp3)\b""")
        for (path in listOf("PuzzleOwner.kt", "GameOwner.kt", "games", "puzzles", "engine", "book", "rules", "board")) {
            val root = File(main, path)
            val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList() else listOf(root)
            for (file in files) for (line in lines(file)) assertFalse(network.containsMatchIn(line), "${file.relativeTo(main)}: $line")
        }
    }

    @Test
    fun `the engine and the Book never touch a Correspondence Game (W10)`() {
        val engine = Regex("""\b(EngineHost|Engine|PirarucuEngine|LevelPlayer|LevelRequest|Book|BookPolicy|GameOwner|GameFlow|evals?|takeback|Takeback|hint|Hint)\b""")
        val friendFiles = sources().filter { it.relativeTo(main).path.startsWith("correspondence/") || it.name.startsWith("Friend") }.toList()
        assertTrue(friendFiles.size >= 8, "${friendFiles.map { it.name }}")
        for (file in friendFiles) for (line in lines(file)) {
            // The strip's enum states in its KDoc that it has no Hint or Takeback; code lines may not name them.
            assertFalse(engine.containsMatchIn(line), "${file.relativeTo(main)}: ${line.trim()}")
        }
        assertTrue(FriendButton.entries.none { it.label == UiCopy.HINT || it.label == UiCopy.TAKEBACK || it.label == UiCopy.MOVE_NOW })
    }

    @Test
    fun `the Relay URL is empty or HTTPS, and a debug build may name a local Worker (W8)`() {
        assertTrue(RelayConfig.URL.isEmpty() || RelayConfig.URL.startsWith("https://"), RelayConfig.URL)
        assertTrue(RelayConfig.url.isEmpty() || RelayConfig.allowed(RelayConfig.url), RelayConfig.url)
        assertTrue(RelayConfig.allowed("https://chess-relay.example.workers.dev"))
        assertTrue(RelayConfig.allowed("http://127.0.0.1:8787"))
        assertTrue(RelayConfig.allowed("http://10.0.2.2:8787"))
        assertFalse(RelayConfig.allowed("http://relay.example.com"))
        assertFalse(RelayConfig.allowed("http://10.0.2.3:8787"))
        assertFalse(RelayConfig.allowed("ftp://127.0.0.1"))
    }

    @Test
    fun `the committed Relay URL is the Worker's only address, bound outside wrangler (W11)`() {
        assertEquals("https://chess-relay.yarosz.com", RelayConfig.URL)
        val wrangler = File("../relay/wrangler.jsonc").readText()
        // workers_dev off: no second address. No routes: the custom domain is bound outside this repo.
        assertTrue(Regex(""""workers_dev":\s*false""").containsMatchIn(wrangler), wrangler)
        assertFalse(Regex(""""routes"\s*:|"route"\s*:""").containsMatchIn(wrangler), wrangler)
        assertTrue(RelayConfig.URL in wrangler, "wrangler.jsonc names the address the Tool ships")
    }

    @Test
    fun `only the debug build allows cleartext, and only to the local addresses`() {
        val config = File("src/debug/res/xml/network_security_config.xml").readText()
        assertTrue("""<base-config cleartextTrafficPermitted="false" />""" in config)
        val domains = Regex("""<domain[^>]*>([^<]+)</domain>""").findAll(config).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("10.0.2.2", "127.0.0.1"), domains)
        assertFalse(File("src/main/res/xml/network_security_config.xml").exists())
        assertFalse(File("src/release").exists(), "release has no source set of its own")
        val build = File("build.gradle.kts").readText()
        val debugBlock = build.substringAfter("debug {").substringBefore("release {")
        assertTrue("relay.url" in debugBlock, "the override is read in the debug block only")
        assertFalse("relay.url" in build.substringAfter("release {"), "no other build type reads it")
    }
}
