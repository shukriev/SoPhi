package dev.sophi.mcp.auth

import dev.sophi.core.credentials.CredentialStore
import dev.sophi.core.credentials.OAuthClient
import dev.sophi.core.credentials.OAuthEndpoints
import dev.sophi.core.credentials.OAuthException
import dev.sophi.core.credentials.OAuthFlow
import dev.sophi.core.credentials.SecretMask
import dev.sophi.core.credentials.Tokens
import dev.sophi.core.credentials.requireSecureUrl
import dev.sophi.mcp.config.McpServerConfig
import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.BindException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** A remote MCP server that wants a sign-in Sophi doesn't have (never had one, or it was revoked). */
class SignInRequiredException(val server: String) :
    Exception("$server needs you to sign in again — run `sophi mcp login $server`")

/** The [SignInRequiredException] somewhere in this throwable's cause chain, if any (transports wrap what they throw). */
fun Throwable.signInRequired(): SignInRequiredException? =
    generateSequence(this) { it.cause }.take(10).firstNotNullOfOrNull { it as? SignInRequiredException }

/** What a sign-in leaves in the credential store, as JSON under `mcp-oauth:<server>`. Access tokens stay in memory. */
@Serializable
data class StoredSignIn(
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String,
    val refreshToken: String?,
    val resource: String,
    /** True when [clientId] came from dynamic registration, so a new port may re-register. */
    val registered: Boolean = false,
)

/**
 * Sign-in for remote MCP servers per the MCP authorization spec: protected-resource metadata, authorization-server
 * metadata, dynamic client registration (unless mcp.json names a client), then [OAuthFlow]. Refresh is silent and
 * runs under [lockFile], re-reading the stored refresh token, so the CLI, the companion and scheduled runs sharing
 * one sign-in never strand each other when a server rotates refresh tokens. Nothing here opens a browser except [login].
 */
