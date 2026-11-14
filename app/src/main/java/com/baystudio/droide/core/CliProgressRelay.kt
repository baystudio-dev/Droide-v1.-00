package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One bounded writer, joined before the final frame; slow clients never block installer callbacks. */
internal object CliProgressRelay {
    suspend fun execute(
        operation: suspend () -> DroideCliExecutionResult,
        writeProgress: (String) -> Unit,
    ): DroideCliExecutionResult = coroutineScope {
        val pending = Channel<PackageProgressSnapshot>(Channel.CONFLATED)
        val text = ProgressText()
        val writer = launch(Dispatchers.IO) {
            for (snapshot in pending) {
                val chunk = text.render(snapshot)
                if (chunk.isNotEmpty()) {
                    writeProgress(chunk)
                    delay(100) // At most ten frames/second, including a continuously noisy child.
                }
            }
        }
        try {
            withContext(PackageProgressObserver { pending.trySend(it) }) { operation() }
        } finally {
            pending.close()
            writer.join()
        }
    }

    internal class ProgressText {
        private var previousLog = ""
        private var previousStage = ""
        private var previousMessage = ""

        fun render(snapshot: PackageProgressSnapshot): String {
            val currentLog = snapshot.log.takeLast(8_192)
            val logChanged = currentLog != previousLog
            val stageChanged = snapshot.stage != previousStage
            val activityChanged = snapshot.message != previousMessage
            val output = buildString {
                if (stageChanged || activityChanged && !logChanged) {
                    append("[Droide] ").append(snapshot.stage.take(80)).append(": ")
                        .append(snapshot.message.take(300)).append('\n')
                }
                if (logChanged && currentLog.isNotEmpty()) {
                    val delta = when {
                        currentLog.startsWith(previousLog) -> currentLog.removePrefix(previousLog)
                        else -> {
                            // A rolling log or a redaction may revise a previous prefix. Only use
                            // an unambiguous substantial overlap; otherwise label the recent tail.
                            val anchor = previousLog.takeLast(512)
                            val offset = if (anchor.length >= 128) currentLog.indexOf(anchor) else -1
                            if (offset >= 0 && currentLog.indexOf(anchor, offset + 1) < 0) {
                                currentLog.substring(offset + anchor.length)
                            } else "\n[recent installer output; earlier lines may be omitted]\n$currentLog"
                        }
                    }
                    append(delta)
                }
            }
            previousLog = currentLog
            previousStage = snapshot.stage
            previousMessage = snapshot.message
            return output
        }
    }
}
