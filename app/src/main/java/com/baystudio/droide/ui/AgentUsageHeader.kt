package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.TokenUsage
import com.baystudio.droide.core.AgentContextUsage
import com.baystudio.droide.core.AgentCompactionActivity
import com.baystudio.droide.core.WorkspaceAgentUsageSnapshot
import java.util.Locale

@Composable
internal fun AgentUsageHeaderButton(context: AgentContextUsage?, paneWidth: Dp, onClick: () -> Unit,
    compaction: AgentCompactionActivity? = null) {
    val label = if (compaction?.running == true) "…"
        else if (context == null && compaction?.afterTokens != null) "✓" else agentContextLabel(context)
    AgentUsageHeaderBadge(label, null, "Usage and billing", paneWidth, onClick, compaction?.running == true)
}

internal fun isCompactContextCommand(text: String, hasAttachments: Boolean, external: Boolean): Boolean =
    !external && !hasAttachments && text.trim() == "/compact"

@Composable
internal fun AgentCompactionSummary(activity: AgentCompactionActivity) {
    Text(activity.detail, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    if (activity.running) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Text("Keeping recent requests and task state.", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    } else activity.afterTokens?.let { after ->
        Text("History ≈${formatUsageTokenCount(activity.beforeTokens.toLong())} → ≈${formatUsageTokenCount(after.toLong())} tokens",
            style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        Text("Full chat history is preserved.", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    }
}

internal fun agentContextLabel(context: AgentContextUsage?): String {
    if (context == null) return "?"
    val prefix = if (context.isEstimated) "≈" else ""
    val limit = context.contextLimit?.toLong() ?: return prefix + formatUsageTokenCount(context.usedTokens)
    val percentage = when {
        context.usedTokens > limit -> ">100%"
        context.usedTokens == limit -> "100%"
        context.usedTokens > 0 && context.usedTokens * 100L < limit -> "<1%"
        else -> "${(context.usedTokens * 100L / limit).coerceAtMost(99L)}%"
    }
    return prefix + percentage
}

@Composable
internal fun AgentContextSummary(context: AgentContextUsage?) {
    Text("Context · last request", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    if (context == null) {
        Text("Available after a request with the selected model.", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    } else {
        val prefix = if (context.isEstimated) "≈" else ""
        val limit = context.contextLimit?.let { " / ${formatUsageTokenCount(it.toLong())}" }.orEmpty()
        Text("$prefix${formatUsageTokenCount(context.usedTokens)}$limit tokens", style = MaterialTheme.typography.titleSmall)
        Text(if (context.isEstimated) "Estimated request input; provider breakdown unavailable."
            else "Provider-reported input + output, including cached input.",
            style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        if (context.contextLimit == null) Text("Model context capacity unavailable.",
            style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    }
}

@Composable
internal fun ExternalAgentUsageHeaderButton(usage: WorkspaceAgentUsageSnapshot?, paneWidth: Dp, onClick: () -> Unit) {
    val reported = usage?.takeIf { it.used >= 0L && it.size > 0L }
    val label = when {
        reported == null -> "?"
        reported.used > reported.size -> ">100%"
        reported.used == reported.size -> "100%"
        else -> "${(reported.used.toDouble() / reported.size.toDouble() * 100.0).toInt().coerceAtMost(99)}%"
    }
    AgentUsageHeaderBadge(label, null, "CLI context usage", paneWidth, onClick)
}

@Composable
private fun AgentUsageHeaderBadge(label: String, cost: String?, description: String, paneWidth: Dp, onClick: () -> Unit,
    loading: Boolean = false) {
    val showCost = paneWidth >= 320.dp
    val showTokens = paneWidth >= 280.dp
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 36.dp, max = 120.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.heightIn(min = 24.dp).widthIn(min = 24.dp, max = 112.dp),
            color = DroideColors.Surface2,
            border = BorderStroke(1.dp, DroideColors.Border),
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Row(
                Modifier.padding(horizontal = if (showTokens) 6.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                else Icon(Icons.Default.DataUsage, null, Modifier.size(13.dp), tint = DroideColors.Muted)
                if (showTokens) {
                    Spacer(Modifier.width(4.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                if (showTokens && showCost && cost != null) {
                    Text(" · $cost", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun ExternalAgentUsageQuickMenu(usage: WorkspaceAgentUsageSnapshot?) {
    val reported = usage?.takeIf { it.used >= 0L && it.size > 0L }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Last reported context usage", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (reported == null) {
            Text("This CLI has not reported context usage.", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        } else {
            Text("${formatUsageTokenCount(reported.used)} / ${formatUsageTokenCount(reported.size)} tokens",
                style = MaterialTheme.typography.titleSmall)
            val amount = reported.costAmount?.takeIf { it.isFinite() && it >= 0.0 }
            val currency = reported.costCurrency?.trim()?.takeIf { it.length in 3..12 && it.all { ch -> ch.isLetter() } }
            if (amount != null && currency != null) {
                Text("CLI-reported cost", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                Text("$currency ${String.format(Locale.US, "%.2f", amount)}", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
internal fun AgentUsageQuickMenu(
    usage: TokenUsage,
    canCompact: Boolean,
    onViewBilling: () -> Unit,
    onCompact: () -> Unit,
    context: AgentContextUsage? = null,
    compaction: AgentCompactionActivity? = null,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (compaction != null) {
            AgentCompactionSummary(compaction)
            HorizontalDivider(color = DroideColors.Border)
        }
        AgentContextSummary(context)
        HorizontalDivider(color = DroideColors.Border)
        Text(AGENT_USAGE_SCOPE, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(AGENT_USAGE_CONTEXT_NOTE, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        AgentUsageSummary(usage)
    }
    HorizontalDivider(color = DroideColors.Border)
    DropdownMenuItem(
        text = { Text("View Usage & Billing") },
        leadingIcon = { Icon(Icons.Default.ReceiptLong, null) },
        onClick = onViewBilling,
    )
    DropdownMenuItem(
        text = { Text("Compact session") },
        leadingIcon = { Icon(Icons.Default.Compress, null) },
        enabled = canCompact && compaction?.running != true,
        onClick = onCompact,
    )
}

@Composable
internal fun AgentUsageSummary(usage: TokenUsage) {
    Text(usageRequestCountLabel(usage.requestCount), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    if (usageHasKnownTokens(usage)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(usageTokenValue(usage), style = MaterialTheme.typography.titleSmall)
            Text(" tokens", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        }
    }
    Text(usageTokenAuthorityLabel(usage), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    usageTokenCoverageLabel(usage)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
    Text(usageCostSummary(usage), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    usageCostCoverageLabel(usage)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
    HorizontalDivider(color = DroideColors.Border)
    if (usage.breakdownComplete) {
        AgentUsageRow("Input", usage.prompt)
        AgentUsageRow("Output", usage.completion)
        if (usage.reasoning > 0) AgentUsageRow("Reasoning", usage.reasoning)
        if (usage.cacheRead > 0) AgentUsageRow("Cache read", usage.cacheRead)
        if (usage.cacheWrite > 0) AgentUsageRow("Cache write", usage.cacheWrite)
    } else {
        Text(AGENT_USAGE_BREAKDOWN_NOTE, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    }
    if (usage.breakdownMismatch) Text(AGENT_USAGE_TOTAL_NOTE, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
}

@Composable
private fun AgentUsageRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = DroideColors.Muted)
        Spacer(Modifier.weight(1f))
        Text(formatUsageTokenCount(value.toLong()), style = MaterialTheme.typography.labelMedium, color = DroideColors.Text)
    }
}
