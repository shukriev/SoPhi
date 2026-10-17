package dev.sophi.versioning

import dev.sophi.store.arcade.ArcadeHost
import dev.sophi.store.arcade.ArcadeStore
import dev.sophi.store.arcade.EmbeddedArcadeStore
import dev.sophi.store.arcade.LegacyLayout
import dev.sophi.store.arcade.SharedStore
import java.nio.file.Path

/** How a versioning store reaches ArcadeDB: an embedded home opened around each operation (ADR-029), or one
 *  store on the shared server held for the store's lifetime (ADR-041 M6). */
class VersioningDb private constructor(private val opener: () -> ArcadeStore, private val held: ArcadeStore?) : AutoCloseable {
    companion object {
        fun embedded(home: Path) = VersioningDb({ EmbeddedArcadeStore.open(home) }, null)
        fun shared(store: ArcadeStore) = VersioningDb({ store }, store)
    }

    fun <T> use(block: (ArcadeStore) -> T): T {
        val s = held ?: opener()
        try { return block(s) } finally { if (held == null) s.close() }
    }

    override fun close() { held?.close() }
}

/** Picks embedded or shared by home: only the default homes live on the shared server. */
object VersioningStores {
    const val SCHEMA_VERSION = 1
    val LEGACY_LAYOUT = LegacyLayout(vertexTypes = listOf("Version", "Scorecard"), edgeTypes = mapOf("MUTATED_FROM" to "Version"))
    private val sophi: Path = Path.of(System.getProperty("user.home"), ".sophi")
    val DEFAULT_SHARED: Map<Path, String> = mapOf(
        sophi.resolve("versioning") to "versioning",
        sophi.resolve("memory").resolve(".versions") to "memory-versions"
    )

    fun db(home: Path, sharedRoot: Path = ArcadeHost.defaultRoot(), shared: Map<Path, String> = DEFAULT_SHARED): VersioningDb {
        val database = shared.entries.firstOrNull { SharedStore.samePath(it.key, home) }?.value
            ?: return VersioningDb.embedded(home)
        return VersioningDb.shared(SharedStore.open(sharedRoot, database, SCHEMA_VERSION, legacyHome = home, layout = LEGACY_LAYOUT))
    }

    fun versionStore(home: Path, sharedRoot: Path = ArcadeHost.defaultRoot(), shared: Map<Path, String> = DEFAULT_SHARED) =
        VersionStore(db(home, sharedRoot, shared))

    fun scorecardStore(home: Path, sharedRoot: Path = ArcadeHost.defaultRoot(), shared: Map<Path, String> = DEFAULT_SHARED) =
        ScorecardStore(db(home, sharedRoot, shared))
}
