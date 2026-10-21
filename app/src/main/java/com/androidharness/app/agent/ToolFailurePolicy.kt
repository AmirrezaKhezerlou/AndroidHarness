package com.androidharness.app.agent

import com.androidharness.app.tools.ToolResult

/** Files, dependencies and backends may change between identical calls. */
internal fun immutableToolFailureHint(result: ToolResult): String? {
    if (result.ok) return null
    val text = result.output.trimStart()
    return if (listOf("Missing required argument:", "Invalid tool arguments:", "Unknown tool:").any {
        text.startsWith(it, ignoreCase = true)
    }) "Fix the tool name or arguments before retrying." else null
}
