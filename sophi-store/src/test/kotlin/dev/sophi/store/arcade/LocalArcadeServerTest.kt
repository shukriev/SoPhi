package dev.sophi.store.arcade

import com.arcadedb.remote.RemoteDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class LocalArcadeServerTest : FunSpec({

    fun canLogin(port: Int, password: String) = runCatching {
        val remote = RemoteDatabase("127.0.0.1", port, "t", LocalArcadeServer.USER, password)
        try { remote.query("sql", "SELECT 1 AS one").close() } finally { remote.close() }
    }.isSuccess

    test("starts on loopback, creates a 0600 secret, and serves the database remotely") {
        val root = tempdir().toPath()
        LocalArcadeServer.start(root).use { srv ->
            srv.database("t")
            canLogin(srv.port, srv.password) shouldBe true
            PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("secret"))) shouldBe "rw-------"
            canLogin(srv.port, "wrong-password") shouldBe false
        }
    }

    test("restart on the same root reuses the secret") {
        val root = tempdir().toPath()
        val first = LocalArcadeServer.start(root).use { it.password }
        LocalArcadeServer.start(root).use { srv ->
            srv.password shouldBe first
            srv.database("t")
            canLogin(srv.port, first) shouldBe true
        }
    }

    test("regenerated secret still authenticates") {
        val root = tempdir().toPath()
        val old = LocalArcadeServer.start(root).use { it.password }
        Files.delete(root.resolve("secret"))
        LocalArcadeServer.start(root).use { srv ->
            srv.password shouldNotBe old
            srv.database("t")
            canLogin(srv.port, srv.password) shouldBe true
        }
    }

    test("two servers on different roots get different ports") {
        LocalArcadeServer.start(tempdir().toPath()).use { a ->
            LocalArcadeServer.start(tempdir().toPath()).use { b -> a.port shouldNotBe b.port }
        }
    }
})
