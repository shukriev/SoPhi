package dev.sophi.store.arcade

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteArcadeStoreConcurrencyTest : FunSpec({

    test("8 threads x 50 upsertDocument on one RemoteArcadeStore all succeed") {
        LocalArcadeServer.start(tempdir().toPath()).use { srv ->
            srv.database("t")
            RemoteArcadeStore.connect(srv.port, "t", srv.password).use { store ->
                store.ensureSchema(documentTypes = listOf("Note"))
                val pool = Executors.newFixedThreadPool(8)
                val futures = (0 until 8).map { t ->
                    pool.submit<Int> {
                        (0 until 50).count { i ->
                            runCatching { store.upsertDocument("Note", "n-$t-$i", mapOf("v" to i)) }.isSuccess
                        }
                    }
                }
                val ok = futures.sumOf { it.get(120, TimeUnit.SECONDS) }
                pool.shutdown()
                ok shouldBe 400
                store.documents("Note").size shouldBe 400
            }
        }
    }
})
