package dev.sophi.learning.export

import dev.sophi.core.session.EntryRole
import dev.sophi.core.session.SessionEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

class DpoBuilderTest : FunSpec({
    fun e(role: EntryRole, content: String, meta: Map<String, String> = emptyMap()) =
        SessionEntry("id-$content".take(24), null, role, content, 1L, meta)
    val entries = listOf(
        e(EntryRole.USER, "write a commit message"),
        e(EntryRole.ASSISTANT, "Updated stuff."),            // index 1 = rejected
        e(EntryRole.USER, "no, conventional commits please"),
        e(EntryRole.ASSISTANT, "feat(core): add X"))         // index 3 = chosen

    test("clean pair: prompt excludes rejected and the correction") {
        val line = DpoBuilder { it }.build(entries, null, rejectedIndex = 1, chosenIndex = 3)!!
        val obj = Json.parseToJsonElement(line).jsonObject
        obj.getValue("prompt").jsonArray.size shouldBe 1     // just the original user ask
        obj.getValue("rejected").jsonArray[0].jsonObject.getValue("content")
            .jsonPrimitive.content shouldBe "Updated stuff."
        obj.getValue("chosen").jsonArray[0].jsonObject.getValue("content")
            .jsonPrimitive.content shouldBe "feat(core): add X"
    }

    test("tool round between rejected and chosen makes the pair unpairable") {
        val withTools = entries.toMutableList()
        withTools.add(2, e(EntryRole.TOOL_RESULT, "x", mapOf("replay" to "false", "toolName" to "t")))
        DpoBuilder { it }.build(withTools, null, 1, 4).shouldBeNull()
    }

    test("rejected index pointing at a non-assistant entry is unpairable") {
        DpoBuilder { it }.build(entries, null, 0, 3).shouldBeNull()
    }

    test("an assistant entry carrying a tool call at the boundary is unpairable, not a plain reply") {
        val withToolCallAtChosen = entries.dropLast(1) + e(EntryRole.ASSISTANT, "",
            mapOf("toolCalls" to """[{"id":"c1","name":"bash","argumentsJson":"{}"}]"""))
        DpoBuilder { it }.build(withToolCallAtChosen, null, rejectedIndex = 1, chosenIndex = 3).shouldBeNull()
    }

    test("an out-of-bounds index is unpairable rather than throwing") {
        DpoBuilder { it }.build(entries, null, rejectedIndex = 1, chosenIndex = 99).shouldBeNull()
        DpoBuilder { it }.build(entries, null, rejectedIndex = -1, chosenIndex = 3).shouldBeNull()
    }
})
