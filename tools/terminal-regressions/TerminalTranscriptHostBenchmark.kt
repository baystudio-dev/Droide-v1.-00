package com.termux.terminal

import com.baystudio.droide.core.TerminalTranscriptPublisher
import java.security.MessageDigest
import kotlin.system.measureNanoTime

/** Uses the preserved upstream TerminalBuffer on a JVM, with a deterministic event clock. */
object TerminalTranscriptHostBenchmark {
    private const val COLUMNS = 80
    private const val SCREEN_ROWS = 24
    private const val HISTORY_ROWS = 6_000
    private const val EVENTS = 200
    private const val EVENT_INTERVAL_MS = 10L
    private const val OUTPUT_CAP = 200_000
    private data class Job(val due: Long, val action: () -> Unit, var canceled: Boolean = false)
    private data class Result(val mode: String, val captures: Int, val scanNs: Long, val totalNs: Long, val digest: String)

    private fun addLine(buffer: TerminalBuffer, line: String) {
        buffer.scrollDownOneLine(0, SCREEN_ROWS, TextStyle.NORMAL)
        line.take(COLUMNS).forEachIndexed { col, char -> buffer.setChar(col, SCREEN_ROWS - 1, char.code, TextStyle.NORMAL) }
    }

    private fun run(coalesce: Boolean): Result {
        val buffer = TerminalBuffer(COLUMNS, 10_000, SCREEN_ROWS)
        repeat(HISTORY_ROWS) { addLine(buffer, "Install history $it: signed package data, extracting files, configuring runtime.") }
        var captures = 0
        var scanNs = 0L
        var output = ""
        var clock = 0L
        val jobs = mutableListOf<Job>()
        val capture: () -> String = {
            var snapshot = ""
            scanNs += measureNanoTime { snapshot = buffer.transcriptText.takeLast(OUTPUT_CAP) }
            captures++
            snapshot
        }
        val publisher = TerminalTranscriptPublisher(
            schedule = { delay, action ->
                val job = Job(clock + delay, action)
                jobs += job
                val cancellation = { job.canceled = true }
                cancellation
            },
            capture = capture,
            publish = { output = it },
        )
        val totalNs = measureNanoTime {
            repeat(EVENTS) { event ->
                addLine(buffer, "Get:$event Ubuntu package - verified metadata and complete event payload.")
                if (coalesce) publisher.request() else output = capture()
                clock += EVENT_INTERVAL_MS
                val due = jobs.filter { it.due <= clock }
                jobs.removeAll(due.toSet())
                due.filterNot { it.canceled }.forEach { it.action() }
            }
            addLine(buffer, "Complete final install output: all 200 events retained in terminal history.")
            if (coalesce) publisher.finish() else output = capture()
        }
        check(output.contains("Complete final install output"))
        val digest = MessageDigest.getInstance("SHA-256").digest(output.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return Result(if (coalesce) "coalesced" else "baseline", captures, scanNs, totalNs, digest)
    }

    @JvmStatic fun main(args: Array<String>) {
        // A small warmup includes both implementations, then alternates order across three runs.
        val warmBaseline = run(false)
        val warmCoalesced = run(true)
        check(warmBaseline.digest == warmCoalesced.digest)
        repeat(3) { iteration ->
            val results = if (iteration % 2 == 0) listOf(run(false), run(true)) else listOf(run(true), run(false))
            check(results[0].digest == results[1].digest) { "Final transcript content changed" }
            results.forEach { result ->
                println("$iteration,${result.mode},${result.captures},${result.scanNs},${result.totalNs},${result.digest}")
            }
        }
    }
}
