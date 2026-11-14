package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Composer actions keep their normal density and grow with accessible text sizes. */
@Composable
internal fun AgentModelControl(providerId: String, model: String, height: Dp, logoSize: Dp, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 6.dp, vertical = 0.dp),
        modifier = modifier.height(textControlHeight(MaterialTheme.typography.labelSmall, height)).widthIn(max = if (compact) 120.dp else 180.dp),
    ) {
        ModelBrandMark(providerId, model, null, logoSize)
        Spacer(Modifier.width(if (compact) 3.dp else 5.dp))
        Text(model.ifBlank { "Model" }, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
    }
}

@Composable
internal fun AgentControlButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, height: Dp, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 6.dp, vertical = 0.dp),
        modifier = modifier.height(textControlHeight(MaterialTheme.typography.labelSmall, height)),
    ) {
        if (!compact) {
            Icon(icon, null, Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(if (compact) 11.dp else 13.dp))
    }
}
