package com.yarosz.chess.relay

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real connector against a local server that accepts TCP and never answers the upgrade (U5). */
class OkHttpLiveConnectorTest {
    private val server = ServerSocket(0)
    private val accepted = CountDownLatch(1)

    init {
        thread(isDaemon = true) {
            runCatching {
                // Hold the connection open, read nothing back: the upgrade hangs.
                server.accept()
                accepted.countDown()
            }
        }
    }

    @AfterTest
    fun cleanUp() = server.close()

    @Test
    fun `closing a socket that never opened cancels its call and frees its Dispatcher slot`() {
        val client = OkHttpTransport.defaultClient()
        var failure: Int? = -1
        val ended = CountDownLatch(1)
        val listener = object : LiveListener {
            override fun onOpen() {}

            override fun onMessage(text: String) {}

            override fun onClosed(code: Int) {}

            override fun onFailure(status: Int?) {
                failure = status
                ended.countDown()
            }
        }
        val socket = OkHttpLiveConnector("http://127.0.0.1:${server.localPort}", client).open("a".repeat(64), "secret", listener)
        assertTrue(accepted.await(5, TimeUnit.SECONDS), "the upgrade reached the server")
        assertEquals(1, client.dispatcher.runningCallsCount(), "the upgrade waits for its 101")

        socket.close()
        assertTrue(ended.await(5, TimeUnit.SECONDS), "the call ends at once, not when the upgrade would")
        assertNull(failure, "no HTTP answer came")
        val deadline = System.currentTimeMillis() + 5_000
        while (client.dispatcher.runningCallsCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(0, client.dispatcher.runningCallsCount(), "its Dispatcher slot is free")
    }

    @Test
    fun `the live socket keeps the HTTPS client's timeouts, which bound the upgrade`() {
        val client = OkHttpTransport.defaultClient()
        assertTrue(client.readTimeoutMillis in 1..20_000, "a hung TLS handshake or 101 times out")
        assertTrue(client.connectTimeoutMillis in 1..15_000)
    }
}
