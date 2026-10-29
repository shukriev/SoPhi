package dev.sophi.sdk

import dev.sophi.ai.providers.BraveSearchProvider
import dev.sophi.core.audit.AuditSink
import dev.sophi.core.audit.JsonlAuditSink
import dev.sophi.core.tools.BashJobs
import dev.sophi.core.tools.BashKillTool
import dev.sophi.core.tools.BashOutputTool
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.DeleteFileTool
import dev.sophi.core.tools.ApplyPatchTool
import dev.sophi.core.tools.EditTool
import dev.sophi.core.tools.FetchUrlTool
import dev.sophi.core.tools.FileReadTool
import dev.sophi.core.tools.FileWriteTool
import dev.sophi.core.tools.GetCurrentDateTimeTool
import dev.sophi.core.tools.GlobTool
import dev.sophi.core.tools.GrepTool
import dev.sophi.core.tools.MoveFileTool
import dev.sophi.core.tools.RunClaudeCodeTool
import dev.sophi.core.tools.BashSandbox
import dev.sophi.core.tools.SandboxMode
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.WebSearchTool
import dev.sophi.core.permissions.PermissionRules
import dev.sophi.mcp.server.buildMcpServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.nio.file.Path

/**
 * The standard file/shell/search/date tool set every interactive Sophi host registers.
 * [root] scopes every filesystem-touching tool (read/write/grep/glob/edit/bash); defaults to the
 * process's current directory, matching those tools' own constructor defaults. Falls back to the
 * `BRAVE_SEARCH_API_KEY` environment variable when [braveApiKey] is null; omit both to leave
 * `web_search` unregistered. [sandbox]: where `bash` runs, switchable while the host runs (see
 * [BashSandbox.set]); [sandboxed] starts it in seatbelt on a Mac that has it (writes outside the
 * workspace, temp and tool caches fail).
 */
fun buildBuiltinTools(root: Path = Path.of("").toAbsolutePath(), braveApiKey: String? = null, sandboxed: Boolean = false,
    sandbox: BashSandbox = BashSandbox(root).apply { if (sandboxed) set(SandboxMode.SEATBELT) },
    /** Background bash jobs; null = `bash(background)` is refused and `bash_output`/`bash_kill` aren't registered. */
    jobs: BashJobs? = null): List<Tool> {
    val tools = mutableListOf<Tool>(
        FileReadTool(root), FileWriteTool(root), GrepTool(root), GlobTool(root), EditTool(root), ApplyPatchTool(root),
        BashTool(root, sandbox, jobs),
        MoveFileTool(root), DeleteFileTool(root),
        FetchUrlTool(), GetCurrentDateTimeTool(), RunClaudeCodeTool()
    )
    if (jobs != null) tools += listOf(BashOutputTool(jobs), BashKillTool(jobs))
    val key = braveApiKey ?: System.getenv("BRAVE_SEARCH_API_KEY")
    if (key != null) {
        tools.add(WebSearchTool(BraveSearchProvider(key)))
    }
    return tools
}

/**
 * The `mcp-serve` server: [exposedNames] of the builtin tools over MCP, every call audited to [audit]
 * (by default the same ~/.sophi/audit.jsonl as the CLI, warnings on stderr since stdout carries the protocol).
 */
fun builtinMcpServer(
    exposedNames: Set<String>,
    root: Path = Path.of("").toAbsolutePath(),
    braveApiKey: String? = null,
    audit: AuditSink? = JsonlAuditSink(),
    /** Defaults to the same global and project files as the CLI; a malformed file throws [PermissionRulesException]. */
    permissions: PermissionRules? = loadPermissionRules(root),
): Server = buildMcpServer(buildBuiltinTools(root, braveApiKey), exposedNames, audit, permissions)

/** The default-path rules: `home/permissions.json`, `projectRoot/.sophi/permissions.json`, trust in `home/permissions-trust.json`. */
fun loadPermissionRules(
    projectRoot: Path,
    home: Path = Path.of(System.getProperty("user.home"), ".sophi"),
): PermissionRules = PermissionRules.load(
    home.resolve("permissions.json"), projectRoot.resolve(".sophi").resolve("permissions.json"), home.resolve("permissions-trust.json")
)
