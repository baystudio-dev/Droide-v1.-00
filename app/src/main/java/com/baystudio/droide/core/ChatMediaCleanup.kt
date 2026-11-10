package com.baystudio.droide.core

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One expiry job, no periodic polling, network, wake lock, transcript scan or project-directory scan. */
object ChatMediaCleanup {
    internal const val JOB_ID = 0x44524D
    internal const val EXTRA_EXPIRY = "chat_media_expiry"
    internal const val EXTRA_ADMISSION_EPOCH = "chat_media_admission_epoch"
    private const val MAX_WORKSPACES = 128
    private const val MAX_REGISTRY_BYTES = 64L * 1024
    private const val RETRY_DELAY_MS = 15L * 60 * 1000
    private val lock = Mutex()
    private data class RegisteredWorkspace(val relative: String, val expiry: Long)

    /** Call from an existing import/send coroutine; original gallery/project files are never enrolled. */
    suspend fun schedule(context: Context, workDir: File): String? = withContext(Dispatchers.IO) {
        guarded {
            val app = context.applicationContext
            ApplicationWorkAdmission.initialize(app)
            val epoch = ApplicationWorkAdmission.requireOpen()
            lock.withLock {
                ensureAdmission(epoch)
                val relative = ChatMediaAccess.relativeWorkspace(app, workDir)
                val old = readRegistry(app)
                val store = ChatMediaStore(workDir)
                store.cleanupExpired()
                ensureAdmission(epoch)
                AgentMediaScratch.cleanup(workDir)
                val expiry = store.nextExpiry()
                val now = System.currentTimeMillis()
                val entries = old.filterNot { it.relative == relative }.toMutableList()
                if (expiry != null) {
                    require(entries.size < MAX_WORKSPACES) { "Chat cleanup workspace registry is full" }
                    entries += RegisteredWorkspace(relative, if (expiry <= now) now + RETRY_DELAY_MS else expiry)
                }
                ApplicationWorkAdmission.withOpen(epoch) {
                    if (entries.sortedBy { it.relative } != old) writeRegistry(app, entries)
                    scheduleNext(app, entries, epoch)
                }
            }
        }
    }

    /** Resume a nonpersistent job after app startup/reboot, using only bounded registered metadata. */
    suspend fun resume(context: Context): String? = cleanup(context)

    suspend fun cleanup(context: Context): String? = cleanup(context, expectedEpoch = null)

    internal suspend fun cleanup(context: Context, expectedEpoch: String?): String? = withContext(Dispatchers.IO) {
        guarded {
            val app = context.applicationContext
            ApplicationWorkAdmission.initialize(app)
            val epoch = expectedEpoch ?: ApplicationWorkAdmission.requireOpen()
            lock.withLock {
                ensureAdmission(epoch)
                val now = System.currentTimeMillis()
                val old = readRegistry(app)
                val remaining = mutableListOf<RegisteredWorkspace>()
                old.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    ensureAdmission(epoch)
                    val workspace = runCatching { ChatMediaAccess.workspace(app, entry.relative, requireExisting = false) }.getOrNull()

                    if (workspace != null) {
                        if (entry.expiry > now) remaining += entry else {
                            try {
                                val store = ChatMediaStore(workspace)
                                store.cleanupExpired(now)
                                ensureAdmission(epoch)
                                AgentMediaScratch.cleanup(workspace, now)
                                store.nextExpiry()?.let { expiry ->
                                    remaining += entry.copy(expiry = if (expiry <= now) now + RETRY_DELAY_MS else expiry)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled
                            } catch (_: Exception) {
                                remaining += entry.copy(expiry = now + RETRY_DELAY_MS)
                            }
                        }
                    }
                }
                ApplicationWorkAdmission.withOpen(epoch) {
                    if (remaining.sortedBy { it.relative } != old) writeRegistry(app, remaining)
                    scheduleNext(app, remaining, epoch)
                }
            }
        }
    }

