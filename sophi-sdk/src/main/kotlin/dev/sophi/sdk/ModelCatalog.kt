package dev.sophi.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream

/** One model as the catalog describes it. Prices are USD per 1M tokens; null when the catalog has none. */
data class ModelInfo(
    val provider: String,
    val id: String,
    val contextTokens: Int?,
    val outputTokens: Int?,
    val inputUsdPerM: Double?,
    val outputUsdPerM: Double?,
    val cacheReadUsdPerM: Double?,
    val tools: Boolean,
    val reasoning: Boolean,
    val vision: Boolean,
    /** models.dev `canonical_model_id`, e.g. `alibaba/qwen3-8b`: what a local GGUF build is matched against. */
    val baseModel: String?,
) {
    val priced: Boolean get() = inputUsdPerM != null && outputUsdPerM != null
}

/**
 * Model windows, prices and capabilities from models.dev, in a compact form:
 * `{"<provider>":{"<id>":{"c":ctx,"o":out,"i":in,"p":outPrice,"r":cacheRead,"t":1,"x":1,"v":1,"k":"<canonical>"}}}`.
 * It only describes models; it never picks one.
 */
class ModelCatalog(private val entries: List<ModelInfo>) {
    fun size(): Int = entries.size

    /**
     * [model] by exact id (any case) or by the part after the last `/` (`anthropic/claude-x` ≈ `claude-x`).
     * Ranked: [preferProvider] exact, [preferProvider] bare, any exact, any bare; ties go to file order.
     */
    fun find(model: String, preferProvider: String? = null): ModelInfo? {
        val bare = model.substringAfterLast('/')
        fun rank(e: ModelInfo): Int? {
            val exact = e.id.equals(model, ignoreCase = true)
            if (!exact && !e.id.substringAfterLast('/').equals(bare, ignoreCase = true)) return null
            return (if (e.provider == preferProvider) 0 else 2) + (if (exact) 0 else 1)
        }
        return entries.mapNotNull { e -> rank(e)?.let { it to e } }.minByOrNull { it.first }?.second
    }

    /**
     * The base model of a local build such as `qwen3-8b-q4_k_m` or `lmstudio-community/qwen3-8b@q4_k_m`:
     * capabilities only (OR over every entry of that base), never a price or a window — the server's window wins.
     */
    fun base(localModel: String): ModelInfo? {
        val name = normaliseLocal(localModel)
        val hits = entries.filter { it.baseModel?.substringAfterLast('/')?.lowercase() == name }
        if (hits.isEmpty()) return null
        return ModelInfo(
            provider = "local", id = localModel, contextTokens = null, outputTokens = null,
            inputUsdPerM = null, outputUsdPerM = null, cacheReadUsdPerM = null,
            tools = hits.any { it.tools }, reasoning = hits.any { it.reasoning }, vision = hits.any { it.vision },
            baseModel = hits.first().baseModel,
        )
    }

    /** Every entry whose `provider/id` contains [query] (any case). */
    fun search(query: String): List<ModelInfo> =
        entries.filter { "${it.provider}/${it.id}".contains(query, ignoreCase = true) }

    companion object {
        const val SOURCE_URL = "https://models.dev/api.json"
        private val QUANT = Regex("""[-_.](q\d\w*|iq\d\w*|f16|bf16|fp8|int4|int8|mlx|\d+bit)$""")

        internal fun normaliseLocal(id: String): String {
            var s = id.lowercase().substringAfterLast('/').substringBefore('@').removeSuffix(".gguf").removeSuffix("-gguf")
            while (true) {
                val next = QUANT.replace(s, "")
                if (next == s) return s
                s = next
            }
        }

        /** Raw `https://models.dev/api.json` → the compact form. */
        fun trim(modelsDevJson: String): String {
            val root = Json.parseToJsonElement(modelsDevJson).jsonObject
            val out = root.mapNotNull { (provider, p) ->
                val models = (p as? JsonObject)?.get("models") as? JsonObject ?: return@mapNotNull null
                provider to JsonObject(models.mapNotNull { (id, m) -> (m as? JsonObject)?.let { id to compact(it) } }.toMap())
            }.toMap()
            return Json.encodeToString(JsonObject.serializer(), JsonObject(out))
        }

        private fun compact(m: JsonObject): JsonObject {
            val limit = m["limit"] as? JsonObject
            val cost = m["cost"] as? JsonObject
            val input = (m["modalities"] as? JsonObject)?.get("input")?.let { runCatching { it.jsonArray }.getOrNull() }
            val fields = buildMap<String, JsonElement> {
                limit?.num("context")?.let { put("c", it) }
                limit?.num("output")?.let { put("o", it) }
                cost?.num("input")?.let { put("i", it) }
                cost?.num("output")?.let { put("p", it) }
                cost?.num("cache_read")?.let { put("r", it) }
                if (m.flag("tool_call")) put("t", JsonPrimitive(1))
                if (m.flag("reasoning")) put("x", JsonPrimitive(1))
                if (input?.any { (it as? JsonPrimitive)?.content == "image" } == true) put("v", JsonPrimitive(1))
                (m["canonical_model_id"] as? JsonPrimitive)?.takeIf { it.isString }?.let { put("k", it) }
            }
            return JsonObject(fields)
        }

        private fun JsonObject.num(key: String): JsonPrimitive? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it.doubleOrNull != null }
        private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

