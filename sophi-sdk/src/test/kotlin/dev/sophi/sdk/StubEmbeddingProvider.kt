package dev.sophi.sdk

import dev.sophi.ai.api.EmbeddingProvider

/** Shared test fake — was duplicated per-file across RuntimeBuilderMemoryTest, RuntimeBuilderLearningTest,
 *  and SophiRuntimeRecordSessionEndTest before this extraction. */
internal class StubEmbeddingProvider(private val shouldFail: Boolean = false) : EmbeddingProvider {
    override val dimensions = 4
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (shouldFail) error("no route to embeddings host")
        return texts.map { FloatArray(dimensions) }
    }
}
