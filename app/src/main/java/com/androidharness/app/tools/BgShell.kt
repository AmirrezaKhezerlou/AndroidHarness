package com.androidharness.app.tools

import com.androidharness.app.data.BgProcessStore
import com.androidharness.app.data.env.LinuxEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ShellBackgroundTool(
    private val store: BgProcessStore,
    private val linuxEnv: LinuxEnvironmentManager,
    private val router: com.androidharness.app.data.env.ShellTierRouter? = null,
) : Tool {
    override val name = "shell_background"
    override val description =
        "Start a long-running shell command in the background (e.g. a dev server). " +
        "When Shizuku is connected the process runs inside its server and survives the app " +
        "being minimized or even killed; otherwise it runs as an app child kept alive by the " +
        "foreground service. In Both mode a normal background command uses Shizuku and " +
        "access=\"root\" uses the direct root service, which lasts only while that connection " +
        "survives. Returns a process id; its output goes to a log file. " +
        "Use bg_list to inspect and bg_kill to stop it."
    override val parametersSchema = Schema.obj(
        mapOf("command" to Schema.string("The shell command to run in the background."),
            "access" to Schema.string("auto (default) or root. In Both mode auto uses Shizuku and root uses the direct root service. Root requires a verified backend and project permission.")),
        required = listOf("command"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val rawCommand = args["command"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: command")
            require(args["access"]?.jsonPrimitive?.content in listOf(null, "auto", "root")) { "access must be auto or root" }
            val remote = ctx.workspace as? com.androidharness.app.workspace.SshFs
            if (remote != null) {
                val deny = if (ctx.sandboxOff) null else ShellPolicy.denyReason(rawCommand, java.io.File(remote.root), java.io.File(remote.root))
                if (deny != null) return@withContext ToolResult(false, deny)
                return@withContext com.androidharness.app.workspace.SshJobs.start(remote, rawCommand)
            }
            val cwd = ctx.workspace.shellRoot
                ?: throw ToolFailure(
                    "This workspace has no real filesystem path, so background processes cannot " +
                        "start here. Switch to a device folder or the app workspace.",
                )

            // Full access skips the denylist re-check; the engine already
            // gated this call against the same policy.
            val deny = if (ctx.sandboxOff) null else ShellPolicy.denyReason(rawCommand, cwd, cwd)
            if (deny != null) {
                return@withContext ToolResult(false, deny)
            }

            // Auto-append --no-bin-links for npm install on shared storage (symlinks unsupported).
            val (command, npmNote) = NpmOnSharedStorage.prepare(rawCommand, cwd)

            try {
                val started = store.start(command, cwd)
                val notes = StringBuilder()
                npmNote?.let { notes.append(it).append('\n') }
                if (started.entry.source == "ROOT") notes.append("[note: running via direct root; tied to this root service/app session]\n")
                if (started.viaShizuku) {
                    notes.append("[note: running under Shizuku: survives the app being minimized or killed]\n")
                }
                ToolResult(
                    true,
                    notes.toString() +
                        "Started background process ${started.entry.id}: ${command.take(80)}\n" +
                        "Log: ${started.entry.logPath}",
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // App-tier background processes run as the app uid, which may
                // not reach folders outside its own; start Shizuku for those.
                val hint =
                    if (router?.deviceAccess != null) "[note: check access mode, connection readiness and project root permission in Settings → Terminal & device.]"
                    else "[note: without Shizuku, background processes run as the app user and could not " +
                        "start in $cwd. Start Shizuku (Settings → Terminal) to run servers anywhere, " +
                        "or move the project into the app workspace or a folder the app can reach directly.]"
                ToolResult(false, hint + "\nFailed to start background process: ${e.message}")
            }
        }
}

class BgListTool(
    private val store: BgProcessStore,
) : Tool {
    override val name = "bg_list"
    override val description =
        "List active background jobs and up to 100 recent completed jobs, with exit codes and recent log output."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            (ctx.workspace as? com.androidharness.app.workspace.SshFs)?.let {
                return@withContext com.androidharness.app.workspace.SshJobs.list(it)
            }
            val entries = store.list()
            if (entries.isEmpty()) return@withContext ToolResult(true, "No tracked background jobs.")
            val sb = StringBuilder()
            entries.forEach { (e, alive) ->
                val uptime = ((e.finishedAt ?: System.currentTimeMillis()) - e.startedAt) / 1000
                sb.append("[${e.id}] ")
                    .append(if (alive) "running" else "${e.termination ?: "exited"} (exit ${e.exitCode?.toString() ?: "unknown"})")
                    .append(" (${uptime}s, via ").append(e.source.lowercase()).append(")")
                    .append(if (e.startedAt < store.bootAt) " [prev app session]" else "")
                    .append(" ")
                    .append(e.command.take(100)).append('\n')
                val log = store.tail(e, 800)
                if (log.isNotBlank()) sb.append(log.trimEnd()).append('\n')
            }
            ToolResult(true, sb.toString().trimEnd())
        }
}

class BgKillTool(
    private val store: BgProcessStore,
) : Tool {
    override val name = "bg_kill"
    override val description = "Kill a background shell process by its id."
    override val parametersSchema = Schema.obj(
        mapOf("id" to Schema.integer("The process id returned by shell_background.")),
        required = listOf("id"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val id = args["id"]?.jsonPrimitive?.intOrNull
                ?: throw ToolFailure("Missing required argument: id")
            (ctx.workspace as? com.androidharness.app.workspace.SshFs)?.let {
                return@withContext com.androidharness.app.workspace.SshJobs.stop(it, id)
            }
            val entry = store.list().firstOrNull { it.first.id == id }?.first
            if (entry == null) ToolResult(false, "No background process with id $id.")
            else if (entry.finishedAt != null) ToolResult(true, "Background process $id already ${entry.termination ?: "exited"} (exit ${entry.exitCode?.toString() ?: "unknown"}).")
            else if (store.kill(id)) ToolResult(true, "Killed background process $id.")
            else ToolResult(false, "Failed to kill process $id.")
        }
}
