package dev.sophi.sdk

object Sophi {
    fun runtime(block: RuntimeBuilder.() -> Unit): SophiRuntime =
        RuntimeBuilder().apply(block).build()
}
