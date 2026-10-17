package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.CheckStatus
import com.androidharness.app.agent.VerificationCheck
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors

/** A quiet receipt; command outcomes stay available without competing with the answer. */
@Composable
internal fun VerificationCard(checks: List<VerificationCheck>) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalStatusColors.current
    var expanded by rememberSaveable(checks) { mutableStateOf(false) }
    val failed = checks.count { it.status == CheckStatus.FAILED }
    val summary = if (checks.isEmpty()) "Verification · Checks not run" else
        "Verification · " + checks.groupingBy { it.status }.eachCount().entries.joinToString(" · ") { (status, count) ->
            "$count " + when (status) {
                CheckStatus.PASSED -> "passed"
                CheckStatus.FAILED -> "failed"
                CheckStatus.UNKNOWN -> "unconfirmed"
                CheckStatus.STALE -> "need recheck"
            }
        }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(summary, style = MaterialTheme.typography.labelMedium,
            color = if (failed > 0) scheme.error else scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp))
        if (expanded) {
            if (checks.isEmpty()) Text("No build, test, or lint result recorded for this turn.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            checks.forEach { check ->
                val label = when (check.status) {
                    CheckStatus.PASSED -> "Passed"
                    CheckStatus.FAILED -> "Failed"
                    CheckStatus.UNKNOWN -> "Unconfirmed"
                    CheckStatus.STALE -> "Recheck needed"
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = when (check.status) {
                    CheckStatus.PASSED -> colors.success
                    CheckStatus.FAILED -> scheme.error
                    else -> scheme.onSurfaceVariant
                })
                Text(check.command, fontFamily = HarnessMono, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
