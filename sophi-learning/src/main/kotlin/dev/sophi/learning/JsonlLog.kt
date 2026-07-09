package dev.sophi.learning

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

class JsonlLog(private val path: Path) {
    @Synchronized
    fun append(json: String) {
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, (json.replace("\n", " ") + "\n").toByteArray(), CREATE, APPEND)
    }

    fun readAll(): List<String> =
        if (!Files.exists(path)) emptyList()
        else Files.readAllLines(path).filter { it.isNotBlank() }

    fun readLast(n: Int): List<String> = readAll().takeLast(n)
}
