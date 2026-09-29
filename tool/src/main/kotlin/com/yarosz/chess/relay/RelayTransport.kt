package com.yarosz.chess.relay

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
