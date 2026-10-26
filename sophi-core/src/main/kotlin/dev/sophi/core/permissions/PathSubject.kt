package dev.sophi.core.permissions

import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString

/**
 * A path inside [root] matches as root-relative ("." for the root itself), anything else as its absolute path; always `/`-separated.
 * Both are resolved through symlinks and the filesystem's own spelling (so `.ENV` on a case-insensitive disk is `.env`)
 * before comparing, because that is the file the tool will actually touch.
 * Null when that can't be told (a dangling symlink, a loop, no permission): the caller fails closed.
 */
internal fun pathSubject(root: Path, resolved: Path): PermissionSubject? {
    val base = realPath(root.normalize()) ?: return null
    val path = realPath(resolved.normalize()) ?: return null
    val text = when {
        path == base -> "."
        path.startsWith(base) -> base.relativize(path).invariantSeparatorsPathString
        else -> path.invariantSeparatorsPathString
    }
    return PermissionSubject(text)
}

/** Real path of the longest existing ancestor (a dangling symlink counts as existing, and has no real path) plus the missing tail as written. */
private fun realPath(p: Path): Path? = try {
    var existing: Path = p
    while (!existing.exists(LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return p
    existing.toRealPath().resolve(existing.relativize(p)).normalize()
} catch (_: IOException) {
    null
}
