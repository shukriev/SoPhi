package dev.sophi.sdk

/**
 * A hostname-shaped run of characters: optional scheme, then dot-separated labels. Deliberately
 * loose — it does not try to know what a valid TLD is, because membership in the installed skill
 * ids is the real test. "unknown-site.org" parses fine and then matches nothing.
 */
private val HOST_CANDIDATE = Regex(
    """(?:https?://)?([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+)"""
)

/** Below this, a slug token is a word like "de" or "com" that occurs in ordinary sentences. */
private const val MIN_TOKEN_LENGTH = 4

/**
 * The skill id for a hostname: lowercase, drop a leading `www.`, dots become dashes.
 *
 * Dropping `www.` is not cosmetic. Without it `www.maidplus.de` and `maidplus.de` derive different
 * ids for one site, and knowledge accumulates in two files that never find each other. Other
 * subdomains are kept — `docs.example.com` and `app.example.com` are genuinely different surfaces.
 */
fun deriveSiteId(host: String): String =
    "site-" + host.lowercase().removePrefix("www.").replace('.', '-')

/**
 * The installed site skill this turn is about, or null.
 *
 * This is the step `browsing-sites.md` asks the model to perform from prose — deriving a slug and
 * remembering to look. Its failure is silent: a model that skips it re-explores a site it already
 * documented, and nothing surfaces that.
 */
fun matchSiteSkill(userInput: String, availableIds: List<String>): String? {
    val ids = availableIds.toSet()

    // An explicit hostname is unambiguous, so it wins outright.
    HOST_CANDIDATE.findAll(userInput).forEach { match ->
        val id = deriveSiteId(match.groupValues[1])
        if (id in ids) return id
    }

    // Otherwise fall back to the skill's own slug words appearing in the message.
    val words = userInput.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
    val hits = availableIds.filter { id ->
        id.removePrefix("site-").split('-')
            .any { token -> token.length >= MIN_TOKEN_LENGTH && token in words }
    }
    // singleOrNull, not firstOrNull: two candidates means guessing, and a confident pointer to the
    // wrong site is worse than no pointer.
    return hits.singleOrNull()
}

/**
 * The line injected into the turn. A pointer rather than the skill body: the `skill` tool exists
 * for on-demand loading, and site skills are meant to grow.
 */
fun renderSitePointer(id: String, body: String): String = buildString {
    appendLine("You already have a skill for this site: $id")
    val procedures = procedureNames(body)
    if (procedures.isNotEmpty()) appendLine("Documented procedures: ${procedures.joinToString(", ")}")
    val unknowns = knownUnknownCount(body)
    if (unknowns > 0) appendLine("Known unknowns: $unknowns")
    append("""Load it with skill(name="$id") before acting, rather than re-exploring.""")
}
