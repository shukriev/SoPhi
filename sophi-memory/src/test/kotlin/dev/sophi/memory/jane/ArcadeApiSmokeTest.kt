package dev.sophi.memory.jane

import com.arcadedb.database.Database
import com.arcadedb.database.DatabaseFactory
import com.arcadedb.index.vector.LSMVectorIndex
import com.arcadedb.schema.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class ArcadeApiSmokeTest : FunSpec({
    fun openDb(): Database {
        val factory = DatabaseFactory(tempdir().toPath().resolve("db").toString())
        return if (factory.exists()) factory.open() else factory.create()
    }

    test("create vertex/edge/document types, insert via SQL, query back") {
        val db = openDb()
        db.transaction {
            db.schema.createVertexType("Person")
            db.schema.createEdgeType("Knows")
            db.schema.createDocumentType("Note")
        }
        db.transaction {
            db.command("sql", "INSERT INTO Person SET id = ?, name = ?", "p1", "Alice")
            db.command("sql", "INSERT INTO Person SET id = ?, name = ?", "p2", "Bob")
            db.command(
                "sql",
                "CREATE EDGE Knows FROM (SELECT FROM Person WHERE id = ?) TO (SELECT FROM Person WHERE id = ?)",
                "p1", "p2"
            )
            db.command("sql", "INSERT INTO Note SET id = ?, text = ?", "n1", "hello")
        }

        val people = db.query("sql", "SELECT FROM Person").use { rs ->
            generateSequence { if (rs.hasNext()) rs.next() else null }.toList()
        }
        people.size shouldBe 2
        people.map { it.getProperty<String>("name") } shouldContain "Alice"

        val edges = db.query("sql", "SELECT FROM Knows").use { rs ->
            generateSequence { if (rs.hasNext()) rs.next() else null }.toList()
        }
        edges.size shouldBe 1
        println("edge propertyNames=" + edges.first().propertyNames)

        db.close()
    }

    test("vector index create and nearest-neighbor query") {
        val db = openDb()
        db.transaction {
            val docType = db.schema.createVertexType("Doc")
            docType.createProperty("id", Type.STRING)
            docType.createProperty("embedding", Type.ARRAY_OF_FLOATS)
            db.command(
                "sql",
                "CREATE INDEX ON Doc (embedding) LSM_VECTOR METADATA { dimensions: 3, similarity: 'COSINE' }"
            )
        }
        db.transaction {
            db.command("sql", "INSERT INTO Doc SET id = ?, embedding = ?", "d1", floatArrayOf(1f, 0f, 0f))
            db.command("sql", "INSERT INTO Doc SET id = ?, embedding = ?", "d2", floatArrayOf(0f, 1f, 0f))
        }
        println("indexes=" + db.schema.indexes.map { it.name })
        val index = db.schema.indexes.first { it.typeName == "Doc" } as LSMVectorIndex
        val nearest = index.findNeighborsFromVector(floatArrayOf(0.9f, 0.1f, 0f), 1)
        val nearestId = nearest.first().first.let { rid -> db.lookupByRID(rid, true) }
            .let { it as com.arcadedb.database.Document }.getString("id")
        nearestId shouldBe "d1"
        db.close()
    }
})
