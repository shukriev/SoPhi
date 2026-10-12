package dev.sophi.store.arcade

import com.arcadedb.ContextConfiguration
import com.arcadedb.GlobalConfiguration
import com.arcadedb.database.DatabaseFactory
import com.arcadedb.remote.RemoteDatabase
import com.arcadedb.server.ArcadeDBServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Pins the ArcadeDB 26.5.1 behaviour the shared-store design depends on — verified first in the
 * 2026-10-12 spike (spec §3.1). If an upgrade breaks one of these, the design needs revisiting,
 * not just the code.
 */
class ArcadeServerFactsTest : FunSpec({

    fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    fun startServer(root: Path, port: Int, password: String = "facts-pass-1"): ArcadeDBServer {
        useSophiArcadeLogConfig()
        val cfg = ContextConfiguration().apply {
            setValue(GlobalConfiguration.SERVER_ROOT_PATH, root.toString())
            setValue(GlobalConfiguration.SERVER_DATABASE_DIRECTORY, root.resolve("databases").toString())
            setValue(GlobalConfiguration.SERVER_ROOT_PASSWORD, password)
            setValue(GlobalConfiguration.SERVER_HTTP_INCOMING_HOST, "127.0.0.1")
            setValue(GlobalConfiguration.SERVER_HTTP_INCOMING_PORT, port.toString())
            setValue(GlobalConfiguration.SERVER_PLUGINS, "")
        }
        return ArcadeDBServer(cfg).also { it.start() }
    }

    test("FULL_TEXT index works and \$score orders by matched terms, with an extra filter") {
        val db = DatabaseFactory(tempdir().resolve("ft").toString()).create()
        try {
            db.command("sql", "CREATE DOCUMENT TYPE Doc")
            db.command("sql", "CREATE PROPERTY Doc.text STRING")
            db.command("sql", "CREATE PROPERTY Doc.project STRING")
            db.command("sql", "CREATE INDEX ON Doc (text) FULL_TEXT")
            db.transaction {
                db.command("sql", "INSERT INTO Doc SET text = 'compactor session window', project = 'p1'")
                db.command("sql", "INSERT INTO Doc SET text = 'compactor only', project = 'p1'")
                db.command("sql", "INSERT INTO Doc SET text = 'compactor session', project = 'p2'")
            }
            val rows = db.query(
                "sql",
                "SELECT text, \$score AS score FROM Doc WHERE SEARCH_INDEX('Doc[text]', ?) = true AND project = ? ORDER BY \$score DESC",
                "compactor session", "p1"
            ).use { rs -> generateSequence { if (rs.hasNext()) rs.next() else null }.toList() }
            rows.map { it.getProperty<String>("text") } shouldBe listOf("compactor session window", "compactor only")
            rows.first().getProperty<Number>("score").toDouble() shouldBe 2.0
        } finally { db.close() }
    }

    test("server on a chosen loopback port: remote vectorNeighbors with List<Float> returns id + distance; / is not served") {
        val root = tempdir().toPath()
        val port = freePort()
        val server = startServer(root, port)
        try {
            val db = server.getOrCreateDatabase("facts")
            db.command("sql", "CREATE VERTEX TYPE V")
            db.command("sql", "CREATE PROPERTY V.id STRING")
            db.command("sql", "CREATE PROPERTY V.emb ARRAY_OF_FLOATS")
            db.command("sql", "CREATE INDEX ON V (emb) LSM_VECTOR METADATA { dimensions: 3, similarity: 'COSINE' }")
            db.transaction {
                db.command("sql", "CREATE VERTEX V SET id = 'a', emb = ?", floatArrayOf(1f, 0f, 0f))
                db.command("sql", "CREATE VERTEX V SET id = 'b', emb = ?", floatArrayOf(0f, 1f, 0f))
            }
            val remote = RemoteDatabase("127.0.0.1", port, "facts", "root", "facts-pass-1")
            try {
                val rows = remote.query("sql", "SELECT expand(vectorNeighbors('V[emb]', ?, 2))", listOf(1f, 0f, 0f))
                    .use { rs -> generateSequence { if (rs.hasNext()) rs.next() else null }.toList() }
                rows.first().getProperty<String>("id") shouldBe "a"
                rows.first().getProperty<Number>("distance").toDouble() shouldBeLessThan 0.01
            } finally { remote.close() }

            val status = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode()
            status shouldNotBe 200
            Files.exists(root.resolve("config").resolve("server-users.jsonl")) shouldBe true
        } finally { server.stop() }
    }

    test("server restarts on the same root with the same password") {
        val root = tempdir().toPath()
        startServer(root, freePort()).stop()
        val port = freePort()
        val server = startServer(root, port)
        try {
            server.getOrCreateDatabase("facts")
            val remote = RemoteDatabase("127.0.0.1", port, "facts", "root", "facts-pass-1")
            try { remote.query("sql", "SELECT 1 AS one").close() } finally { remote.close() }
        } finally { server.stop() }
    }

    test("vectors set at insert time are indexed without a manual put, at 20k records") {
        val db = DatabaseFactory(tempdir().resolve("vec").toString()).create()
        try {
            db.command("sql", "CREATE VERTEX TYPE V")
            db.command("sql", "CREATE PROPERTY V.id STRING")
            db.command("sql", "CREATE PROPERTY V.emb ARRAY_OF_FLOATS")
            db.command("sql", "CREATE INDEX ON V (emb) LSM_VECTOR METADATA { dimensions: 64, similarity: 'COSINE' }")
            val rnd = Random(42)
            val vectors = List(20_000) { FloatArray(64) { rnd.nextFloat() - 0.5f } }
            vectors.chunked(1_000).forEachIndexed { b, batch ->
                db.transaction {
                    batch.forEachIndexed { i, v -> db.command("sql", "CREATE VERTEX V SET id = ?, emb = ?", "v${b * 1_000 + i}", v) }
                }
            }
            val target = vectors[12_345]
            val rows = db.query("sql", "SELECT expand(vectorNeighbors('V[emb]', ?, 5))", target)
                .use { rs -> generateSequence { if (rs.hasNext()) rs.next() else null }.toList() }
            rows.size shouldBeGreaterThan 0
            rows.first().getProperty<String>("id") shouldBe "v12345"
        } finally { db.close() }
    }
})
