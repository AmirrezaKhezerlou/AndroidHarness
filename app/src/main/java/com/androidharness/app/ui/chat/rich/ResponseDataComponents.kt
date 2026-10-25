package com.androidharness.app.ui.chat.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.androidharness.app.ui.chat.AnswerTableSurface
import com.androidharness.app.ui.chat.AnswerTableScrollHint
import com.androidharness.app.R
import com.androidharness.app.core.response.RichResponse

/** Latest explicitly changed component state can accompany a normally typed follow-up. */
internal val LocalResponseContext = compositionLocalOf<((String, String) -> Unit)?> { null }
internal val LocalResponseFileOpen = compositionLocalOf<((String, Int?) -> Unit)?> { null }

@Composable
internal fun ResponseTable(table: RichResponse.Node.Table, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    var query by rememberSaveable(table) { mutableStateOf("") }
    var sort by rememberSaveable(table) { mutableIntStateOf(-1) }
    var ascending by rememberSaveable(table) { mutableStateOf(true) }
    var selected by rememberSaveable(table) { mutableStateOf(List(table.rows.size) { false }) }
    var page by rememberSaveable(table) { mutableIntStateOf(0) }
    val contextChange = LocalResponseContext.current
    val ui = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val tableScroll = rememberScrollState()
    fun context(): String = buildString {
        append("Filter: ").append(query.ifBlank { "all" }).append("\n")
        if (sort >= 0) append("Sort: ${table.columns[sort]} ${if (ascending) "ascending" else "descending"}\n")
        append(table.columns.joinToString(" | ")).append('\n')
        val chosen = table.rows.indices.filter { selected[it] }
        append(if (chosen.isEmpty()) "No rows selected." else "Selected rows:\n" + chosen.joinToString("\n") { table.rows[it].joinToString(" | ") })
    }
    val filtered = remember(table, query, sort, ascending) { com.androidharness.app.core.response.ResponseTableData.indices(table, query, sort, ascending) }
    val pages = ((filtered.size + 9) / 10).coerceAtLeast(1)
    AnswerTableSurface {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(table.title, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(query, { query = it.take(120); page = 0; contextChange?.invoke(table.title, context()) },
            label = { Text(stringResource(R.string.rich_filter_rows)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val cellWidth = maxOf((96 * fontScale).dp, (maxWidth - 48.dp) / table.columns.size)
            Column(Modifier.horizontalScroll(tableScroll).width(48.dp + cellWidth * table.columns.size)) {
                Row(Modifier.fillMaxWidth().background(colors.secondaryContainer.copy(alpha = .55f)), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(48.dp))
                    table.columns.forEachIndexed { index, label ->
                        TextButton(onClick = {
                            if (sort == index) ascending = !ascending else { sort = index; ascending = true }
                            page = 0; contextChange?.invoke(table.title, context())
                        }, modifier = Modifier.width(cellWidth)) { Text(label + if (sort == index) (if (ascending) " ↑" else " ↓") else " ↕", fontWeight = FontWeight.SemiBold) }
                    }
                }
                filtered.drop(page.coerceAtMost(pages - 1) * 10).take(10).forEach { index -> key(index) {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (selected[index]) colors.secondaryContainer.copy(alpha = .4f)
                        else if (filtered.indexOf(index) % 2 == 1) colors.surfaceContainerHigh.copy(alpha = .4f) else colors.surfaceContainerLow).toggleable(selected[index], role = Role.Checkbox, onValueChange = { checked ->
                        selected = selected.toMutableList().also { it[index] = checked }
                        contextChange?.invoke(table.title, context())
                    }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected[index], null, Modifier.size(48.dp))
                        table.rows[index].forEach { cell -> Text(cell, Modifier.width(cellWidth).padding(8.dp), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content)) }
                    }
                } }
            }
        }
        AnswerTableScrollHint(tableScroll)
        if (filtered.isEmpty()) Text(stringResource(R.string.rich_no_rows))
        if (selected.any { it }) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.rich_selected_count, selected.count { it }), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { selected = List(table.rows.size) { false }; contextChange?.invoke(table.title, context()) }) {
                Text(stringResource(R.string.rich_clear_selection))
            }
        }
        if (pages > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text(stringResource(R.string.rich_previous)) }
            Text("${page + 1} / $pages", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { page++ }, enabled = page < pages - 1) { Text(stringResource(R.string.rich_next)) }
        }
        if (table.caption.isNotBlank()) Text(table.caption, style = MaterialTheme.typography.bodySmall)
        ResponseCopyShare(table.title, com.androidharness.app.core.response.ResponseTableData.csv(table, filtered), "text/csv")
        onFollowUp?.let { follow -> TextButton(onClick = {
            follow(RichResponse.followUp(ui.getString(R.string.rich_discuss_rows_prompt), table.title, context()))
        }, enabled = selected.any { it }) { Text(stringResource(R.string.rich_discuss_rows)) } }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResponseTabs(tabs: RichResponse.Node.Tabs, onOpenUrl: ((String) -> Unit)?, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    var selected by rememberSaveable(tabs) { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    var contexts by rememberSaveable(tabs) { mutableStateOf(List(tabs.tabs.size) { "" }) }
    val contextChange = LocalResponseContext.current
    val tab = tabs.tabs[selected.coerceIn(tabs.tabs.indices)]
    val ui = LocalContext.current
    ResponsePanel {
        Text(tabs.title, style = MaterialTheme.typography.titleSmall)
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp) {
            tabs.tabs.forEachIndexed { index, item -> Tab(selected == index, onClick = {
                selected = index
                contextChange?.invoke(tabs.title, "Selected tab: ${item.title}\n" + contexts[index].ifBlank { item.children.joinToString("\n", transform = RichResponse::describe) })
            }, text = { Text(item.title) }) }
        }
        holder.SaveableStateProvider(selected) {
            CompositionLocalProvider(LocalResponseContext provides { title, context ->
                val childContext = "$title\n$context".take(1500)
                contexts = contexts.toMutableList().also { it[selected] = childContext }
                contextChange?.invoke(tabs.title, "Selected tab: ${tab.title}\n$childContext")
            }) {
                tab.children.forEachIndexed { index, child -> key(index) { ResponseNode(child, onOpenUrl, onFollowUp) } }
            }
        }
        onFollowUp?.let { follow -> TextButton(onClick = {
            follow(RichResponse.followUp(ui.getString(R.string.rich_discuss_tab_prompt, tab.title), tabs.title,
                "Selected tab: ${tab.title}\n" + contexts[selected].ifBlank { tab.children.joinToString("\n", transform = RichResponse::describe) }))
        }) { Text(stringResource(R.string.rich_discuss_tab)) } }
    }
}
