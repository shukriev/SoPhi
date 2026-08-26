package dev.sophi.core.agent.eval

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class EvalCaseTest : FunSpec({
    test("loadEvalCases loads one case per YAML file, categorized by its parent directory name") {
        val evalsDir = createTempDirectory("evals-test")
        val categoryDir = evalsDir.resolve("category-a").also { it.createDirectories() }
        categoryDir.resolve("case-1.yaml").writeText(
            "id: case-1\ngoalPrompt: \"do the thing\"\ncheck:\n  command: \"exit 0\"\n"
        )

        val cases = loadEvalCases(evalsDir)

        cases shouldHaveSize 1
        cases.first().id shouldBe "case-1"
        cases.first().category shouldBe "category-a"
        cases.first().scenario.goalPrompt shouldBe "do the thing"
        cases.first().scenario.check.command shouldBe "exit 0"
    }

    test("loadEvalCases loads cases from multiple category directories") {
        val evalsDir = createTempDirectory("evals-test")
        evalsDir.resolve("category-a").also { it.createDirectories() }
            .resolve("case-1.yaml").writeText("id: case-1\ngoalPrompt: \"g1\"\ncheck:\n  command: \"exit 0\"\n")
        evalsDir.resolve("category-b").also { it.createDirectories() }
            .resolve("case-2.yaml").writeText("id: case-2\ngoalPrompt: \"g2\"\ncheck:\n  command: \"exit 0\"\n")

        val cases = loadEvalCases(evalsDir)

        cases.map { it.category }.toSet() shouldBe setOf("category-a", "category-b")
    }

    test("loadEvalCases returns an empty list for a directory with no category subdirectories") {
        val evalsDir = createTempDirectory("evals-test")

        loadEvalCases(evalsDir) shouldBe emptyList()
    }

    test("loadEvalCases respects an explicit maxIterations when given, else EvalScenario's own default") {
        val evalsDir = createTempDirectory("evals-test")
        val categoryDir = evalsDir.resolve("category-a").also { it.createDirectories() }
        categoryDir.resolve("case-1.yaml").writeText(
            "id: case-1\ngoalPrompt: \"g\"\ncheck:\n  command: \"exit 0\"\nmaxIterations: 3\n"
        )

        loadEvalCases(evalsDir).first().scenario.maxIterations shouldBe 3
    }
})
