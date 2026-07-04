package dev.sophi.cli

interface InputSource {
    suspend fun readLine(): String?
    suspend fun awaitEsc()
}
