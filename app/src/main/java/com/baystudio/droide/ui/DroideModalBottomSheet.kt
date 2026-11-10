package com.baystudio.droide.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DroideModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val onDismiss = rememberUpdatedState(onDismissRequest)
    val dismissal = remember(sheetState, scope) {
        DroideSheetDismissal(scope, hide = { sheetState.hide() }, hidden = { !sheetState.isVisible },
            onDismiss = { onDismiss.value() })
    }
    ModalBottomSheet(onDismissRequest = dismissal::dismissed, sheetState = sheetState,
        containerColor = containerColor) {
        content(dismissal::request)
    }
}

/** UI-scope cancellation retires pending dismissal without closing a replacement surface. */
internal class DroideSheetDismissal(
    private val scope: CoroutineScope,
    private val hide: suspend () -> Unit,
    private val hidden: () -> Boolean,
    private val onDismiss: () -> Unit,
) {
    private var closing = false
    private var completed = false

    fun request() {
        if (closing || completed) return
        closing = true
        scope.launch {
            try {
                hide()
                if (hidden()) dismissed()
            } finally { closing = false }
        }
    }

    // Material calls this only when its scrim/back/drag dismissal has finished.
    fun dismissed() {
        if (completed) return
        completed = true
        onDismiss()
    }
}
