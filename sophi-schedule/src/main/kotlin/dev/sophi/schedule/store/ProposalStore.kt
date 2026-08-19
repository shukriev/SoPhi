package dev.sophi.schedule.store

import dev.sophi.schedule.model.Proposal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

class ProposalStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fold(): Map<String, Proposal> =
        if (!Files.exists(path)) emptyMap()
        else Files.readAllLines(path).filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<Proposal>(it) }.getOrNull() }
            .associateBy { it.id }

    fun list(status: String? = null): List<Proposal> =
        fold().values.filter { status == null || it.status == status }.sortedByDescending { it.ts }

    fun get(id: String): Proposal? = fold()[id]

    fun add(proposal: Proposal): Proposal {
        append(proposal)
        return proposal
    }

    fun accept(id: String): Boolean =
        transition(id) { it.copy(status = "accepted", reviewedAtMs = System.currentTimeMillis()) }

    fun reject(id: String, reason: String): Boolean =
        transition(id) { it.copy(status = "rejected", reviewedAtMs = System.currentTimeMillis(), reviewReason = reason) }

    private fun transition(id: String, update: (Proposal) -> Proposal): Boolean {
        val current = fold()[id] ?: return false
        if (current.status != "pending") return false
        append(update(current))
        return true
    }

    private fun append(p: Proposal) {
        path.parent?.let { Files.createDirectories(it) }
        val line = json.encodeToString(p).replace("\n", " ")
        Files.write(path, (line + "\n").toByteArray(), CREATE, APPEND)
    }
}