        /** The compact form → a catalog. Throws when it isn't a JSON object. */
        fun parse(compactJson: String): ModelCatalog = fromObject(Json.parseToJsonElement(compactJson).jsonObject)

        private fun fromObject(root: JsonObject): ModelCatalog = ModelCatalog(root.flatMap { (provider, models) ->
            (models as? JsonObject).orEmpty().mapNotNull { (id, e) ->
                val f = e as? JsonObject ?: return@mapNotNull null
                fun d(k: String) = (f[k] as? JsonPrimitive)?.doubleOrNull
                fun i(k: String) = (f[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
                fun b(k: String) = (f[k] as? JsonPrimitive)?.intOrNull == 1
                ModelInfo(provider, id, i("c"), i("o"), d("i"), d("p"), d("r"), b("t"), b("x"), b("v"),
                    (f["k"] as? JsonPrimitive)?.content)
            }
        })

        private val bundledJson: JsonObject by lazy {
            ModelCatalog::class.java.getResourceAsStream("/dev/sophi/sdk/models.json.gz")?.use { s ->
                runCatching { Json.parseToJsonElement(GZIPInputStream(s).readBytes().decodeToString()).jsonObject }.getOrNull()
            } ?: JsonObject(emptyMap())
        }
        private val bundledCatalog: ModelCatalog by lazy { fromObject(bundledJson) }

        /** The snapshot shipped in the jar; empty when it's missing. */
        fun bundled(): ModelCatalog = bundledCatalog

        /** [cache] (or the snapshot when it's missing or broken), with [user]'s fields laid over it. */
        fun load(cache: Path?, user: Path?, onWarning: (String) -> Unit): ModelCatalog {
            fun read(f: Path?, warn: (String) -> String) = f?.takeIf { Files.exists(it) }?.let { p ->
                runCatching { Json.parseToJsonElement(Files.readString(p)).jsonObject }
                    .onFailure { onWarning(warn("${it.message?.lineSequence()?.first()}")) }.getOrNull()
            }
            val base = read(cache) { "model catalog: $cache isn't valid JSON ($it) — using the bundled snapshot" } ?: bundledJson
            val overlay = read(user) { "model catalog: ignoring $user, it isn't valid JSON ($it)" } ?: return if (base === bundledJson) bundledCatalog else fromObject(base)
            return fromObject(merge(base, overlay))
        }

        private fun merge(base: JsonObject, over: JsonObject): JsonObject = JsonObject(base + over.mapValues { (k, v) ->
            val b = base[k]
            if (b is JsonObject && v is JsonObject) merge(b, v) else v
        })

        /**
         * Fetches and writes [cache] when it's missing or older than [maxAge]; true when it wrote. Never throws.
         * A download with fewer than [minModels] models (models.dev changed shape) keeps the old file.
         */
        fun refreshIfStale(
            cache: Path,
            maxAge: Duration = Duration.ofDays(1),
            minModels: Int = 1000,
            fetch: () -> String = ::fetchModelsDev,
        ): Boolean {
            val fresh = runCatching {
                Files.exists(cache) && Files.getLastModifiedTime(cache).toInstant().isAfter(Instant.now().minus(maxAge))
            }.getOrDefault(false)
            if (fresh) return false
            return runCatching {
                val trimmed = trim(fetch())
                check(parse(trimmed).size() >= minModels) { "too few models" }
                writeAtomically(cache, trimmed.toByteArray())
            }.isSuccess
        }

        /** Writes via a temp file and an atomic move, so a killed write never leaves half a file. */
        fun writeAtomically(target: Path, bytes: ByteArray) {
            val dir = Files.createDirectories(target.toAbsolutePath().parent)
            val tmp = Files.createTempFile(dir, "models", ".tmp")
            try {
                Files.write(tmp, bytes)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

        fun fetchModelsDev(): String {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
            val res = http.send(HttpRequest.newBuilder(URI(SOURCE_URL)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            check(res.statusCode() in 200..299) { "models.dev answered ${res.statusCode()}" }
            return res.body()
        }
    }
}
