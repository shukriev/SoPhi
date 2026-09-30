package dev.sophi.store.arcade

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Properties

// ArcadeDB loads the first arcadedb-log.properties on the classpath. Its own logs to
// "./log/arcadedb.log" (relative to wherever Sophi was started, so every project got a log/ dir)
// and prints INFO to the console (the LSMVectorIndex lines in every transcript).
class ArcadeLogConfigTest : FunSpec({
    test("the arcadedb-log.properties on the classpath is Sophi's: logs under the home dir, quiet console") {
        val props = Properties().apply {
            ArcadeLogConfigTest::class.java.classLoader.getResourceAsStream("arcadedb-log.properties")!!.use { load(it) }
        }
        props.getProperty("java.util.logging.FileHandler.pattern") shouldBe "%h/.sophi/log/arcadedb.log"
        props.getProperty("java.util.logging.ConsoleHandler.level") shouldBe "WARNING"
    }
})
