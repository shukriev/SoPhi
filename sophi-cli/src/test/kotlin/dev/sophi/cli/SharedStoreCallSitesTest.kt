package dev.sophi.cli

import com.github.ajalt.clikt.core.CliktError
import dev.sophi.store.arcade.IncompatibleHostException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import java.nio.file.Files
import java.nio.file.Path

class SharedStoreCallSitesTest : FunSpec({
    test("a store that cannot be opened becomes one clear CLI error, not a stack trace") {
        val e = shouldThrow<CliktError> {
            openStore { throw IncompatibleHostException("an older Sophi holds the legacy dir") }
        }
        e.message shouldContain "an older Sophi holds the legacy dir"
    }

    test("no default-home store is built directly (it must go through VersioningStores)") {
        val roots = listOf(Path.of("src/main"), Path.of("../sophi-sdk/src/main"))
        val banned = Regex("""(Version|Scorecard)Store\((Path\.of\(versioningHomeStr\)|memoryVersioningHome\(\)|versioningHome)\)""")
        val hits = roots.filter { Files.exists(it) }.flatMap { root ->
            Files.walk(root).use { s ->
                s.filter { it.toString().endsWith(".kt") }.toList().flatMap { f ->
                    Files.readAllLines(f).withIndex().filter { banned.containsMatchIn(it.value) }.map { "$f:${it.index + 1}" }
                }
            }
        }
        hits shouldBe emptyList()
    }
})
