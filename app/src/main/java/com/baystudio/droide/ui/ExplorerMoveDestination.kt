package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Destination navigation is separate from the Explorer's active folder and never creates a path. */
@Composable
internal fun ExplorerMoveDestination(
    files: FileRepository, source: FileEntry, path: String, enabled: Boolean, onPathChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun navigate(directory: String) { focus.clearFocus(); keyboard?.hide(); onPathChange(directory) }
    key(files, source.path, path) {
        var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
        var cursor by remember { mutableStateOf<DirectoryPageCursor?>(null) }
        var request by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(request) {
            busy = true
            error = null
            try {
                val directory = withContext(Dispatchers.IO) {
                    ExplorerFilePolicy.requireUserTarget(files.root, path).also {
                        check(files.resolveChecked(it).isDirectory) { "Choose an existing project folder" }
                        check(!source.isDir || (it != source.path && !it.startsWith("${source.path}/"))) {
                            "A folder cannot be moved into itself"
                        }
                    }
                }
                val page = files.listFilesPage(directory, cursor, pageSize = 50, options = DirectoryListingOptions.EXPLORER)
                entries = (entries + page.entries.filter { it.isDir && it.path != source.path &&
                    (!source.isDir || !it.path.startsWith("${source.path}/")) }).distinctBy { it.path }
                // Explorer ordering puts directories first. Never scan file-only pages to browse folders.
                cursor = page.nextCursor?.takeIf { it.isDir }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not load folders" }
            finally { busy = false }
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { navigate("") }, enabled = enabled) { Text("Project root") }
                IconButton(onClick = { navigate(path.trimEnd('/').substringBeforeLast('/', "")) },
                    enabled = enabled && path.isNotBlank()) { Icon(Icons.Default.ArrowUpward, "Parent folder") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { message ->
                Text(message, style = MaterialTheme.typography.labelSmall, color = DroideColors.Error)
                TextButton(onClick = { request++ }, enabled = enabled && !busy) { Text("Retry folders") }
            }
            val height = (LocalConfiguration.current.screenHeightDp * .23f).coerceIn(72f, 180f).dp
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = height)) {
                items(entries, key = { it.path }) { folder ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = enabled && !busy) { navigate(folder.path) }
                        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, Modifier.size(20.dp), tint = DroideColors.Warning)
                        Text(folder.path.substringAfterLast('/'), Modifier.weight(1f).padding(horizontal = 8.dp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ChevronRight, "Open destination folder", Modifier.size(20.dp))
                    }
                }
                if (cursor != null) item {
                    TextButton(onClick = { request++ }, enabled = enabled && !busy) { Text("More folders") }
                }
                if (!busy && error == null && entries.isEmpty()) item {
                    Text("No subfolders", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall,
                        color = DroideColors.Muted)
                }
            }
            Text("Move to /${if (path.isBlank()) "" else path.trimEnd('/') + "/"}${source.path.substringAfterLast('/')}",
                style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
