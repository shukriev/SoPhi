package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

private val MAP_ONLY = """
    ## Map
    Entry: https://example.com/dashboard

    ## Known unknowns
    - How to create a client

    ## Last updated
    2026-09-21
""".trimIndent()

private val WITH_ONE_PROCEDURE = """
    ## Map
    Entry: https://example.com/dashboard

    ## Procedures

    ### Create a client
    1. Click "New client" in the left sidebar.
    2. Fill the "Name" field.

    ## Last updated
    2026-09-21
""".trimIndent()

class SiteSkillStructureTest : FunSpec({

    test("an unrecognised mode string is rejected, and a missing one means map") {
        siteSkillModeOf("map") shouldBe SiteSkillMode.MAP
        siteSkillModeOf("procedure") shouldBe SiteSkillMode.PROCEDURE
        siteSkillModeOf("PROCEDURE") shouldBe SiteSkillMode.PROCEDURE
        // The default is the safety property: omitting it must never mean "procedure".
        siteSkillModeOf(null) shouldBe SiteSkillMode.MAP
        siteSkillModeOf("  ") shouldBe SiteSkillMode.MAP
        siteSkillModeOf("banana") shouldBe null
    }

    test("a map-mode body with Map and Known unknowns is accepted") {
        checkSiteSkillStructure(MAP_ONLY, SiteSkillMode.MAP).shouldBeEmpty()
    }

    test("map mode rejects a Procedures section") {
        // The whole point: exploring cannot write down how to do something it never did.
        checkSiteSkillStructure(WITH_ONE_PROCEDURE, SiteSkillMode.MAP).shouldNotBeEmpty()
    }

    test("procedure mode accepts Map plus exactly one procedure entry") {
        checkSiteSkillStructure(WITH_ONE_PROCEDURE, SiteSkillMode.PROCEDURE).shouldBeEmpty()
    }

    test("procedure mode rejects a second procedure entry") {
        val two = WITH_ONE_PROCEDURE.replace(
            "## Last updated",
            "### Delete a client\n1. Click the row's trash icon.\n\n## Last updated"
        )
        checkSiteSkillStructure(two, SiteSkillMode.PROCEDURE).shouldNotBeEmpty()
    }

    test("a body with no recognised section is rejected in either mode") {
        // The existing site-maidplus-de.md is shaped like this. Reading it is unaffected; the
        // next write has to structure it, which is the migration.
        val unsectioned = "# Some site\n\nThe dashboard has tables and a sidebar."
        checkSiteSkillStructure(unsectioned, SiteSkillMode.MAP).shouldNotBeEmpty()
        checkSiteSkillStructure(unsectioned, SiteSkillMode.PROCEDURE).shouldNotBeEmpty()
    }

    test("headings are matched case-insensitively and unknown sections are tolerated") {
        val mixed = """
            ## map
            Entry: https://example.com

            ## Notes for humans
            Anything may live here.
        """.trimIndent()
        checkSiteSkillStructure(mixed, SiteSkillMode.MAP).shouldBeEmpty()
    }

    test("a ### heading outside Procedures is not counted as a procedure entry") {
        val nested = """
            ## Map
            ### Dashboard
            Tables and a sidebar.

            ### Settings
            A form.
        """.trimIndent()
        checkSiteSkillStructure(nested, SiteSkillMode.MAP).shouldBeEmpty()
    }
})
