package dev.sophi.core.credentials

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class OAuthEndpoints(val authorizeUrl: String, val tokenUrl: String)

data class OAuthClient(val clientId: String, val clientSecret: String? = null)

data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long?)

class OAuthException(message: String) : Exception(message)

/**
 * Authorization code + PKCE (S256) in the browser, answered on a 127.0.0.1 loopback redirect; and refresh.
 * Nothing here stores anything: callers decide where tokens go.
 */
class OAuthFlow(
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
    private val openBrowser: (URI) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val callbackTimeoutMs: Long = 300_000,
) {
    private val random = SecureRandom()

    /** The redirect target. [port] 0 picks a free one; a taken [port] throws [java.net.BindException]. */
    class Loopback internal constructor(port: Int) : AutoCloseable {
        @Volatile internal var pending: CompletableDeferred<Map<String, String>>? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
            createContext("/callback") { ex ->
                val body = "Signed in. You can close this tab and go back to SoPhi.".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
                pending?.complete(query(ex.requestURI.rawQuery))
            }
            start()
        }
        val redirectUri = "http://127.0.0.1:${server.address.port}/callback"
        override fun close() = server.stop(0)
    }

    fun loopback(port: Int = 0): Loopback = Loopback(port)

    suspend fun authorize(
        endpoints: OAuthEndpoints, client: OAuthClient, loopback: Loopback, scopes: List<String>, extra: Map<String, String> = emptyMap(),
    ): Tokens {
        val verifier = randomUrlSafe(48).take(64)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(24)
        val got = CompletableDeferred<Map<String, String>>().also { loopback.pending = it }
        val params = listOfNotNull(
            "response_type" to "code", "client_id" to client.clientId, "redirect_uri" to loopback.redirectUri,
            scopes.takeIf { it.isNotEmpty() }?.let { "scope" to it.joinToString(" ") }, "state" to state,
            "code_challenge" to challenge, "code_challenge_method" to "S256",
        ) + extra.toList()
        val url = endpoints.authorizeUrl
        withContext(Dispatchers.IO) { openBrowser(URI(url + (if ('?' in url) "&" else "?") + form(params))) }
        val back = try { withTimeout(callbackTimeoutMs) { got.await() } } catch (e: TimeoutCancellationException) {
            throw OAuthException("Sign-in timed out")
        }
        if (back["state"] != state) throw OAuthException("Sign-in was not completed")
        back["error"]?.let { throw OAuthException("Sign-in failed: ${back["error_description"] ?: it}") }
        val code = back["code"] ?: throw OAuthException("Sign-in was not completed")
        return token(endpoints, listOfNotNull(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to loopback.redirectUri,
            "client_id" to client.clientId, "code_verifier" to verifier, client.clientSecret?.let { "client_secret" to it },
        ) + extra.toList(), keepRefresh = null)
    }

    suspend fun refresh(endpoints: OAuthEndpoints, client: OAuthClient, refreshToken: String, extra: Map<String, String> = emptyMap()): Tokens =
        token(endpoints, listOfNotNull(
            "grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to client.clientId,
            client.clientSecret?.let { "client_secret" to it },
        ) + extra.toList(), keepRefresh = refreshToken)

    private suspend fun token(endpoints: OAuthEndpoints, params: List<Pair<String, String>>, keepRefresh: String?): Tokens =
        withContext(Dispatchers.IO) {
            val req = HttpRequest.newBuilder(URI(endpoints.tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(params))).build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            val body = runCatching { Json.parseToJsonElement(res.body()) as? JsonObject }.getOrNull()
            fun str(key: String) = (body?.get(key) as? JsonPrimitive)?.contentOrNull
            if (res.statusCode() !in 200..299) {
                if (keepRefresh != null && str("error") == "invalid_grant") throw OAuthException("refresh token rejected")
                throw OAuthException("The sign-in server said ${res.statusCode()}${str("error_description")?.let { ": $it" } ?: ""}")
            }
            val access = str("access_token") ?: throw OAuthException("The sign-in answer had no access token")
            val expiresIn = (body?.get("expires_in") as? JsonPrimitive)?.longOrNull
            Tokens(access, str("refresh_token") ?: keepRefresh, expiresIn?.let { nowMs() + it * 1000 })
        }

    private fun randomUrlSafe(bytes: Int) = b64url(ByteArray(bytes).also(random::nextBytes))
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun form(params: List<Pair<String, String>>) =
        params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, Charsets.UTF_8) }
}

private fun query(s: String?) = (s ?: "").split("&").filter { '=' in it }
    .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
