package dev.sophi.core.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class SessionEntryTest : FunSpec({
    val json = Json { ignoreUnknownKeys = true }

    test("SessionEntry round-trips through JSON with all fields") {
        val entry = SessionEntry(
            id = "abc123",
            parentId = null,
            role = EntryRole.USER,
            content = "Hello",
            timestamp = 1000L,
            metadata = mapOf("key" to "value")
        )
        val encoded = json.encodeToString(entry)
        val decoded = json.decodeFromString<SessionEntry>(encoded)
        decoded shouldBe entry
    }

    test("SessionEntry with parentId round-trips correctly") {
        val entry = SessionEntry(
            id = "child",
            parentId = "parent",
            role = EntryRole.ASSISTANT,
            content = "Hi there",
            timestamp = 2000L
        )
        val encoded = json.encodeToString(entry)
        val decoded = json.decodeFromString<SessionEntry>(encoded)
        decoded.parentId shouldBe "parent"
        decoded.metadata.shouldBeEmpty()
    }

    test("all four EntryRole values survive JSON round-trip") {
        EntryRole.entries.forEach { role ->
            val entry = SessionEntry("id", null, role, "content", 0L)
            val encoded = json.encodeToString(entry)
            val decoded = json.decodeFromString<SessionEntry>(encoded)
            decoded.role shouldBe role
        }
    }

    test("SessionEntry metadata defaults to empty map") {
        val entry = SessionEntry("id", null, EntryRole.SYSTEM, "sys", 0L)
        entry.metadata.shouldBeEmpty()
    }
})
