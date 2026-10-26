package dev.sophi.core.permissions

import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/** A path inside [root] matches as root-relative ("." for the root itself), anything else as its absolute path; always `/`-separated. */
internal fun pathSubject(root: Path, resolved: Path): PermissionSubject {
    val base = root.normalize()
    val path = resolved.normalize()
    val text = when {
        path == base -> "."
        path.startsWith(base) -> base.relativize(path).invariantSeparatorsPathString
        else -> path.invariantSeparatorsPathString
    }
    return PermissionSubject(text)
}
