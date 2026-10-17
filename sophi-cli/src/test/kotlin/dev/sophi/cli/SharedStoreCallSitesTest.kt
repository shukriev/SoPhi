package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.testing.test
import dev.sophi.store.arcade.IncompatibleHostException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class SharedStoreCallSitesTest : FunSpec({
    test("a store that cannot be opened becomes one clear CLI error, not a stack trace") {
        val e = shouldThrow<CliktError> {
            openStore { throw IncompatibleHostException("an older Sophi holds the legacy dir") }
        }
        e.message shouldBe "Cannot open the shared store: an older Sophi holds the legacy dir"
    }

    test("a null message falls back to the exception class name, never 'null'") {
        val e = shouldThrow<CliktError> { openStore { throw IllegalStateException() } }
        e.message shouldContain "IllegalStateException"
        e.message!! shouldNotContain "null"
    }

    test("other exceptions are not wrapped") {
        shouldThrow<RuntimeException> { openStore { throw RuntimeException("bug") } }.message shouldBe "bug"
    }

    test("real commands reach openStore: a failing store is one error line and a non-zero exit") {
        // Temp homes are always embedded, so no real home can raise the shared-store error; inject it through the seam.
        val original = versionStoreFactory
        versionStoreFactory = { throw IncompatibleHostException("an older Sophi holds the legacy dir") }
        try {
            val home = createTempDirectory("shared-cmd-test")
            for (cmd in listOf(TournamentStatusCommand(), ConfigSeedCommand(), VersionsListCommand())) {
                val args = if (cmd is VersionsListCommand) "config default" else ""
                val result = cmd.test("$args --versioning-home $home".trim().let { if (cmd is VersionsListCommand) args else it })
                result.statusCode shouldBe 1
                result.stderr shouldContain "Cannot open the shared store: an older Sophi holds the legacy dir"
                result.output shouldNotContain "at dev.sophi"
            }
        } finally { versionStoreFactory = original }
    }

    test("no default-home store is built directly (every VersionStore/ScorecardStore construction is on the skills allowlist)") {
        // Only skills/lessons/agents `.versions` homes may construct stores directly; everything else goes through VersioningStores.
        val roots = listOf(Path.of("src/main"), Path.of("../sophi-sdk/src/main"))
        val construction = Regex("""\b(Version|Scorecard)Store\(""")
        // Skills sites only: file name to trimmed line. Anything else (e.g. a memory `.versions` home) must use VersioningStores.
        val allowed = setOf(
            "SkillVerifyCommand.kt" to """val versionStore = VersionStore(skillsHome.resolve(".versions"))""",
            "SkillVerifyCommand.kt" to """all -> SkillVersionStore(VersionStore(skillsHome.resolve(".versions")), project)""",
            "SkillReviewCommand.kt" to """VersionStore(skillsHome.resolve(".versions")), project = false,""",
            "SkillReviewCommand.kt" to """VersionStore(projectSkillsHome.resolve(".versions")), project = true,""",
            "AutoSkillStore.kt" to """VersionStore(dir.resolve(".versions")), project = false, legacyJsonlPath = dir.resolve(".versions.jsonl")""",
            "InstallSkillTool.kt" to """VersionStore(targetDir.resolve(".versions")), args.project,""",
            "SkillVerification.kt" to """val versionStore = SkillVersionStore(VersionStore(skillsHome.resolve(".versions")), project)""",
            "WriteSkillTool.kt" to """VersionStore(targetDir.resolve(".versions")), args.project,"""
        )
        val declaration = Regex("""\b(class|interface|object)\s+(Version|Scorecard)Store\b""")
        val hits = roots.flatMap { root ->
            val files = Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
            (files.isNotEmpty()) shouldBe true
            files.flatMap { f ->
                Files.readAllLines(f).withIndex().filter {
                    construction.containsMatchIn(it.value) && !declaration.containsMatchIn(it.value) &&
                        (f.fileName.toString() to it.value.trim()) !in allowed
                }.map { "$f:${it.index + 1}: ${it.value.trim()}" }
            }
        }
        hits shouldBe emptyList()
    }
})
