package com.androidharness.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ModelReasoning
import com.androidharness.app.llm.ProviderType

@Composable
fun ModelReasoningIcon(entry: ModelEntry, providerType: ProviderType? = null, directory: Boolean = false) {
    val status = ModelReasoning.of(entry, providerType)
    if (status == ModelReasoning.UNKNOWN || status == ModelReasoning.UNSUPPORTED) return
    var details by remember { mutableStateOf(false) }
    val description = when (status) {
        ModelReasoning.REPORTED -> if (directory) "Reasoning listed in model directory" else "Reasoning reported by provider"
        ModelReasoning.CUSTOM -> "Reasoning set by you"
        else -> "Reasoning inferred from model name"
    }
    IconButton(onClick = { details = true }) {
        Icon(if (status == ModelReasoning.INFERRED) Icons.Outlined.HelpOutline else Icons.Outlined.Psychology,
            contentDescription = description, modifier = Modifier.size(20.dp),
            tint = if (status == ModelReasoning.INFERRED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary)
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(description) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { Text(when (status) {
            ModelReasoning.REPORTED -> if (directory) "The model directory lists reasoning support. This connection has not confirmed it. Visible thinking text depends on the provider."
                else "This connection's catalog reports reasoning support. It does not guarantee that the provider exposes thinking text."
            ModelReasoning.CUSTOM -> "You marked this custom model as a reasoning model. Its provider has not confirmed this setting."
            else -> "This model family may support reasoning, but this connection has not confirmed it. Thinking controls and visible thinking depend on the provider."
        }) } }, confirmButton = { TextButton(onClick = { details = false }) { Text("Got it") } })
}
