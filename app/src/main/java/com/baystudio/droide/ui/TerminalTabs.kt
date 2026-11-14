package com.baystudio.droide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.TerminalManager

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TerminalTabs(
    snapshot: TerminalManager.Snapshot,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onCreate: (() -> Unit)? = null,
    createEnabled: Boolean = true,
) {
    if (snapshot.tabs.isEmpty() && onCreate == null) return
    val tabHeight = textControlHeight(MaterialTheme.typography.labelLarge, DroideDimensions.EditorTabBar)
    Column(Modifier.fillMaxWidth().testTag("terminal-tab-strip").background(DroideColors.Surface)) {
        // One row keeps creation within reach without taking another terminal line.
        // Fixed bounds avoid intrinsic measurements and indicator indices while tabs change.
        Row(Modifier.fillMaxWidth().height(tabHeight), verticalAlignment = Alignment.CenterVertically) {
          Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            snapshot.tabs.forEach { tab ->
                key(tab.id) {
                    val selected = tab.id == snapshot.activeId
                    val bringIntoView = remember { BringIntoViewRequester() }
                    LaunchedEffect(selected) { if (selected) bringIntoView.bringIntoView() }
                    Box(Modifier.width(128.dp).height(tabHeight).bringIntoViewRequester(bringIntoView)) {
                        Row(
                            Modifier.fillMaxSize().selectable(selected, role = Role.Tab, onClick = { onSelect(tab.id) })
                                .padding(start = 12.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tab.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            IconButton(onClick = { onClose(tab.id) }, Modifier.size(DroideDimensions.EditorTabBar)) {
                                Icon(Icons.Default.Close, "Close terminal ${tab.title}", Modifier.size(18.dp))
                            }
                        }
                        HorizontalDivider(Modifier.align(Alignment.BottomCenter), thickness = 2.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else DroideColors.Border)
                    }
                }
            }
          }
          if (onCreate != null) {
              TextButton(onClick = onCreate, enabled = createEnabled,
                  modifier = Modifier.widthIn(min = 96.dp, max = 160.dp).height(tabHeight), contentPadding = PaddingValues(horizontal = 8.dp)) {
                  Text("+ Ubuntu", maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
          }
        }
    }
}
