package dev.sophi.store.arcade

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.createTempDirectory

// ArcadeDB's own arcadedb-log.properties logs to "./log/arcadedb.log" (every project Sophi ran in got
// a log/ dir) and prints INFO to the console. Relying on classpath order to shadow it failed in the
// packaged companion app, whose launcher lists jars alphabetically (arcadedb-engine before sophi-store).
// So Sophi points ArcadeDB at its own file via java.util.logging.config.file, which ArcadeDB checks
// before the classpath — unless the host already set it.
class ArcadeLogConfigTest : FunSpec({
    fun withProperty(value: String?, block: () -> Unit) {
        val saved = System.getProperty(LOG_CONFIG_PROPERTY)
        if (value == null) System.clearProperty(LOG_CONFIG_PROPERTY) else System.setProperty(LOG_CONFIG_PROPERTY, value)
        try { block() } finally {
            if (saved == null) System.clearProperty(LOG_CONFIG_PROPERTY) else System.setProperty(LOG_CONFIG_PROPERTY, saved)
        }
    }

    test("opening a store points ArcadeDB at Sophi's log config: home-dir log file, quiet console") {
        withProperty(null) {
            EmbeddedArcadeStore.open(createTempDirectory("arcade-log-test")).close()

            val file = System.getProperty(LOG_CONFIG_PROPERTY)
            file shouldNotBe null
            val props = Properties().apply { Files.newInputStream(java.nio.file.Path.of(file)).use { load(it) } }
            props.getProperty("java.util.logging.FileHandler.pattern") shouldBe "%h/.sophi/log/arcadedb.log"
            props.getProperty("java.util.logging.ConsoleHandler.level") shouldBe "WARNING"
        }
    }

    test("a log config the host already chose is left alone") {
        withProperty("/host/chosen/logging.properties") {
            useSophiArcadeLogConfig()
            System.getProperty(LOG_CONFIG_PROPERTY) shouldBe "/host/chosen/logging.properties"
        }
    }
})
