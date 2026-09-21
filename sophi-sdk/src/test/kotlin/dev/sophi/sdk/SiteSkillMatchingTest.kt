package dev.sophi.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val INSTALLED = listOf("site-maidplus-de", "site-docs-example-com", "site-github-com")

private val BODY = """
    ## Map
    Entry: https://maidplus.de/dashboard

    ## Procedures

    ### Create a client
    1. Click "New client".

    ### File a support ticket
    1. Open the Support tab.

    ## Known unknowns
    - How to archive a caregiver
    - How to export invoices

    ## Last updated
    2026-09-21
""".trimIndent()

class SiteSkillMatchingTest : FunSpec({

    test("a full URL yields the site id") {
        matchSiteSkill("open https://maidplus.de/clients?x=1 and file a ticket", INSTALLED) shouldBe
            "site-maidplus-de"
    }

    test("a bare hostname yields the site id") {
        matchSiteSkill("check maidplus.de for new tasks", INSTALLED) shouldBe "site-maidplus-de"
    }

    test("www is stripped so one site never becomes two ids") {
        // The protocol's literal rule would derive site-www-maidplus-de and miss the real skill.
        matchSiteSkill("open www.maidplus.de", INSTALLED) shouldBe "site-maidplus-de"
        deriveSiteId("www.maidplus.de") shouldBe deriveSiteId("maidplus.de")
    }

    test("a non-www subdomain is kept, because it is a different surface") {
        matchSiteSkill("look at docs.example.com", INSTALLED) shouldBe "site-docs-example-com"
        deriveSiteId("docs.example.com") shouldBe "site-docs-example-com"
    }

    test("a hostname with no installed skill matches nothing rather than inventing an id") {
        matchSiteSkill("go to unknown-site.org", INSTALLED) shouldBe null
    }

    test("a slug token in prose matches, so an unnamed URL still recalls") {
        matchSiteSkill("check my maidplus tasks for today", INSTALLED) shouldBe "site-maidplus-de"
    }

    test("short slug tokens never fire") {
        // "de", "com" and "co" appear in ordinary sentences constantly.
        matchSiteSkill("the de facto standard", INSTALLED) shouldBe null
        matchSiteSkill("a com port", INSTALLED) shouldBe null
    }

    test("an exact hostname beats another skill's token") {
        matchSiteSkill("copy the maidplus layout into github.com", INSTALLED) shouldBe "site-github-com"
    }

    test("two token matches and no hostname is ambiguous, so nothing is recalled") {
        matchSiteSkill("compare maidplus and github", INSTALLED) shouldBe null
    }

    test("procedure names and unknown counts are read from the body") {
        procedureNames(BODY) shouldBe listOf("Create a client", "File a support ticket")
        knownUnknownCount(BODY) shouldBe 2
    }

    test("the pointer names the skill, its procedures and how to load it") {
        val pointer = renderSitePointer("site-maidplus-de", BODY)

        pointer shouldContain "site-maidplus-de"
        pointer shouldContain "Create a client"
        pointer shouldContain "File a support ticket"
        pointer shouldContain "2"
        pointer shouldContain """skill(name="site-maidplus-de")"""
    }

    test("an unsectioned legacy body still yields a usable pointer") {
        // site-maidplus-de.md is shaped like this today; a pointer with no procedure list beats
        // no pointer at all, and needs no migration.
        val pointer = renderSitePointer("site-maidplus-de", "# Maidplus\n\nDashboard has tables.")

        pointer shouldContain "site-maidplus-de"
        procedureNames("# Maidplus\n\nDashboard has tables.") shouldBe emptyList()
    }
})
