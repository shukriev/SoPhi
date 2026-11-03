package dev.sophi.ai.providers

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException

private val NOT_LOADED = Regex("""no models? (is |are )?loaded|model\b.*\bnot (loaded|found)""", RegexOption.IGNORE_CASE)

/**
 * Why [error] means the model can't be reached now — the server is down, hasn't loaded the model, or
 * answered 5xx after the client's own retries — or null when the request itself is at fault (a bad
 * request, a prompt too long, a refused key) or the error is something else. Another model can help
 * with the first kind, never with the second.
 */
fun unavailableReason(error: Throwable): String? {
    for (e in generateSequence(error) { it.cause }.take(10)) {
        val status = when (e) {
            is OpenAIServiceException -> e.statusCode()
            is AnthropicServiceException -> e.statusCode()
            else -> null
        }
        if (status != null) return when {
            status >= 500 -> "the server answered $status"
            status == 404 || (status == 400 && NOT_LOADED.containsMatchIn(e.message.orEmpty())) -> "the model isn't loaded"
            else -> null
        }
        if (e is OpenAIIoException || e is AnthropicIoException || e is SocketException || e is UnknownHostException ||
            e is HttpConnectTimeoutException
        ) return "the server isn't answering"
    }
    return null
}
