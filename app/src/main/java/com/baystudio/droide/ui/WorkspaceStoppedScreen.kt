package com.baystudio.droide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders state only. Opening work, exit guards and failure delivery stay with the owner. */
@Composable
internal fun WorkspaceStoppedScreen(
    canOpen: Boolean,
    failure: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DroideSessionLayout(modifier) {
        DroideSessionLogo(88.dp)
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.border(1.dp, Color(0xFF4D596B), CircleShape)
                .background(Color(0xFF202833), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(7.dp).background(DroideSessionColors.Muted, CircleShape))
            Text("Session stopped", color = DroideSessionColors.Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Ready when you are",
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (failure == null) {
            Text(
                "Your files and recovery drafts\nare still available.",
                color = DroideSessionColors.Muted,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            // Do not hide a failed open behind the normal reassurance. Long errors can scroll.
            Text(
                failure,
                modifier = Modifier.fillMaxWidth().semantics {
                    liveRegion = LiveRegionMode.Polite
                    error(failure)
                },
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 285.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onOpen,
                enabled = canOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DroideSessionColors.Accent,
                    contentColor = Color(0xFF061A2E),
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // Reserve the same space on both sides: the title stays centered even
                    // with an end arrow, and enlarged text can wrap without touching it.
                    Text(
                        "Open Droide",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.CenterEnd).size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Previous tasks won’t restart automatically.",
                color = DroideSessionColors.Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