class McpOAuth(
    private val store: CredentialStore,
    private val mask: SecretMask,
    private val flow: OAuthFlow,
    private val lockFile: Path = Path.of(System.getProperty("user.home"), ".sophi", "credentials.lock"),
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onSignInNeeded: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val access = ConcurrentHashMap<String, Tokens>()
    /** Whether each server has a stored sign-in, so requests and UI redraws don't each start a Keychain lookup. */
    private val signedIn = ConcurrentHashMap<String, Boolean>()
    /** Servers whose last refresh failed: [onSignInNeeded] fires once per failure streak. */
    private val failing = ConcurrentHashMap.newKeySet<String>()

    /** Signs in through the browser (via [flow]'s openBrowser) and stores the result. [config] must be resolved. */
    suspend fun login(config: McpServerConfig, flow: OAuthFlow = this.flow): Result<Unit> = runCatching {
        val url = requireNotNull(config.url) { "${config.name} has no url" }
        val endpoints = discover(url, config.name)
        val previous = stored(config.name)
        val configured = config.oauth?.let { OAuthClient(it.clientId, it.clientSecret) }
        val scopes = config.oauth?.scopes.orEmpty()
        val extra = mapOf("resource" to url)
        val reusable = previous?.takeIf { configured == null && it.registered && it.authorizeUrl == endpoints.first.authorizeUrl }
        val port = (reusable ?: previous?.takeIf { configured != null })?.let { URI(it.redirectUri).port } ?: 0
        val loopback = try { flow.loopback(port) } catch (e: BindException) { flow.loopback() }
        loopback.use { lb ->
            val client = configured
                ?: reusable?.takeIf { it.redirectUri == lb.redirectUri }?.let { OAuthClient(it.clientId, it.clientSecret) }
                ?: register(endpoints.second ?: throw OAuthException("${config.name} needs a client id: add an \"oauth\" block to mcp.json"), lb.redirectUri)
            val tokens = flow.authorize(endpoints.first, client, lb, scopes, extra)
            save(config.name, StoredSignIn(
                endpoints.first.authorizeUrl, endpoints.first.tokenUrl, client.clientId, client.clientSecret, lb.redirectUri,
                tokens.refreshToken, url, registered = configured == null,
            ))
            remember(config.name, tokens)
        }
        signedIn[config.name] = true
        failing.remove(config.name)
    }

    fun logout(server: String) {
        access.remove(server)
        signedIn[server] = false
        store.remove(key(server))
    }

    /** Cached; [install]'s 401 path re-reads it, so a sign-in another process made is still found. */
    fun hasSignIn(server: String): Boolean = signedIn.getOrPut(server) { stored(server) != null }

    /** A live access token: cached, else refreshed. Null when there's no sign-in or the refresh was refused. */
    suspend fun accessToken(server: String): String? {
        access[server]?.takeIf { fresh(it) }?.let { return it.accessToken }
        if (!hasSignIn(server)) return null
        return locked {
            // Another process may have refreshed (and rotated the refresh token) while this one waited.
            access[server]?.takeIf { fresh(it) }?.accessToken ?: refresh(server)
        }
    }

    /** Adds the bearer token to every request; a 401 refreshes once and retries, then fails with [SignInRequiredException]. */
    fun install(client: KtorClient, server: String) {
        fun refuse(): Nothing = throw SignInRequiredException(server)
        client.plugin(HttpSend).intercept { request ->
            // Only a server this process holds a token for sends one up front; the rest find out from a 401.
            if (access.containsKey(server)) request.headers[HttpHeaders.Authorization] = "Bearer ${accessToken(server) ?: refuse()}"
            val first = execute(request)
            if (first.response.status != HttpStatusCode.Unauthorized) return@intercept first
            access.remove(server)
            signedIn.remove(server)
            val token = accessToken(server) ?: refuse()
            request.headers[HttpHeaders.Authorization] = "Bearer $token"
            val second = execute(request)
            if (second.response.status == HttpStatusCode.Unauthorized) refuse()
            second
        }
    }

    private suspend fun refresh(server: String): String? {
        val s = stored(server) ?: run { signedIn[server] = false; return null }
        val refreshToken = s.refreshToken ?: return signInNeeded(server)
        return try {
            val tokens = flow.refresh(
                OAuthEndpoints(s.authorizeUrl, s.tokenUrl), OAuthClient(s.clientId, s.clientSecret), refreshToken, mapOf("resource" to s.resource),
            )
            if (tokens.refreshToken != refreshToken) save(server, s.copy(refreshToken = tokens.refreshToken))
            failing.remove(server)
            remember(server, tokens)
        } catch (e: OAuthException) {
            signInNeeded(server)
        }
    }

    private fun signInNeeded(server: String): String? {
        access.remove(server)
        if (failing.add(server)) onSignInNeeded(server)
        return null
    }

    private fun remember(server: String, tokens: Tokens): String {
        mask.add(tokens.accessToken)
        tokens.refreshToken?.let(mask::add)
        access[server] = tokens
        return tokens.accessToken
    }

    private fun fresh(t: Tokens) = t.expiresAtMs?.let { it - 60_000 > nowMs() } ?: true

    private fun stored(server: String): StoredSignIn? =
        store.get(key(server))?.let { runCatching { json.decodeFromString(StoredSignIn.serializer(), it) }.getOrNull() }

    private fun save(server: String, s: StoredSignIn) {
        s.refreshToken?.let(mask::add)
        s.clientSecret?.let(mask::add)
        store.set(key(server), json.encodeToString(StoredSignIn.serializer(), s))
    }

    /** One refresher at a time: a mutex for this process, a file lock for the others. */
    private suspend fun <T> locked(block: suspend () -> T): T = jvmLock(lockFile).withLock {
        withContext(Dispatchers.IO) {
            Files.createDirectories(lockFile.toAbsolutePath().parent)
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
                ch.lock().use { block() }
            }
        }
    }

    /** (authorize + token endpoints, registration endpoint or null). */
    private suspend fun discover(url: String, server: String): Pair<OAuthEndpoints, String?> = withContext(Dispatchers.IO) {
        val origin = URI(url).let { "${it.scheme}://${it.rawAuthority}" }
        val challenge = send(HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString("{}")))
        val metadataUrl = challenge?.headers()?.firstValue("WWW-Authenticate")?.orElse(null)
            ?.let { Regex("resource_metadata=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?: "$origin/.well-known/oauth-protected-resource"
        requireSecureUrl(metadataUrl)
        val authServer = getJson(metadataUrl)?.get("authorization_servers")?.jsonArray?.firstOrNull()
            ?.let { (it as? JsonPrimitive)?.contentOrNull }?.trimEnd('/') ?: origin
        requireSecureUrl(authServer)
        val meta = getJson("$authServer/.well-known/oauth-authorization-server")
            ?: getJson("$authServer/.well-known/openid-configuration")
            ?: throw OAuthException("$server doesn't offer sign-in")
        fun str(k: String) = (meta[k] as? JsonPrimitive)?.contentOrNull
        val endpoints = OAuthEndpoints(
            str("authorization_endpoint") ?: throw OAuthException("$server's sign-in has no authorization endpoint"),
            str("token_endpoint") ?: throw OAuthException("$server's sign-in has no token endpoint"),
        )
        endpoints to str("registration_endpoint")
    }

    private suspend fun register(endpoint: String, redirectUri: String): OAuthClient = withContext(Dispatchers.IO) {
        requireSecureUrl(endpoint)
        val body = buildJsonObject {
            put("client_name", "Sophi")
            putJsonArray("redirect_uris") { add(JsonPrimitive(redirectUri)) }
            putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none")
        }.toString()
        val res = send(HttpRequest.newBuilder(URI(endpoint)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))) ?: throw OAuthException("Couldn't register Sophi with the sign-in server")
        val reply = runCatching { json.parseToJsonElement(res.body()) as? JsonObject }.getOrNull()
        val id = (reply?.get("client_id") as? JsonPrimitive)?.contentOrNull
        if (res.statusCode() !in 200..299 || id == null) throw OAuthException("The sign-in server refused to register Sophi (${res.statusCode()})")
        OAuthClient(id, (reply["client_secret"] as? JsonPrimitive)?.contentOrNull)
    }

    private fun getJson(url: String): JsonObject? =
        send(HttpRequest.newBuilder(URI(url)).header("Accept", "application/json").GET())
            ?.takeIf { it.statusCode() in 200..299 }
            ?.let { runCatching { json.parseToJsonElement(it.body()) as? JsonObject }.getOrNull() }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String>? =
        runCatching { http.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }.getOrNull()

    private fun key(server: String) = "mcp-oauth:$server"

    private companion object {
        private val locks = ConcurrentHashMap<Path, Mutex>()
        fun jvmLock(p: Path): Mutex = locks.computeIfAbsent(p.toAbsolutePath().normalize()) { Mutex() }
    }
}
