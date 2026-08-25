package dev.sophi.sdk

import dev.sophi.ai.providers.BraveSearchProvider
import dev.sophi.core.tools.BashTool
import dev.sophi.core.tools.EditTool
import dev.sophi.core.tools.FetchUrlTool
import dev.sophi.core.tools.FileReadTool
import dev.sophi.core.tools.FileWriteTool
import dev.sophi.core.tools.GetCurrentDateTimeTool
import dev.sophi.core.tools.GlobTool
import dev.sophi.core.tools.GrepTool
import dev.sophi.core.tools.RunClaudeCodeTool
import dev.sophi.core.tools.Tool
import dev.sophi.core.tools.WebSearchTool
import java.nio.file.Path

/**
 * The standard file/shell/search/date tool set every interactive Sophi host registers.
 * [root] scopes every filesystem-touching tool (read/write/grep/glob/edit/bash); defaults to the
 * process's current directory, matching those tools' own constructor defaults. Falls back to the
 * `BRAVE_SEARCH_API_KEY` environment variable when [braveApiKey] is null; omit both to leave
 * `web_search` unregistered.
 */
fun buildBuiltinTools(root: Path = Path.of("").toAbsolutePath(), braveApiKey: String? = null): List<Tool> {
    val tools = mutableListOf<Tool>(
        FileReadTool(root), FileWriteTool(root), GrepTool(root), GlobTool(root), EditTool(root), BashTool(root),
        FetchUrlTool(), GetCurrentDateTimeTool(), RunClaudeCodeTool()
    )
    val key = braveApiKey ?: System.getenv("BRAVE_SEARCH_API_KEY")
    if (key != null) {
        tools.add(WebSearchTool(BraveSearchProvider(key)))
    }
    return tools
}
