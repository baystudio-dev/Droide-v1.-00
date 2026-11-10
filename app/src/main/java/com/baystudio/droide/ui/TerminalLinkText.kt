package com.baystudio.droide.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.baystudio.droide.core.TerminalLinks

/** Detect only the tapped token; output bursts must not repeatedly scan the whole transcript. */
@Composable
internal fun TerminalLinkText(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        modifier = modifier.pointerInput(context) {
            detectTapGestures { position ->
                val shown = layout ?: return@detectTapGestures
                val displayedText = shown.layoutInput.text.text
                val offset = shown.getOffsetForPosition(position)

                if (offset !in displayedText.indices || !shown.getBoundingBox(offset).contains(position)) return@detectTapGestures
                TerminalLinks.findAt(displayedText, offset)?.let { TerminalLinkOpener.open(context, it) }
            }
        },
        onTextLayout = { layout = it },
        color = MaterialTheme.colorScheme.onBackground,
        fontFamily = FontFamily.Monospace,
        fontSize = NativeTerminalTextSize.DEFAULT_SP.sp,
        lineHeight = (NativeTerminalTextSize.DEFAULT_SP * 1.3f).sp,
    )
}
