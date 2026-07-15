package dev.sophi.memory.jane

import dev.sophi.memory.FakeEmbeddingProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class RoomRouterTest : FunSpec({
    val fake = FakeEmbeddingProvider()
    test("routes a task-flavored query to TASKS among the top rooms") {
        val router = RoomRouter(fake)
        // FakeEmbeddingProvider matches on exact tokens — use the descriptor's plural forms.
        val query = fake.embed(listOf("appointments deadlines reminders errands todo")).single()
        val rooms = router.route(query, topK = 3)
        rooms shouldHaveSize 3
        (Room.TASKS in rooms) shouldBe true
    }
    test("returns exactly topK distinct rooms") {
        val router = RoomRouter(fake)
        val query = fake.embed(listOf("anything at all")).single()
        router.route(query, topK = 2) shouldHaveSize 2
    }
})
