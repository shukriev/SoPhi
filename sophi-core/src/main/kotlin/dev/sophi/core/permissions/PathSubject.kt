package dev.sophi.core.permissions

import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString

/**
 * A path inside [root] matches as root-relative ("." for the root itself), anything else as its absolute path; always `/`-separated.
 * Both are resolved through symlinks and the filesystem's own spelling (so `.ENV` on a case-insensitive disk is `.env`)
 * before comparing, because that is the file the tool will actually touch.
 */
internal fun pathSubject(root: Path, resolved: Path): PermissionSubject {
    val base = realPath(root.normalize())
    val path = realPath(resolved.normalize())
    val text = when {
        path == base -> "."
        path.startsWith(base) -> base.relativize(path).invariantSeparatorsPathString
        else -> path.invariantSeparatorsPathString
    }
    return PermissionSubject(text)
}

/** Real path of the longest existing ancestor, plus the not-yet-existing tail as written; the lexical path if that fails. */
private fun realPath(p: Path): Path = runCatching {
    var existing: Path = p
    while (!existing.exists(LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return p
    existing.toRealPath().resolve(existing.relativize(p)).normalize()
}.getOrDefault(p)
