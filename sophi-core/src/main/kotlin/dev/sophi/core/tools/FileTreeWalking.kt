package dev.sophi.core.tools

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Like Files.walk(root) but skips subtrees it can't read (e.g. macOS TCC-protected folders
 * encountered when root is a whole home directory) instead of aborting the entire search.
 */
internal fun walkRegularFiles(root: Path): List<Path> {
    val results = mutableListOf<Path>()
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile) results.add(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
    })
    return results
}
