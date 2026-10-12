package dev.sophi.store.arcade

import java.nio.file.Files
import java.nio.file.Path

/** Child process for [ArcadeHostTwoProcessTest]: hosts the store, writes one note, signals ready, idles. */
object HostProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val ready = Path.of(args[1])
        val host = ArcadeHost.open(root, database = "ctx", schemaVersion = 1, engineVersion = "child")
        check(host.role == Role.HOST) { "child expected to host, was ${host.role}" }
        host.withStore {
            it.ensureSchema(documentTypes = listOf("Note"))
            it.upsertDocument("Note", "from-host", mapOf("who" to "child"))
        }
        Files.writeString(ready, "ok")
        Thread.sleep(Long.MAX_VALUE)
    }
}
