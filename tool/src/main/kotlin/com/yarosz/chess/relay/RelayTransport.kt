package com.yarosz.chess.relay

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One HTTPS request to the Relay: [path] from the root (`/v1/games/…/events?since=3`), the seat
 * secret as the bearer when the request acts as a Seat, and a JSON [body]. toString leaves the
 * secret out.
 */
class RelayRequest(val method: String, val path: String, val bearer: String? = null, val body: String? = null) {
    override fun toString() = "$method $path"
}

/** The Relay's answer: any status, with its body as text. [retryAfter] is the `Retry-After` header, if sent. */
data class RelayResponse(val status: Int, val body: String, val retryAfter: String? = null)

/**
 * How [RelayClient] reaches the Relay. [exchange] returns whatever response arrives, any status
 * included, and throws [IOException] when none does (offline, a timeout, a dropped connection). The
 * phone then can't know whether the Relay acted on the request, which is why every append is safe to
 * repeat (C8).
 */
fun interface RelayTransport {
    suspend fun exchange(request: RelayRequest): RelayResponse
}

/**
 * How the phone opens a Game's live socket (`GET /v1/games/{gameId}/live`, C1, G3), beside
 * [RelayTransport]: a seam, so the live session is tested on the JVM against the fake Relay. [open]
 * returns at once; what happens next reaches [listener], on any thread.
 */
fun interface LiveConnector {
    fun open(gameId: String, seatSecret: String, listener: LiveListener): LiveSocket
}

/** One live socket, open or opening. Both calls return at once and do nothing once it has closed. */
interface LiveSocket {
    fun send(text: String)

    fun close()
}

/** What happens to one live socket. After [onClosed] or [onFailure], nothing more is called. */
interface LiveListener {
    fun onOpen()

    fun onMessage(text: String)

    /** The Relay closed the socket with [code] (4001: another socket of this Seat replaced it; 4004: the Game was deleted). */
    fun onClosed(code: Int)

    /**
     * No socket, or it broke: [status] is the HTTP status when the Relay answered the upgrade with a
     * refusal (401 bad_seat_secret, 404 game_not_found, ...), null when no answer came.
     */
    fun onFailure(status: Int?)
}

/**
 * The real transport, over OkHttp (in the SDK's dependencies, and the library C1 names for the live
 * WebSocket). [baseUrl] is the Relay's root URL: HTTPS, or plain HTTP to a local Worker
 * ([RelayConfig.allowed]) for the opt-in end-to-end test and a debug build on the emulator. Only a
 * debug build's network security config permits that cleartext; a release permits none.
 *
 * Only [com.yarosz.chess.FriendOwner] constructs this, and only with [RelayConfig.url] set (ADR 0004).
 */
class OkHttpTransport(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) : RelayTransport {
    private val root = baseUrl.trimEnd('/')

    init {
        require(RelayConfig.allowed(root)) { "the Relay is reached over HTTPS" }
    }

    override suspend fun exchange(request: RelayRequest): RelayResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(root + request.path)
        request.bearer?.let { builder.header("Authorization", "Bearer $it") }
        val body = request.body?.toRequestBody(JSON)
        builder.method(request.method, body ?: if (request.method == "POST") ByteArray(0).toRequestBody(JSON) else null)
        client.newCall(builder.build()).execute().use { response ->
            RelayResponse(response.code, response.body.string(), response.header("Retry-After"))
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Short timeouts: a background sync has minutes, and a lost response is retried anyway. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/**
 * The live socket over OkHttp's WebSocket (C1, V2), with the seat secret as the bearer of the
 * upgrade request. [client] is [OkHttpTransport]'s, as it is: its connect and read timeouts bound the
 * TCP connect, the TLS handshake and the wait for the 101. Once the socket is open OkHttp clears
 * them (the socket's own timeout is set to 0 on the upgrade, and the reader lifts the read timeout
 * while it waits for a frame), so a quiet socket is the session's watchdog to judge (W7, U5). The
 * phone pings; OkHttp's own ping is off.
 *
 * Only [com.yarosz.chess.FriendOwner] constructs this, and only with [RelayConfig.url] set (ADR 0004).
 */
class OkHttpLiveConnector(baseUrl: String, private val client: OkHttpClient) : LiveConnector {
    private val root = baseUrl.trimEnd('/')

    init {
        require(RelayConfig.allowed(root)) { "the Relay is reached over HTTPS" }
    }

    override fun open(gameId: String, seatSecret: String, listener: LiveListener): LiveSocket {
        val request = Request.Builder().url(url(root, gameId)).header("Authorization", "Bearer $seatSecret").build()
        val opened = AtomicBoolean(false)
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL, null)
                listener.onClosed(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onFailure(response?.code?.takeIf { it != SWITCHING })
        })
        return object : LiveSocket {
            override fun send(text: String) {
                socket.send(text)
            }

            /**
             * An open socket closes with 1000. One still opening is cancelled: close only queues the
             * frame, and would leave the call, its thread and a Dispatcher slot until the upgrade ends.
             */
            override fun close() {
                if (!opened.get() || !socket.close(NORMAL, null)) socket.cancel()
            }
        }
    }

    companion object {
        private const val NORMAL = 1000
        private const val SWITCHING = 101

        /** The live endpoint of [gameId] under [root]: `https://` becomes `wss://`, a local `http://` `ws://`. */
        fun url(root: String, gameId: String): String {
            require(Protocol.isGameId(gameId)) { "not a Game id" }
            val scheme = if (root.startsWith("https://")) "wss://" else "ws://"
            return scheme + root.trimEnd('/').substringAfter("://") + "/v${Protocol.MAJOR}/games/$gameId/live"
        }
    }
}
