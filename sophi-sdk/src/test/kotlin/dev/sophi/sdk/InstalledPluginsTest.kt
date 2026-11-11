package dev.sophi.sdk

import dev.sophi.extensions.SophiPlugin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class InstalledPluginsTest : FunSpec({
    /** Compiles a tiny SophiPlugin that exists only inside the jar (never on the test classpath). */
    fun pluginJar(out: Path, version: String) {
        val src = createTempDirectory("plugin-src")
        val srcFile = src.resolve("testplugin/JarPlugin.java").also { it.parent.createDirectories() }
        srcFile.writeText(
            """
            package testplugin;
            public class JarPlugin implements dev.sophi.extensions.SophiPlugin {
                public String getName() { return "jar-plugin"; }
                public String getVersion() { return "$version"; }
                public java.util.List<dev.sophi.extensions.AgentHook> hooks() { return java.util.List.of(); }
            }
            """.trimIndent()
        )
        val cp = listOf(SophiPlugin::class.java, Unit::class.java)
            .joinToString(java.io.File.pathSeparator) { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", cp, "-d", src.toString(), srcFile.toString()) shouldBe 0
        JarOutputStream(out.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("testplugin/JarPlugin.class"))
            jar.write(src.resolve("testplugin/JarPlugin.class").readBytes())
            jar.putNextEntry(JarEntry("META-INF/services/dev.sophi.extensions.SophiPlugin"))
            jar.write("testplugin.JarPlugin\n".toByteArray())
        }
        src.toFile().deleteRecursively()
    }

    fun git(repo: Path, vararg args: String): String {
        val p = ProcessBuilder("git", "-C", repo.toString(), "-c", "user.email=t@e.com", "-c", "user.name=T", *args)
            .redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    }

    fun commitJar(repo: Path, version: String): String {
        pluginJar(repo.resolve("plugin.jar"), version)
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", version)
        return git(repo, "rev-parse", "HEAD")
    }

    lateinit var repo: Path
    lateinit var home: Path
    lateinit var source: String
    beforeTest {
        repo = createTempDirectory("plugin-repo").resolve("hello-plugin").createDirectories()
        ProcessBuilder("git", "init", "-q", repo.toString()).start().waitFor()
        home = createTempDirectory("plugins-home")
        source = "git:file://${repo.toAbsolutePath()}"
    }
    afterTest {
        repo.parent.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }

    test("shows the repo, exact commit and declared classes, pins them, and loads the jar's plugin") {
        val head = commitJar(repo, "1.0.0")
        var shown: InstalledPlugins.Preview? = null

        val pin = InstalledPlugins.install(source, home) { shown = it; true }

        shown!!.name shouldBe "hello-plugin"
        shown!!.commit shouldBe head
        shown!!.declares shouldBe listOf("testplugin.JarPlugin")
        shown!!.describe() shouldContain "trusted code"
        pin!!.commit shouldBe head
        InstalledPlugins.list(home).getValue("hello-plugin").commit shouldBe head
        val loaded = InstalledPlugins.load(home)
        loaded.map { it.name to it.version } shouldBe listOf("jar-plugin" to "1.0.0")
        loaded.single().javaClass.classLoader shouldNotBe SophiPlugin::class.java.classLoader
    }

    test("installs nothing unless confirmed") {
        commitJar(repo, "1.0.0")
        InstalledPlugins.install(source, home) { false } shouldBe null
        home.resolve("hello-plugin").exists() shouldBe false
        InstalledPlugins.list(home) shouldBe emptyMap()
    }

    test("a ref pins the commit it resolves to, and a moved ref changes nothing without --update") {
        val first = commitJar(repo, "1.0.0")
        git(repo, "tag", "v1")
        InstalledPlugins.install("$source#v1", home) { true }!!.commit shouldBe first

        val second = commitJar(repo, "2.0.0")
        git(repo, "tag", "-f", "v1")
        shouldThrow<IllegalStateException> { InstalledPlugins.install("$source#v1", home) { true } }.message shouldContain "--update"
        InstalledPlugins.load(home).single().version shouldBe "1.0.0"

        var shown: InstalledPlugins.Preview? = null
        InstalledPlugins.install("$source#v1", home, update = true) { shown = it; true }
        shown!!.replaces!!.commit shouldBe first
        shown!!.commit shouldBe second
        InstalledPlugins.load(home).single().version shouldBe "2.0.0"
    }

    test("refuses to load a jar that is unpinned or changed since install") {
        commitJar(repo, "1.0.0")
        InstalledPlugins.install(source, home) { true }
        val refused = mutableListOf<String>()
        home.resolve("hello-plugin/plugin.jar").writeBytes(repo.resolve("plugin.jar").readBytes() + 0)
        home.resolve("stray").createDirectories().resolve("plugin.jar").writeBytes(repo.resolve("plugin.jar").readBytes())

        InstalledPlugins.load(home) { refused += it }.shouldBeEmpty()

        refused.size shouldBe 2
        refused.joinToString() shouldContain "changed since it was installed"
        refused.joinToString() shouldContain "no pin"
    }

    test("refuses a repo with no prebuilt jar and never builds it") {
        repo.resolve("build.gradle").writeText("throw new RuntimeException()")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "src")
        shouldThrow<IllegalStateException> { InstalledPlugins.install(source, home) { true } }.message shouldContain "never builds"
    }

    test("remove deletes the jar and its pin") {
        commitJar(repo, "1.0.0")
        InstalledPlugins.install(source, home) { true }
        InstalledPlugins.remove(home, "hello-plugin") shouldBe true
        home.resolve("hello-plugin").exists() shouldBe false
        InstalledPlugins.list(home) shouldBe emptyMap()
        InstalledPlugins.remove(home, "hello-plugin") shouldBe false
    }
})
