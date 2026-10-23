package dev.sophi.core.agent

import dev.sophi.ai.api.LLMProvider
import dev.sophi.ai.api.LLMResponse
import dev.sophi.ai.api.TokenUsage
import dev.sophi.ai.api.ToolCall
import dev.sophi.core.session.AgentSession
import dev.sophi.core.session.SessionEntry
import dev.sophi.core.session.SessionManager
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.DeleteFileTool
import dev.sophi.core.tools.EditTool
import dev.sophi.core.tools.FileWriteTool
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.ToolRegistry
import dev.sophi.core.undo.CheckpointStore
import dev.sophi.core.undo.FileRestorer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random

class AgentLoopCheckpointTest : FunSpec({
    val config = AgentConfig(model = "test-model", systemPrompt = "You are helpful.")

    fun use(id: String, name: String, args: String) =
        LLMResponse.ToolUse(listOf(ToolCall(id, name, args)), TokenUsage(1, 0))

    fun scripted(vararg rounds: LLMResponse): Pair<LLMProvider, SessionManager> {
        val provider = mockk<LLMProvider>()
        val sm = mockk<SessionManager>()
        var n = 0
        every { provider.stream(any()) } answers { rounds[(n++).coerceAtMost(rounds.size - 1)].toStreamFlow() }
        every { sm.save(any()) } just Runs
        return provider to sm
    }

    fun registry(root: Path, trash: Path, vararg extra: Tool) = ToolRegistry().apply {
        register(FileWriteTool(root)); register(EditTool(root)); register(BashTool(root))
        register(DeleteFileTool(root, trash, createTempDirectory("home")))
        extra.forEach { register(it) }
    }

    fun rounds(session: AgentSession): List<SessionEntry> = session.branch().filter { "toolCalls" in it.metadata }

    test("acceptance: edit, write and rm through tools, then undo restores every byte") {
        val root = createTempDirectory("ckpt-root")
        root.resolve("a.txt").writeText("alpha")
        root.resolve("b.txt").writeText("beta")
        root.resolve("keep.bin").writeBytes(Random.nextBytes(256))
        val originals = listOf("a.txt", "b.txt", "keep.bin").associateWith { root.resolve(it).readBytes() }
        val store = CheckpointStore(root, createTempDirectory("ckpt-home"))
        val (provider, sm) = scripted(
            use("c1", "edit_file", """{"path":"a.txt","old_string":"alpha","new_string":"ALPHA"}"""),
            use("c2", "write_file", """{"path":"new.txt","content":"fresh"}"""),
            use("c3", "bash", """{"command":"rm b.txt"}"""),
            use("c4", "delete_file", """{"path":"keep.bin"}"""),
            LLMResponse.Text("done", TokenUsage(1, 1)),
        )
        val loop = AgentLoop(provider, registry(root, createTempDirectory("trash")), sm,
            contextWindowTokens = 100_000, checkpoints = store)

        val session = loop.turn(AgentSession(id = "s"), "go", config)
        root.resolve("new.txt").exists() shouldBe true
        root.resolve("keep.bin").exists() shouldBe false

        var undone = 0
        val restorer = FileRestorer(store)
        while (true) {
            val plan = restorer.plan(session) ?: break
            restorer.apply(session, plan) shouldBe emptyList()
            undone++
        }

        undone shouldBe 4
        originals.forEach { (name, bytes) -> root.resolve(name).readBytes().toList() shouldBe bytes.toList() }
        root.resolve("new.txt").exists() shouldBe false
    }

    test("a round with only SAFE calls takes no checkpoint") {
        val root = createTempDirectory("ckpt-root")
        val safe = object : Tool {
            override val name = "look"
            override val description = "Looks"
            override val parametersJson = "{}"
            override suspend fun execute(argumentsJson: String) = "seen"
        }
        val (provider, sm) = scripted(use("c1", "look", "{}"), LLMResponse.Text("ok", TokenUsage(1, 1)))
        val loop = AgentLoop(provider, registry(root, createTempDirectory("trash"), safe), sm,
            contextWindowTokens = 100_000, checkpoints = CheckpointStore(root, createTempDirectory("ckpt-home")))

        val session = loop.turn(AgentSession(id = "s"), "go", config)

        rounds(session) shouldHaveSize 1
        rounds(session)[0].metadata shouldNotContainKey "checkpoint"
    }

    test("a delegate call that rates SAFE still takes a checkpoint") {
        val root = createTempDirectory("ckpt-root")
        val (provider, sm) = scripted(
            use("c1", "delegate_to_subagent", """{"subagent_type":"none","prompt":"edit things"}"""),
            LLMResponse.Text("ok", TokenUsage(1, 1)),
        )
        val registry = registry(root, createTempDirectory("trash"))
        registry.register(SubagentTool(emptyList(), provider, registry, sm, config, 100_000))
        val loop = AgentLoop(provider, registry, sm, contextWindowTokens = 100_000,
            checkpoints = CheckpointStore(root, createTempDirectory("ckpt-home")))

        val session = loop.turn(AgentSession(id = "s"), "go", config)

        rounds(session).single().metadata shouldContainKey "checkpoint"
    }

    test("a bash command reaching outside the root is recorded") {
        val root = createTempDirectory("ckpt-root")
        val store = CheckpointStore(root, createTempDirectory("ckpt-home"))
        val (provider, sm) = scripted(
            use("c1", "bash", """{"command":"cat /etc/hosts > out.txt"}"""),
            LLMResponse.Text("ok", TokenUsage(1, 1)),
        )
        val loop = AgentLoop(provider, registry(root, createTempDirectory("trash")), sm,
            contextWindowTokens = 100_000, checkpoints = store)

        val session = loop.turn(AgentSession(id = "s"), "go", config)

        val round = rounds(session).single()
        round.metadata shouldContainKey "checkpoint"
        round.metadata["outsideCommands"]!! shouldContain "cat /etc/hosts > out.txt"
        FileRestorer(store).plan(session)!!.warnings.shouldNotBeEmpty()
    }

    test("without a CheckpointStore the loop runs and records moves") {
        val root = createTempDirectory("ckpt-root")
        root.resolve("gone.txt").writeText("bye")
        val (provider, sm) = scripted(
            use("c1", "delete_file", """{"path":"gone.txt"}"""),
            LLMResponse.Text("ok", TokenUsage(1, 1)),
        )
        val loop = AgentLoop(provider, registry(root, createTempDirectory("trash")), sm, contextWindowTokens = 100_000)

        val session = loop.turn(AgentSession(id = "s"), "go", config)

        val round = rounds(session).single()
        round.metadata shouldContainKey "moves"
        round.metadata shouldNotContainKey "checkpoint"
    }
})
