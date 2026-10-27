package dev.sophi.learning

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.attribute.PosixFilePermissions

class JsonlLog(private val path: Path) {
    private var ownerOnly = false

    @Synchronized
    fun append(json: String) {
        path.parent?.let { Files.createDirectories(it) }
        // Tool output, lessons and outcomes: readable by the owner only, the same as session files. An existing
        // log is tightened once per instance, so files created by older versions are fixed too.
        if (!ownerOnly) { restrictToOwner(path); ownerOnly = true }
        Files.write(path, (json.replace("\n", " ") + "\n").toByteArray(), CREATE, APPEND)
    }

    fun readAll(): List<String> =
        if (!Files.exists(path)) emptyList()
        else Files.readAllLines(path).filter { it.isNotBlank() }

    fun readLast(n: Int): List<String> = readAll().takeLast(n)
}

private fun restrictToOwner(path: Path) {
    val perms = PosixFilePermissions.fromString("rw-------")
    try {
        if (Files.exists(path)) Files.setPosixFilePermissions(path, perms)
        else Files.createFile(path, PosixFilePermissions.asFileAttribute(perms))
    } catch (_: UnsupportedOperationException) {
        // Not a POSIX file system (Windows): the user profile's ACLs already apply.
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        // Another JsonlLog on the same path created it between the check and here, owner-only as well.
    }
}
