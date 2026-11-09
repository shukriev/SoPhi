package dev.sophi.core.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

// Tests that need the sqlite3 binary are skipped (reported as ignored) when it is not on PATH.
private val hasSqlite = runCatching { ProcessBuilder("sqlite3", "-version").start().waitFor() == 0 }.getOrDefault(false)

private fun sqlite(db: Path, sql: String) {
    val p = ProcessBuilder("sqlite3", db.toString(), sql).redirectErrorStream(true).start()
    check(p.waitFor() == 0) { p.inputStream.bufferedReader().readText() }
}

class SqliteShowToolTest : FunSpec({
    test("without sqlite3 the tool says so") {
        val root = tempdir().toPath()
        root.resolve("x.db").writeText("SQLite format 3\u0000rest")
        SqliteShowTool(root, sqlite3 = "no-such-sqlite3-binary").execute("""{"path":"x.db"}""") shouldStartWith
            "Error: sqlite3 is not installed"
    }

    test("a file that isn't a SQLite database is refused") {
        val root = tempdir().toPath()
        root.resolve("notes.db").writeText("just text")
        SqliteShowTool(root).execute("""{"path":"notes.db"}""") shouldStartWith "Error: not a SQLite database"
        SqliteShowTool(root).execute("""{"path":"."}""") shouldStartWith "Error: "
        SqliteShowTool(root).execute("""{"path":"missing.db"}""") shouldStartWith "Error: "
    }

    test("read-only, path is the permission subject, deferred") {
        val root = tempdir().toPath()
        val tool = SqliteShowTool(root)
        tool.riskLevel("""{"path":"a.db"}""") shouldBe RiskLevel.SAFE
        tool.effects("""{"path":"a.db"}""") shouldBe emptySet()
        tool.permissionSubjects("""{"path":"a.db"}""")!!.single().text shouldBe "a.db"
        tool.deferred shouldBe true
    }

    run {
        fun fixture(): Pair<Path, Path> {
            val root = tempdir().toPath()
            val db = root.resolve("app.db")
            sqlite(db, """
                CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT, bio TEXT);
                INSERT INTO users(name, bio) VALUES ('alice', NULL), ('bob', 'line1
                line2'), ('carol', '${"x".repeat(500)}');
                CREATE VIEW names AS SELECT name FROM users;
                CREATE TABLE empty(a, b);
            """.trimIndent())
            return root to db
        }

        test("path alone lists tables and views with schema and row counts").config(enabled = hasSqlite) {
            val (root, _) = fixture()
            val out = SqliteShowTool(root).execute("""{"path":"app.db"}""")
            out shouldContain "table users (3 rows)"
            out shouldContain "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT, bio TEXT)"
            out shouldContain "table empty (0 rows)"
            out shouldContain "view names"
            out shouldContain "CREATE VIEW names AS SELECT name FROM users"
        }

        test("path and table show the columns and a page of rows, long cells cut").config(enabled = hasSqlite) {
            val (root, _) = fixture()
            val out = SqliteShowTool(root).execute("""{"path":"app.db","table":"users","limit":2}""")
            out shouldContain "rows 1-2 of 3"
            out shouldContain "id | name | bio"
            out shouldContain "1 | alice | NULL"
            out shouldContain "2 | bob | line1\\nline2"
            out shouldNotContain "carol"
            val third = SqliteShowTool(root).execute("""{"path":"app.db","table":"users","offset":2}""")
            third shouldContain "rows 3-3 of 3"
            third shouldContain "3 | carol | ${"x".repeat(200)}…[+300 chars]"
            third shouldNotContain "alice"
            SqliteShowTool(root).execute("""{"path":"app.db","table":"empty"}""") shouldContain "a | b"
        }

        test("a table that isn't in the database is refused, so no SQL gets through").config(enabled = hasSqlite) {
            val (root, _) = fixture()
            for (t in listOf("nope", "users; DROP TABLE users", "users\" --", "sqlite_master")) {
                SqliteShowTool(root).execute("""{"path":"app.db","table":${kotlinx.serialization.json.JsonPrimitive(t)}}""") shouldStartWith
                    "Error: no table or view"
            }
        }

        test("a database outside the workspace is refused, also through a symlink").config(enabled = hasSqlite) {
            val (outside, _) = fixture()
            val root = tempdir().toPath()
            SqliteShowTool(root).execute("""{"path":"${outside.resolve("app.db")}"}""") shouldStartWith "Error: "
            SqliteShowTool(root).execute("""{"path":"../${outside.fileName}/app.db"}""") shouldStartWith "Error: "
            root.resolve("link.db").createSymbolicLinkPointingTo(outside.resolve("app.db"))
            SqliteShowTool(root).execute("""{"path":"link.db"}""") shouldStartWith "Error: "
        }

        test("the database is not modified").config(enabled = hasSqlite) {
            val (root, db) = fixture()
            val bytes = db.readBytes()
            val mtime = db.getLastModifiedTime()
            SqliteShowTool(root).execute("""{"path":"app.db"}""")
            SqliteShowTool(root).execute("""{"path":"app.db","table":"users"}""")
            db.readBytes() shouldBe bytes
            db.getLastModifiedTime() shouldBe mtime
            Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() } shouldBe listOf("app.db")
        }
    }
})
