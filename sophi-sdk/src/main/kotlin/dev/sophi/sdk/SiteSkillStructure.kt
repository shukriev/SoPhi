package dev.sophi.sdk

/**
 * What a `write_skill` call claims it is recording.
 *
 * [MAP] is what exploring produces: the shape of a system, learned by looking. [PROCEDURE] is what
 * doing produces: steps that were actually carried out. The distinction exists because the first
 * real site skill this system wrote was a dashboard inventory filed under a protocol that asked
 * for workflows — a tour wearing a procedure's heading, with nobody able to tell the difference.
 */
enum class SiteSkillMode { MAP, PROCEDURE }

/** Section headings a site skill is built from, matched case-insensitively at `##` level. */
private const val MAP = "map"
private const val PROCEDURES = "procedures"
private const val KNOWN_UNKNOWNS = "known unknowns"
private const val LAST_UPDATED = "last updated"

private val RECOGNISED = setOf(MAP, PROCEDURES, KNOWN_UNKNOWNS, LAST_UPDATED)

private val H2 = Regex("""(?m)^##[ \t]+(.+?)[ \t]*$""")
private val H3 = Regex("""(?m)^###[ \t]+(.+?)[ \t]*$""")

/**
 * [SiteSkillMode.MAP] for a null or blank value — omitting the parameter must never mean
 * [SiteSkillMode.PROCEDURE], since the whole point is that claiming a procedure is a deliberate
 * act. Null return means the caller sent something unrecognised, which is an error rather than a
 * default.
 */
fun siteSkillModeOf(raw: String?): SiteSkillMode? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return SiteSkillMode.MAP
    return when (trimmed.lowercase()) {
        "map" -> SiteSkillMode.MAP
        "procedure" -> SiteSkillMode.PROCEDURE
        else -> null
    }
}

/**
 * Complaints about a site skill body, empty when acceptable. Same shape as [checkSkillContent]: a
 * pure function returning reasons, so the tool can report all of them at once.
 *
 * Unknown `##` headings are tolerated deliberately — a skill may carry notes this validator has no
 * opinion about, and rejecting them would make the format brittle for no safety gain.
 */
fun checkSiteSkillStructure(body: String, mode: SiteSkillMode): List<String> = buildList {
    val headings = H2.findAll(body).map { it.groupValues[1].trim().lowercase() }.toList()
    val recognised = headings.filter { it in RECOGNISED }

    if (recognised.isEmpty()) {
        add(
            "body has none of the recognised sections (## Map, ## Procedures, ## Known unknowns, " +
                "## Last updated) — an unstructured body cannot distinguish what the site is from " +
                "how to operate it"
        )
        return@buildList
    }

    val hasProcedures = PROCEDURES in recognised
    if (mode == SiteSkillMode.MAP && hasProcedures) {
        add(
            "mode=map cannot write a ## Procedures section — a procedure records steps that were " +
                "actually performed; use mode=procedure after doing the task"
        )
    }

    if (hasProcedures) {
        val entries = procedureEntryCount(body)
        if (entries > 1) {
            add("## Procedures has $entries entries; one write records one completed task")
        }
    }
}

/**
 * `###` headings inside the `## Procedures` section only. A `###` under `## Map` is a sub-heading
 * of the map, not a procedure, so the count is scoped to the section rather than the whole body.
 */
private fun procedureEntryCount(body: String): Int = procedureNames(body).size

/** The `### ` entries under `## Procedures`, in document order. Empty for an unsectioned body. */
fun procedureNames(body: String): List<String> =
    H3.findAll(sectionBody(body, PROCEDURES)).map { it.groupValues[1].trim() }.toList()

/** How many `-` bullets sit under `## Known unknowns`. */
fun knownUnknownCount(body: String): Int =
    sectionBody(body, KNOWN_UNKNOWNS).lines().count { it.trimStart().startsWith("- ") }

/** The text under [heading], up to the next `##` or end of body; empty when absent. */
private fun sectionBody(body: String, heading: String): String {
    val start = H2.findAll(body).firstOrNull { it.groupValues[1].trim().lowercase() == heading }
        ?: return ""
    val after = body.substring(start.range.last + 1)
    val nextH2 = H2.find(after)
    return if (nextH2 == null) after else after.substring(0, nextH2.range.first)
}
