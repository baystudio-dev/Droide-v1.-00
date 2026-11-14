package com.baystudio.droide.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.R

internal object DroideSessionColors {
    val Accent = Color(0xFF32A7FF)
    val Muted = Color(0xFFA8B2C2)
}

/** Shared presentation for startup/stopped states. The Activity owns insets and lifecycle. */
@Composable
internal fun DroideSessionLayout(
    modifier: Modifier = Modifier, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Center when it fits; scroll above the footer in short windows or with large text.
            Column(
                Modifier.widthIn(max = 380.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
        if (footer != null) footer() else Text(
            "Your workspace, anywhere.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            color = DroideSessionColors.Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun DroideSessionLogo(size: Dp = 96.dp) {
    Box(
        Modifier.size(size)
            .drawWithCache {
                val radius = this.size.width * 1.15f
                val glow = Brush.radialGradient(
                    listOf(DroideSessionColors.Accent.copy(alpha = .12f), Color.Transparent),
                    center = Offset(this.size.width / 2f, this.size.height / 2f),
                    radius = radius,
                )
                onDrawBehind { drawCircle(glow, radius = radius) }
            }
            .clip(RoundedCornerShape(size / 4)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        // Use the original vector, not the adaptive launcher drawable. A larger canvas
        // compensates for launcher safe-zone padding without scaling a rasterized layer.
        Image(
            painterResource(R.drawable.ic_droide_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}
