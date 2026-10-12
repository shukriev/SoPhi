package dev.sophi.context

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

enum class Role { SOURCE, TEST, DOC, CONFIG, INSTRUCTION, GENERATED }

data class FileEntry(val relPath: String, val size: Long, val mtime: Long, val role: Role, val lang: String)

data class Listing(val files: List<FileEntry>, val partial: Boolean)

val INSTRUCTION_FILES = setOf("AGENTS.md", "CLAUDE.md", ".cursorrules")

/** `null` from [lsFiles] means "no usable git here — walk the tree instead". */
fun interface GitProbe {
    fun lsFiles(root: Path): List<String>?

    companion object {
        val none = GitProbe { null }

        /** Real git, unless it's macOS's install-prompt stub (no Command Line Tools — spec §5.2). */
        val system = GitProbe { root ->
            if (findGitRoot(root) == null || !gitUsable()) null
            else runCatching {
                // stdout to a temp file and stderr discarded, so neither pipe can fill and block git;
                // waitFor then really enforces the timeout, and a hung git is killed.
                val tmp = Files.createTempFile("sophi-ls-files", ".out")
                try {
                    val p = ProcessBuilder("git", "-C", root.toString(), "ls-files", "-co", "--exclude-standard", "-z")
                        .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(tmp.toFile()).start()
                    if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); null }
                    else if (p.exitValue() != 0) null
                    else Files.readString(tmp).split('\u0000').filter { it.isNotEmpty() }.distinct()
                } finally { Files.deleteIfExists(tmp) }
            }.getOrNull()
        }

        private val usable: Boolean by lazy {
            val isMac = System.getProperty("os.name").lowercase().contains("mac")
            val cmd = if (isMac) listOf("xcode-select", "-p") else listOf("git", "--version")
            runCatching {
                val p = ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                if (p.waitFor(5, TimeUnit.SECONDS)) p.exitValue() == 0 else { p.destroyForcibly(); false }
            }.getOrDefault(false)
        }
        private fun gitUsable() = usable
    }
}

fun findGitRoot(start: Path): Path? {
    var p: Path? = start.toAbsolutePath().normalize()
    while (p != null) {
        if (Files.exists(p.resolve(".git"))) return p
        p = p.parent
    }
    return null
}

object ProjectFiles {
    const val MAX_BYTES = 1_000_000L
    const val MAX_FILES = 50_000
    private val SKIP_DIRS = setOf(".git", "node_modules", "build", "target", ".gradle", ".idea", "out", "dist")
    private val LOCKFILES = setOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "Cargo.lock", "poetry.lock", "gradle.lockfile", "Gemfile.lock")
    private val CONFIG_NAMES = setOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "package.json", "Cargo.toml", "pyproject.toml", "go.mod", "Makefile", "Dockerfile", "docker-compose.yml")
    private val LANGS = mapOf(
        "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "ts" to "typescript", "tsx" to "typescript",
        "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript", "py" to "python", "go" to "go", "rs" to "rust",
        "md" to "markdown", "markdown" to "markdown", "xml" to "xml", "json" to "json", "yml" to "yaml", "yaml" to "yaml",
        "toml" to "toml", "sh" to "shell", "gradle" to "groovy", "swift" to "swift", "c" to "c", "h" to "c", "cpp" to "cpp",
        "html" to "html", "css" to "css", "sql" to "sql", "txt" to "text", "properties" to "properties"
    )

    private fun isSecret(name: String) = name == ".env" || name.startsWith(".env.") ||
        name.substringAfterLast('.', "").lowercase() in setOf("pem", "key", "p12", "pfx") ||
        name.startsWith("id_rsa") || name.startsWith("id_ed25519") || name.startsWith("id_ecdsa")

    fun classify(relPath: String): Pair<Role, String> {
        val name = relPath.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val lang = LANGS[ext] ?: if (name == "Dockerfile" || name == "Makefile") name.lowercase() else ext.ifEmpty { "text" }
        val role = when {
            name in INSTRUCTION_FILES || relPath == ".sophi/instructions.md" || relPath.endsWith("/.sophi/instructions.md") -> Role.INSTRUCTION
            name in LOCKFILES || name.contains(".min.") -> Role.GENERATED
            name in CONFIG_NAMES -> Role.CONFIG
            relPath.contains("/test/") || relPath.startsWith("test/") || relPath.contains("/tests/") || relPath.startsWith("tests/") ||
                Regex("(Test|Tests|Spec|IT|_test|\\.test|\\.spec)\\.[A-Za-z]+$").containsMatchIn(name) -> Role.TEST
            lang == "markdown" || lang == "text" -> Role.DOC
            else -> Role.SOURCE
        }
        return role to lang
    }

    fun isBinary(path: Path): Boolean = runCatching {
        Files.newInputStream(path).use { s -> val buf = ByteArray(8192); val n = s.read(buf); (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() } }
    }.getOrDefault(true)

    fun list(root: Path, git: GitProbe = GitProbe.system, maxFiles: Int = MAX_FILES): Listing {
        val candidates = git.lsFiles(root) ?: walk(root, maxFiles)
        val out = ArrayList<FileEntry>()
        var partial = false
        for (rel in candidates) {
            val parts = rel.split('/')
            if (parts.dropLast(1).any { it in SKIP_DIRS } || isSecret(parts.last())) continue
            val path = root.resolve(rel)
            val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: continue
            if (attrs.isSymbolicLink || !attrs.isRegularFile || attrs.size() > MAX_BYTES || isBinary(path)) continue
            if (out.size >= maxFiles) { partial = true; break }
            val (role, lang) = classify(rel)
            out.add(FileEntry(rel, attrs.size(), attrs.lastModifiedTime().toMillis(), role, lang))
        }
        return Listing(out, partial)
    }

    // ponytail: .gitignore subset — root file only, glob lines and dir/ lines, no negation or nested
    // .gitignore files; git repos never reach this path (ls-files is exact). Upgrade if non-git folders
    // with complex ignores show up.
    private fun walk(root: Path, limit: Int): List<String> {
        val patterns = listOf(".gitignore", ".sophiignore").flatMap { f ->
            root.resolve(f).takeIf { Files.isRegularFile(it) }?.let { Files.readAllLines(it) }.orEmpty()
        }.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
        val dirNames = patterns.filter { it.endsWith("/") }.map { it.trimEnd('/').trimStart('/') }.toSet()
        val fs = root.fileSystem
        val globs = patterns.filterNot { it.endsWith("/") }.map { p ->
            val g = p.trimStart('/'); fs.getPathMatcher("glob:" + if (g.contains('/')) g else "{$g,**/$g}")
        }
        val out = ArrayList<String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                val rel = root.relativize(dir).toString()
                return if (dir != root && (name in SKIP_DIRS || name in dirNames || rel in dirNames)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = root.relativize(file)
                if (attrs.isSymbolicLink || isSecret(file.fileName.toString())) return FileVisitResult.CONTINUE
                if (globs.none { it.matches(rel) }) out.add(rel.toString().replace('\\', '/'))
                // early stop: list() only needs maxFiles + 1 acceptable files to know it is partial
                return if (out.size > limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return out.sorted()
    }
}
