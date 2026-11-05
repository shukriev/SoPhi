package dev.sophi.core.tools

import dev.sophi.core.agent.ToolCallScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EcmaError
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrapFactory
import java.util.concurrent.CompletableFuture

private const val CODE_MODE = "code_mode"

/**
 * Runs a short JavaScript program (Rhino) whose `tools.<name>(args)` calls go through [ToolCallScope], i.e. the agent
 * loop's own pipeline: rules, confirmation, hooks, audit and undo apply to each call as if the model made it. The script
 * itself reaches nothing else: no Java, files, network, processes or threads. SAFE: it does nothing on its own.
 */
class CodeModeTool(
    /** Script time; time spent inside tool calls (a confirmation prompt, say) doesn't count. */
    private val timeLimitMillis: Long = 10_000,
    private val maxCalls: Int = 100,
) : Tool {
    override val name = CODE_MODE
    override val description =
        "Run a short JavaScript program that calls other tools and returns only what you need: use it to chain several " +
            "dependent tool calls in one step. In the script, tools.<name>(args) calls a tool (args: an object with that " +
            "tool's parameters) and returns its text result; a failed or denied call throws an Error. Each call is checked " +
            "and confirmed exactly like a direct call. End with `return <value>` (objects come back as JSON). Plain, " +
            "synchronous JavaScript (ES2015 level): no async/await, require, fetch, console or file access. " +
            "${timeLimitMillis / 1000} s of script time, at most $maxCalls tool calls."
    override val parametersJson =
        """{"type":"object","properties":{"code":{"type":"string","description":"The script body; end it with return <value>."}},"required":["code"]}"""

    private class Request(val name: String, val argumentsJson: String, val reply: CompletableFuture<String>)

    override suspend fun execute(argumentsJson: String): String {
        val scope = currentCoroutineContext()[ToolCallScope]
            ?: return "Error: code_mode runs only inside an agent turn, where its tool calls can be checked"
        val code = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
            ?: return "Error: code is required"
        // Rhino runs on its own thread; each tool call comes back here, so it runs where the model's own calls run.
        val requests = Channel<Request>()
        return coroutineScope {
            val script = async {
                runInterruptible(Dispatchers.IO) {
                    try {
                        runScript(code) { n, a ->
                            val reply = CompletableFuture<String>()
                            requests.trySendBlocking(Request(n, a, reply)).getOrThrow()
                            reply.get()
                        }
                    } finally {
                        requests.close()
                    }
                }
            }
            for (r in requests) r.reply.complete(scope.call(r.name, r.argumentsJson))
            script.await()
        }
    }

    /** One sandboxed run; [call] blocks this thread until the tool call is done. */
    private fun runScript(code: String, call: (String, String) -> String): String {
        val ran = mutableListOf<String>()
        val sandbox = Sandbox(timeLimitMillis)
        return try {
            sandbox.call { cx ->
                val global = cx.initSafeStandardObjects()
                ScriptableObject.putProperty(global, "tools", Bridge(global, sandbox, ran, call))
                val value = cx.evaluateString(global, "(function(){\n$code\n})()", "script", 0, null)
                when (value) {
                    is Undefined -> "(the script returned nothing)"
                    is CharSequence -> value.toString()
                    else -> NativeJSON.stringify(cx, global, value, null, null).takeUnless { it is Undefined }?.toString()
                        ?: Context.toString(value)
                }
            }
        } catch (e: Throwable) {
            if (e is InterruptedException || Thread.currentThread().isInterrupted) throw e
            val why = when (e) {
                is EcmaError -> if (e.name == "Error") e.errorMessage else e.details()
                is RhinoException -> e.details()
                else -> e.message ?: e.javaClass.simpleName
            }
            "Error: the script failed: $why" + if (ran.isEmpty()) "" else "\nTool calls that already ran: ${ran.joinToString(", ")}"
        }
    }

    /** `tools`: any property is a function that makes that tool call. */
    private inner class Bridge(
        private val global: Scriptable, private val sandbox: Sandbox, private val ran: MutableList<String>,
        private val call: (String, String) -> String,
    ) : ScriptableObject(global, null) {
        override fun getClassName() = "Tools"
        override fun get(name: String, start: Scriptable): Any = object : BaseFunction(global, ScriptableObject.getFunctionPrototype(global)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
                if (name == CODE_MODE) throw ScriptRuntime.constructError("Error", "a script can't call $CODE_MODE")
                if (ran.size >= maxCalls) throw ScriptRuntime.constructError("Error", "a script may make at most $maxCalls tool calls")
                val arg = args.firstOrNull()
                val json = if (arg == null || arg is Undefined) "{}"
                    else NativeJSON.stringify(cx, global, arg, null, null).takeUnless { it is Undefined }?.toString() ?: "{}"
                ran += name
                val start = System.nanoTime()
                val result = call(name, json)
                sandbox.deadline += System.nanoTime() - start
                if (isToolError(result)) throw ScriptRuntime.constructError("Error", result)
                return result
            }
        }
    }

    /** Interpreted Rhino that sees no Java class or object, and stops at [deadline] or when its thread is interrupted. */
    private class Sandbox(timeLimitMillis: Long) : ContextFactory() {
        @Volatile var deadline = System.nanoTime() + timeLimitMillis * 1_000_000
        private val limitText = "the time limit (${timeLimitMillis} ms of script time)"

        override fun makeContext(): Context = super.makeContext().apply {
            setInterpretedMode(true)
            instructionObserverThreshold = 10_000
            setClassShutter { false }
            wrapFactory = object : WrapFactory() {
                override fun wrapAsJavaObject(cx: Context, scope: Scriptable?, javaObject: Any?, staticType: Class<*>?): Scriptable =
                    throw EvaluatorException("host objects are not reachable from a script")
            }
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (System.nanoTime() > deadline) throw Error("the script reached $limitText")
        }
    }
}
