package dev.sophi.sdk

import dev.sophi.extensions.PluginRegistry
import dev.sophi.extensions.SophiPlugin
import dev.sophi.skills.SkillInstaller
import dev.sophi.skills.SkillLock
import dev.sophi.skills.SkillPin
import java.net.URLClassLoader
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Plugins installed from git (`sophi install git:<url>[#ref]`) into `~/.sophi/plugins/<name>/plugin.jar`,
 * each pinned in `plugins.lock.json` to the repo, the exact commit and the jar's SHA-256.
 *
 * The format: the repo commits a prebuilt `plugin.jar` at its root that lists its classes in
 * `META-INF/services/dev.sophi.extensions.SophiPlugin`. Nothing from the repo is built or run during
 * install. At startup a jar loads only while it still matches its pin.
 */
object InstalledPlugins {
    const val LOCK = "plugins.lock.json"
    const val JAR = "plugin.jar"
    val DEFAULT_DIR: Path = Path.of(System.getProperty("user.home"), ".sophi", "plugins")
    private val SERVICES = "META-INF/services/" + SophiPlugin::class.java.name
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** What an install would bring in; [replaces] is the pin an `--update` moves. */
    data class Preview(
        val name: String, val source: String, val commit: String, val sha256: String,
        val declares: List<String>, val replaces: SkillPin?
    ) {
        fun describe(): String = buildString {
            appendLine("Plugin:   $name")
            appendLine("From:     $source")
            replaces?.let { appendLine("Replaces: commit ${it.commit}") }
            appendLine("Commit:   $commit")
            appendLine("Jar:      sha256 $sha256")
            appendLine("Declares: ${declares.joinToString()}")
            append("A plugin is trusted code: once installed it runs inside SoPhi with your full user permissions ")
            append("and can read your files, keys and sessions. Install it only if you trust its author.")
        }
    }

    /**
     * Fetches [source] (`git:<url>[#ref]`), hands [confirm] the [Preview], and installs only on yes.
     * Returns the new pin, or null when declined. An installed name is refused unless [update].
     */
    fun install(source: String, dir: Path = DEFAULT_DIR, update: Boolean = false, confirm: (Preview) -> Boolean): SkillPin? {
        require(source.startsWith("git:")) { "plugins install from git only: git:<url>[#ref]" }
        val url = source.removePrefix("git:")
        val name = url.substringBefore('#').trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
        require(NAME.matches(name)) { "can't name a plugin from $url" }
        val old = SkillLock.read(dir, LOCK)[name]
        check(old == null || update) { "$name is already installed at ${old?.commit}; --update re-resolves it" }
        val (root, commit, cleanup) = SkillInstaller().resolveSource(url)
        try {
            checkNotNull(commit) { "$url is not a git URL (https://, git@ or file://)" }
            val jar = root.resolve(JAR)
            check(jar.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                "$url ships no prebuilt $JAR at its root (commit $commit). SoPhi installs prebuilt jars only and never builds a plugin from source."
            }
            val declares = ZipFile(jar.toFile()).use { z ->
                z.getEntry(SERVICES)?.let { e -> z.getInputStream(e).bufferedReader().readLines() }.orEmpty()
            }.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            check(declares.isNotEmpty()) { "$JAR declares no plugin: $SERVICES is missing or empty" }
            val pin = SkillPin(url, commit, sha256(jar))
            if (!confirm(Preview(name, url, commit, pin.sha256, declares, old))) return null
            jar.copyTo(dir.resolve(name).createDirectories().resolve(JAR), overwrite = true)
            SkillLock.write(dir, SkillLock.read(dir, LOCK) + (name to pin), LOCK)
            return pin
        } finally {
            cleanup()
        }
    }

    fun list(dir: Path = DEFAULT_DIR): Map<String, SkillPin> = SkillLock.read(dir, LOCK)

    fun remove(dir: Path = DEFAULT_DIR, name: String): Boolean {
        val pins = list(dir)
        if (name !in pins) return false
        dir.resolve(name).toFile().deleteRecursively()
        SkillLock.write(dir, pins - name, LOCK)
        return true
    }

    /**
     * The plugins in jars that still match their pin, found by [java.util.ServiceLoader] through one class
     * loader over those jars. A jar with no pin, or changed since install, is skipped and told to [onRefused].
     */
    fun load(dir: Path = DEFAULT_DIR, onRefused: (String) -> Unit = { System.err.println(it) }): List<SophiPlugin> {
        if (!dir.isDirectory()) return emptyList()
        val pins = list(dir)
        val jars = dir.listDirectoryEntries().sorted().filter { it.resolve(JAR).isRegularFile() }.mapNotNull { d ->
            val pin = pins[d.name]
            when {
                pin == null -> null.also { onRefused("plugin ${d.name}: not loaded, it has no pin in $LOCK") }
                pin.sha256 != sha256(d.resolve(JAR)) ->
                    null.also { onRefused("plugin ${d.name}: not loaded, $JAR changed since it was installed at ${pin.commit}") }
                else -> d.resolve(JAR)
            }
        }
        if (jars.isEmpty()) return emptyList()
        val loader = URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), SophiPlugin::class.java.classLoader)
        // Plugins on the host's own classpath are the host's business; only the jars' own classes count here.
        return runCatching { PluginRegistry(loader).discover().plugins().filter { it.javaClass.classLoader === loader } }
            .getOrElse { onRefused("plugins: not loaded, ${it.message}"); emptyList() }
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
