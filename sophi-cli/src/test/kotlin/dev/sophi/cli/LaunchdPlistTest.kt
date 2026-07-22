package dev.sophi.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class LaunchdPlistTest : FunSpec({
    test("build embeds the sophi binary, subcommand, and interval") {
        val xml = LaunchdPlist.build(sophiBin = "/usr/local/bin/sophi", intervalSeconds = 60)
        xml shouldContain "<string>/usr/local/bin/sophi</string>"
        xml shouldContain "<string>schedule</string>"
        xml shouldContain "<string>run-due</string>"
        xml shouldContain "<integer>60</integer>"
        xml shouldContain "dev.sophi.schedule"
    }
})
