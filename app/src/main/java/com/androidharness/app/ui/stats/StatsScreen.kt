package com.androidharness.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.db.CodeActivityTotals
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine

private enum class StatsSort(val label: String) { TOKENS("Tokens"), PRICE("Price"), REQUESTS("Requests") }
private data class UsageView(val stats: StatsSnapshot = StatsSnapshot(), val code: CodeActivityTotals = CodeActivityTotals())

@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit) = StatsScreen(container.sessions, onBack)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsScreen(repository: SessionRepository, onBack: () -> Unit) {
    var range by rememberSaveable { mutableStateOf(StatsRange.WEEK) }
    var sort by rememberSaveable { mutableStateOf(StatsSort.TOKENS) }
    var definitions by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = System.currentTimeMillis(); delay(60_000) }
        }
    }
    val window = range.window(now)
    val flow = remember(repository, window, range) {
        combine(repository.sessions, repository.statsUsageBetween(window.since, window.until), repository.codeActivityBetween(window.since, window.until)) { sessions, usage, code ->
            UsageView(statsSnapshot(usage, sessions.takeIf { range == StatsRange.LIFETIME }), code)
        }
    }
    val view = key(window, range) { flow.collectAsStateWithLifecycle(initialValue = UsageView()).value }
    val bundle = view.stats.bundle
    val models = view.stats.byModel.map { row -> row to com.androidharness.app.llm.ModelPrices.estimate(
        row.model, row.inputTokens, row.outputTokens, row.cachedTokens, row.cacheWriteTokens) }
    val knownPrices = models.mapNotNull { it.second }
    val cost = knownPrices.takeIf { it.isNotEmpty() }?.sum()
    val code = view.code
    val scheme = MaterialTheme.colorScheme
    val green = LocalStatusColors.current.success
    Scaffold(containerColor = scheme.surface, topBar = {
        AppHeader("Usage", subtitle = "Model activity and AI code changes", onBack = onBack)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (LocalDensity.current.fontScale > 1.2f) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatsRange.entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(selected = range == option, onClick = { range = option }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                label = { Text(option.label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) })
                        }
                    }
                }
            } else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                StatsRange.entries.forEachIndexed { index, option ->
                    SegmentedButton(selected = range == option, onClick = { range = option }, icon = {},
                        shape = SegmentedButtonDefaults.itemShape(index, StatsRange.entries.size)) {
                        Text(option.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MODEL ACTIVITY", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer)
                    Text(formatTokenCount(bundle.input + bundle.output), style = MaterialTheme.typography.displayMedium,
                        fontFamily = HarnessMono, color = scheme.onPrimaryContainer)
                    Text("total tokens · ${bundle.requests} ${if (bundle.requests == 1L) "request" else "requests"} · ${bundle.sessionCount} ${if (bundle.sessionCount == 1) "session" else "sessions"}",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
                    HorizontalDivider(color = scheme.onPrimaryContainer.copy(alpha = .18f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        UsageMetric("Estimated cost", cost?.let { "$${"%.2f".format(it)}" } ?: "Unavailable", Modifier.weight(1f))
                        UsageMetric("Cache hit rate", if (bundle.input > 0) "${"%.1f".format(bundle.cached.toDouble() / bundle.input * 100)}%" else "—", Modifier.weight(1f))
                    }
                    if (knownPrices.size < models.size) Text("Cost includes models with known list prices only.", style = MaterialTheme.typography.labelSmall)
                }
            }
            UsageCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Code, null, tint = scheme.primary)
                    Column {
                        Text("AI code changes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${range.label} · ${code.edits} tracked edits", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = green.copy(alpha = .09f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("+${lineCount(code.added)}", style = if (lineCount(code.added).length > 8) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, fontFamily = HarnessMono, color = green)
                            Text("Lines added", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = scheme.errorContainer.copy(alpha = .45f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("−${lineCount(code.removed)}", style = if (lineCount(code.removed).length > 8) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, fontFamily = HarnessMono, color = scheme.error)
                            Text("Lines removed", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                val total = code.added + code.removed
                if (total > 0) Row(Modifier.fillMaxWidth().height(6.dp)) {
                    if (code.added > 0) Box(Modifier.weight(code.added.toFloat() / total).fillMaxHeight().background(green, RoundedCornerShape(3.dp)))
                    if (code.removed > 0) Box(Modifier.weight(code.removed.toFloat() / total).fillMaxHeight().background(scheme.error, RoundedCornerShape(3.dp)))
                }
                val net = code.added - code.removed
                Text("Net change  ${if (net >= 0) "+" else "−"}${lineCount(kotlin.math.abs(net))} lines", style = MaterialTheme.typography.bodyMedium)
                if (code.edits == 0L) Text("No tracked code changes in this period.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text("Source code and configuration · totals stay after chat deletion", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            UsageCard {
                Text("Token breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    UsageMetric("Fresh input", formatTokenCount(bundle.freshInput), Modifier.weight(1f))
                    UsageMetric("Output", formatTokenCount(bundle.output), Modifier.weight(1f))
                }
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = .5f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    UsageMetric("Cache reads", formatTokenCount(bundle.cached), Modifier.weight(1f))
                    UsageMetric("Cache writes", formatTokenCount(bundle.cacheWrite), Modifier.weight(1f))
                }
            }
            UsageCard {
                Text("By model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    StatsSort.entries.forEachIndexed { index, option ->
                        SegmentedButton(selected = sort == option, onClick = { sort = option }, icon = {}, shape = SegmentedButtonDefaults.itemShape(index, StatsSort.entries.size)) { Text(option.label) }
                    }
                }
                val sorted = when (sort) {
                    StatsSort.TOKENS -> models.sortedByDescending { it.first.totalTokens }
                    StatsSort.PRICE -> models.sortedByDescending { it.second ?: -1.0 }
                    StatsSort.REQUESTS -> models.sortedByDescending { it.first.requests }
                }
                if (sorted.isEmpty()) Text("No dated model activity in this period. Earlier usage is available in Lifetime.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                val max = sorted.maxOfOrNull { it.first.totalTokens }?.coerceAtLeast(1) ?: 1
                sorted.forEachIndexed { index, (model, price) ->
                    if (index > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = .45f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(model.model, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${model.providerName} · ${model.requests} requests", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${formatTokenCount(model.totalTokens)} tokens", fontFamily = HarnessMono, style = MaterialTheme.typography.bodySmall)
                            Text(price?.let { "≈ $${"%.2f".format(it)}" } ?: "Price unavailable", style = MaterialTheme.typography.bodySmall)
                        }
                        LinearProgressIndicator(progress = { model.totalTokens.toFloat() / max }, modifier = Modifier.fillMaxWidth().height(4.dp))
                    }
                }
            }
            UsageCard {
                TextButton(onClick = { definitions = !definitions }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    Text("How Usage is counted", Modifier.weight(1f), color = scheme.onSurface)
                    Icon(if (definitions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (definitions) {
                    Text("AI code changes count applied source and configuration edits, including repeated edits and later reversals. Net is added minus removed activity, not the current Git diff. Manual editor saves, documentation, generated files, dependencies and binary files are excluded.", style = MaterialTheme.typography.bodySmall)
                    Text("Tracked file tools and eligible local foreground shell changes are included. Background commands, remote shell changes and comparisons beyond size or work limits can be missing. Earlier totals use retained edit records; deleted history cannot be recovered. No file contents are kept in Usage.", style = MaterialTheme.typography.bodySmall)
                    Text("Today uses your device's calendar day. 1 week includes today and the previous 6 days; 1 month includes today and the previous 29 days. Undated token usage appears only in Lifetime.", style = MaterialTheme.typography.bodySmall)
                    Text("Token totals include subagents and compaction. Cache reads and writes are separate from fresh input. Prices are estimates from the bundled model price list, not provider invoices.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun UsageCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun lineCount(value: Long): String = java.text.NumberFormat.getIntegerInstance().format(value)

@Composable
private fun UsageMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = HarnessMono)
    }
}
