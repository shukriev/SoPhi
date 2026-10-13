package dev.sophi.context

/** The project a session is bound to (CLI: the one containing cwd; companion: the chat's chip), or null. */
typealias ProjectOf = (sessionId: String?) -> String?

/** Spec §6.2: a session searches its own project plus every global one; it is oriented on its own project only. */
object ContextScope {
    fun searchIds(primary: String?, projects: List<ProjectInfo>): List<String> =
        (listOfNotNull(primary?.takeIf { id -> projects.any { it.id == id } }) +
            projects.filter { it.global }.sortedBy { it.root }.map { it.id }).distinct()
}
