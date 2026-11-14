package com.baystudio.droide.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay

 
@Composable
internal fun droidePhysicalLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE






@Composable
internal fun droidePhysicalWindowHeightDp(): Dp =
    LocalConfiguration.current.screenHeightDp.dp






@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberDroideImeVisible(graceMs: Long = 280L): Boolean {
    // Callers need a visibility boundary, not a new composition for every positive IME pixel.
    val bottomPresent = rememberImeBottomPresent(WindowInsets.ime)
    val rawVisible = WindowInsets.isImeVisible || bottomPresent
    var stableVisible by remember { mutableStateOf(rawVisible) }
    LaunchedEffect(rawVisible, graceMs) {
        if (rawVisible) stableVisible = true
        else {
            delay(graceMs)
            stableVisible = false
        }
    }
    return rawVisible || stableVisible
}

@Composable
internal fun rememberImeBottomPresent(ime: WindowInsets): Boolean {
    val density = LocalDensity.current
    val present by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    return present
}

internal fun droideTypingImeActive(imeVisible: Boolean, target: AccessoryInputTarget): Boolean =
    imeVisible && target.keyProfile() != null