    /** Cancel expiry work without changing the registry, retained media, or project files. */
    suspend fun shutdownForExit(context: Context): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val failures = mutableListOf<Throwable>()
        try { ApplicationWorkAdmission.initialize(context) } catch (failure: Throwable) { failures += failure }
        check(!ApplicationWorkAdmission.isOpen) { "Close application admission before stopping media cleanup" }
        val workers = ChatMediaCleanupExitWorkers.snapshot()
        try { requireNotNull(context.getSystemService(JobScheduler::class.java)).cancel(JOB_ID) }
        catch (failure: Throwable) { failures += failure }
        workers.forEach { it.job.cancel(CancellationException("Droide has exited")) }
        try {
            withTimeout(15_000L) {
                workers.forEach { it.job.join() }
                // Foreground import/send cleanup uses this same lock. Wait for an admitted filesystem step;
                // queued callers fail their epoch check when they enter.
                lock.withLock { }
            }
        } catch (failure: Throwable) { failures += failure }
        workers.mapNotNullTo(failures) { it.failure }
        if (failures.isNotEmpty()) throw IllegalStateException("Chat media exit cleanup was incomplete").apply {
            failures.forEach(::addSuppressed)
        }
    }

    private fun ensureAdmission(epoch: String) {
        if (!ApplicationWorkAdmission.isCurrentOpen(epoch)) throw CancellationException("Droide has exited")
    }

    private suspend fun guarded(action: suspend () -> Unit): String? = try {
        action()
        null
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
        "Background media cleanup is unavailable. Attachments still expire after seven days and cleanup retries when Droide is used."
    }

    private fun scheduleNext(context: Context, entries: List<RegisteredWorkspace>, epoch: String) {
        val scheduler = requireNotNull(context.getSystemService(JobScheduler::class.java))
        val expiry = entries.minOfOrNull { it.expiry }
        if (expiry == null) { scheduler.cancel(JOB_ID); return }
        val pending = scheduler.getPendingJob(JOB_ID)
        if (pending?.extras?.getLong(EXTRA_EXPIRY) == expiry &&
            pending.extras.getString(EXTRA_ADMISSION_EPOCH) == epoch) return
        val delay = (expiry - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, ChatMediaCleanupJobService::class.java))
            .setMinimumLatency(delay)
            .setExtras(PersistableBundle().apply {
                putLong(EXTRA_EXPIRY, expiry)
                putString(EXTRA_ADMISSION_EPOCH, epoch)
            })
            .build()
        check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) { "Android deferred background cleanup scheduling" }
    }

    private fun registry(context: Context): File {
        val root = File(context.filesDir.canonicalFile, "chat-media-cleanup")
        require(!Files.isSymbolicLink(root.toPath()))
        check(root.isDirectory || root.mkdir())
        val file = File(root, "workspaces.properties")
        require(!Files.isSymbolicLink(file.toPath()))
        return file
    }

    private fun readRegistry(context: Context): List<RegisteredWorkspace> {
        val file = registry(context)
        if (!file.exists()) return emptyList()
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1..MAX_REGISTRY_BYTES)
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        require(properties.size <= MAX_WORKSPACES)
        val entries = properties.stringPropertyNames().sorted().map { key ->
            require(key.matches(Regex("workspace-[0-9]{1,3}")))
            val value = properties.getProperty(key)
            val expiry = value.substringBefore('|').toLong()
            val relative = ChatMediaAccess.decode(value.substringAfter('|', ""))
            require(expiry >= 0 && relative.isNotBlank() && relative.length <= 512)
            RegisteredWorkspace(relative, expiry)
        }
        require(entries.map { it.relative }.distinct().size == entries.size)
        return entries.sortedBy { it.relative }
    }

    private fun writeRegistry(context: Context, entries: List<RegisteredWorkspace>) {
        require(entries.size <= MAX_WORKSPACES)
        val file = registry(context)
        val properties = Properties().apply {
            entries.sortedBy { it.relative }.forEachIndexed { index, entry -> setProperty("workspace-$index", "${entry.expiry}|${ChatMediaAccess.encode(entry.relative)}") }
        }
        val temp = File(file.parentFile, ".registry-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output -> properties.store(output, "Droide chat media expiry registry"); output.fd.sync() }
            require(temp.length() <= MAX_REGISTRY_BYTES)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
    }
}

internal object ChatMediaCleanupExitWorkers {
    internal class Entry(val job: Job) { @Volatile var failure: Throwable? = null }
    private val lock = Any()
    private val entries = mutableMapOf<Job, Entry>()
    fun track(job: Job) {
        val entry = Entry(job)
        synchronized(lock) { entries[job] = entry }
        job.invokeOnCompletion { cause ->
            entry.failure = cause?.takeUnless { it is CancellationException }
                ?: cause?.suppressed?.firstOrNull()
            synchronized(lock) { entries.remove(job) }
        }
    }
    fun snapshot(): List<Entry> = synchronized(lock) { entries.values.toList() }
}

class ChatMediaCleanupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerLock = Any()
    private var running: Pair<JobParameters, Job>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (runCatching { ApplicationWorkAdmission.initialize(applicationContext) }.isFailure) return false
        val epoch = params.extras.getString(ChatMediaCleanup.EXTRA_ADMISSION_EPOCH) ?: return false
        if (!ApplicationWorkAdmission.isCurrentOpen(epoch)) return false
        var admitted = false
        return try {
            ApplicationWorkAdmission.withOpen(epoch) {
                admitted = true
                val worker = scope.launch(start = CoroutineStart.LAZY) {
                    try { ChatMediaCleanup.cleanup(applicationContext, epoch) }
                    finally {
                        val complete = synchronized(workerLock) {
                            if (running?.first === params) { running = null; true } else false
                        }
                        if (complete) jobFinished(params, false)
                    }
                }
                ChatMediaCleanupExitWorkers.track(worker)
                val previous = synchronized(workerLock) { running.also { running = params to worker } }
                previous?.second?.cancel()
                worker.start()
                true
            }
        } catch (failure: IllegalStateException) {
            if (!admitted && !ApplicationWorkAdmission.isCurrentOpen(epoch)) false else throw failure
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val worker = synchronized(workerLock) {
            running?.takeIf { it.first === params }?.also { running = null }?.second
        }
        worker?.cancel()
        val epoch = params.extras.getString(ChatMediaCleanup.EXTRA_ADMISSION_EPOCH) ?: return false
        return ApplicationWorkAdmission.isCurrentOpen(epoch)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
