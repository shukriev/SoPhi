package dev.sophi.context

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class TokenizerTest : FunSpec({
    test("splits camelCase and keeps the joined identifier") {
        Tokenizer.tokens("ContextCompactor") shouldContainAll listOf("contextcompactor", "context", "compactor")
    }
    test("splits snake_case, paths and letter-digit boundaries") {
        Tokenizer.tokens("sophi-core/src/glob_tool.kt v2Api") shouldContainAll listOf("sophi", "core", "glob", "tool", "kt", "v2api", "api")
    }
    test("stems and lowercases") {
        Tokenizer.tokens("Compacts compacting") shouldBe listOf("compact", "compact")
    }
    test("drops stop words, keyword noise and one-letter tokens") {
        val t = Tokenizer.tokens("how do I use the val fun return a x")
        t shouldNotContain "the"; t shouldNotContain "val"; t shouldNotContain "x"
        t shouldBe emptyList()
    }
    test("query and index text tokenize the same way") {
        Tokenizer.indexText("core/HubPort.kt", "fun hubPort()").split(" ") shouldContain "hub"
        Tokenizer.tokens("hub port") shouldBe listOf("hub", "port")
    }
    test("is safe to call from several threads") {
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..8).map {
            Thread {
                try { repeat(200) { check(Tokenizer.tokens("compacting sessions") == listOf("compact", "session")) } }
                catch (t: Throwable) { failures.add(t) }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        failures.toList() shouldBe emptyList()
    }
})
