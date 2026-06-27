package dev.sophi.ai.api

class ProviderRegistry(providers: List<LLMProvider>) {
    private val map: Map<String, LLMProvider> = providers.associateBy { it.name }

    fun get(name: String): LLMProvider =
        map[name] ?: throw NoSuchElementException("Unknown provider: $name")

    fun names(): List<String> = map.keys.sorted()
}
