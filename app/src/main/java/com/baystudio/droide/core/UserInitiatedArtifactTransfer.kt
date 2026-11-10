package com.baystudio.droide.core

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class UserInitiatedArtifactTransfer(context: Context) {
    private val appContext = context.applicationContext
    private val store = ArtifactTransferStore(appContext)
    private val downloader = TrustedArtifactDownloader(appContext)

    suspend fun download(
        spec: TrustedArtifactSpec,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): File = downloadDescriptor(spec, onProgress)

    suspend fun download(
        spec: TrustedSha512ArtifactSpec,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): File = downloadDescriptor(spec, onProgress)

    suspend fun <T> withArtifact(
        spec: TrustedArtifactSpec,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
        block: suspend (File) -> T,
    ): T = downloader.withLease(spec) { block(downloadDescriptor(spec, onProgress)) }

    suspend fun <T> withArtifact(
        spec: TrustedSha512ArtifactSpec,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
        block: suspend (File) -> T,
    ): T = downloader.withLease(spec) { block(downloadDescriptor(spec, onProgress)) }

    internal suspend fun <T> withArtifact(
        spec: TrustedArtifactDescriptor,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
        block: suspend (File) -> T,
    ): T = downloader.withLease(spec) { block(downloadDescriptor(spec, onProgress)) }

    internal suspend fun <T> withArtifacts(
        specs: List<TrustedArtifactDescriptor>,
        block: suspend (Map<TrustedArtifactDescriptor, File>) -> T,
    ): T {
        require(specs.size in 1..64) { "Invalid artifact group size" }
        suspend fun acquire(index: Int): T = if (index == specs.size) {
            block(specs.associateWith { downloadDescriptor(it, {}) })
        } else {
            downloader.withLease(specs[index]) { acquire(index + 1) }
        }
        return acquire(0)
    }

    suspend fun discard(spec: TrustedArtifactSpec) = downloader.discard(spec)
    suspend fun discard(spec: TrustedSha512ArtifactSpec) = downloader.discard(spec)
    suspend fun cached(spec: TrustedArtifactSpec): File? = downloader.cached(spec)
    suspend fun cached(spec: TrustedSha512ArtifactSpec): File? = downloader.cached(spec)

    private suspend fun downloadDescriptor(
        spec: TrustedArtifactDescriptor,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit,
    ): File {
        ApplicationWorkAdmission.initialize(appContext)
        val epoch = ApplicationWorkAdmission.requireOpen()
        val cached = cached(spec, onProgress)
        check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "Droide has exited" }
        if (cached != null) {
            onProgress(TrustedArtifactDownloadProgress(cached.length(), cached.length(), fromCache = true))
            return cached
        }

        val transferId = ArtifactTransferStore.transferId(spec)
        val waiter = ApplicationWorkAdmission.withOpen(epoch) {
            WAITERS.join(transferId) {
                val request = store.ensure(spec)
                store.recoverStaleRunning(request.id)
                if (store.read(request.id)?.state != ArtifactTransferState.RUNNING) schedule(request.id)
            }
        }
        var lastBytes = -1L
        var lastDetail: String? = null
        try {
            suspend fun awaitWorker() {
            while (true) {
                currentCoroutineContext().ensureActive()
                waiter.ensureActive()
                if (!ApplicationWorkAdmission.isCurrentOpen(epoch)) throw CancellationException("Droide has exited")
                val current = store.read(transferId) ?: error("Artifact transfer state disappeared")
                if (current.bytesDownloaded != lastBytes || current.detail != lastDetail) {
                    lastBytes = current.bytesDownloaded
                    lastDetail = current.detail
                    onProgress(
                        TrustedArtifactDownloadProgress(
                            bytesDownloaded = current.bytesDownloaded,
                            totalBytes = current.totalBytes,
                            fromCache = false,
                            detail = current.detail.takeIf { it.isNotBlank() },
                        )
                    )
                }
                when (current.state) {
                    ArtifactTransferState.SUCCEEDED -> return
                    ArtifactTransferState.FAILED -> error(current.detail.ifBlank { "Artifact transfer failed" })
                    ArtifactTransferState.CANCELED -> throw CancellationException(
                        current.detail.ifBlank { "Artifact transfer canceled" }
                    )
                    ArtifactTransferState.QUEUED,
                    ArtifactTransferState.RUNNING,
                    -> delay(POLL_MS)
                }
            }
            }
            // UIDT owns the transfer's wake lock. Restore our CPU lease before verifying the completed
            // cache; legacy foreground transfers still require the caller's lease.
            if (Build.VERSION.SDK_INT >= 34) OwnedDevelopmentOperation.withCpuIdle { awaitWorker() }
            else awaitWorker()
            return cached(spec, onProgress)
                ?: error("Verified transfer completed but trusted cache is unavailable")
        } catch (cancelled: CancellationException) {
            try {
                withArtifactTransferAdmission(epoch) {
                    WAITERS.leave(waiter, canceled = true) {
                        cancelWorker(appContext, transferId, "All callers canceled the package transfer")
                    }
                }
            } catch (cleanupFailure: Throwable) {
                cancelled.addSuppressed(cleanupFailure)
            }
            throw cancelled
        } finally {
            WAITERS.leave(waiter, canceled = false) {}
        }
    }

    private suspend fun cached(spec: TrustedArtifactDescriptor,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {}): File? = when (spec) {
        is TrustedArtifactSpec -> downloader.cached(spec, onProgress)
        is TrustedSha512ArtifactSpec -> downloader.cached(spec, onProgress)
        else -> error("Unsupported trusted artifact descriptor")
    }

    internal fun schedule(transferId: String) {
        ApplicationWorkAdmission.initialize(appContext)
        ApplicationWorkAdmission.withOpen {
            val epoch = ApplicationWorkAdmission.requireOpen()
            val request = store.read(transferId) ?: error("Artifact transfer request is unavailable")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    scheduleUidt(appContext, request, epoch)
                } else {
                    PackageTransferForegroundService.start(appContext, transferId, request.requestIdentity, epoch)
                }
            } catch (failure: Exception) {
                // Admission failed before a worker took ownership. Keep Retry and recovery from mistaking an
                // unscheduled request for a transfer that is still waiting to run.
                try {
                    store.markSchedulingFailed(transferId, request.requestIdentity, failure.message ?: "Transfer scheduling failed")
                } catch (persistenceFailure: Exception) {
                    failure.addSuppressed(persistenceFailure)
                }
                throw failure
            }
        }
    }

    companion object {
        private const val POLL_MS = 200L
        private val WAITERS = ArtifactTransferWaiterRegistry()

        fun cancel(context: Context, transferId: String, reason: String = "Transfer canceled by user") {
            ApplicationWorkAdmission.initialize(context)
            val epoch = ApplicationWorkAdmission.currentEpoch
            withArtifactTransferAdmission(epoch) {
                WAITERS.cancelAll(transferId, reason) { cancelWorker(context, transferId, reason) }
            }
        }

        internal fun cancelIfCurrent(context: Context, transferId: String, requestIdentity: String, epoch: String, reason: String): Boolean {
            ApplicationWorkAdmission.initialize(context)
            return withArtifactTransferAdmission(epoch) {
                val record = ArtifactTransferStore(context).read(transferId)
                if (record?.requestIdentity != requestIdentity || record.state !in setOf(
                        ArtifactTransferState.QUEUED, ArtifactTransferState.RUNNING)) return@withArtifactTransferAdmission false
                WAITERS.cancelAll(transferId, reason) { cancelWorker(context, transferId, reason) }
                true
            } ?: false
        }

        /** Admission must be durably closed before any Android stop callback can request a retry. */
        suspend fun shutdownForExit(context: Context): Unit = withContext(NonCancellable + Dispatchers.IO) {
            val failures = mutableListOf<Throwable>()
            fun attempt(block: () -> Unit) { try { block() } catch (failure: Throwable) { failures += failure } }
            val app = context.applicationContext
            attempt { ApplicationWorkAdmission.initialize(app) }
            check(!ApplicationWorkAdmission.isOpen) { "Close application admission before stopping artifact transfers" }
            val workers = ArtifactTransferExitWorkers.snapshot()
            var store: ArtifactTransferStore? = null
            val ids = workers.mapTo(linkedSetOf()) { it.transferId }
            attempt { store = ArtifactTransferStore(app); ids += store!!.idsForExit() }
            var scheduler: JobScheduler? = null
            var queriedScheduler = false
            val pending = mutableListOf<JobInfo>()
            attempt {
                scheduler = requireNotNull(app.getSystemService(JobScheduler::class.java))
                pending += scheduler!!.allPendingJobs.filter {
                    it.service == ComponentName(app, UserInitiatedArtifactTransferJobService::class.java)
                }
                queriedScheduler = true
                pending.mapNotNullTo(ids) { it.extras.getString(ArtifactTransferStore.EXTRA_TRANSFER_ID) }
            }
            // Finish every cancellation record before asking Android to stop any scheduled worker.
            for (id in ids) attempt {
                WAITERS.cancelAll(id, EXIT_REASON) { requireNotNull(store).markCanceledForExit(id, EXIT_REASON) }
            }
            for (job in pending) attempt { scheduler!!.cancel(job.id) }
            // A failed pending-job query must not leave a known durable transfer queued.
            if (!queriedScheduler) for (id in ids) attempt { scheduler?.cancel(ArtifactTransferStore.jobId(id)) }
            workers.forEach { it.job.cancel(CancellationException(EXIT_REASON)) }
            attempt { app.stopService(Intent(app, PackageTransferForegroundService::class.java)) }
            try { withTimeout(EXIT_JOIN_TIMEOUT_MS) { workers.forEach { it.job.join() } } }
            catch (failure: Throwable) { failures += failure }
            workers.mapNotNullTo(failures) { it.failure }
            attempt { app.getSystemService(NotificationManager::class.java)?.cancel(PackageTransferForegroundService.FOREGROUND_NOTIFICATION_ID) }
            failures += ArtifactTransferExitWorkers.takeFailures()
            if (failures.isNotEmpty()) throw IllegalStateException("Artifact transfer exit cleanup was incomplete").apply {
                failures.forEach(::addSuppressed)
            }
        }

        private const val EXIT_REASON = "Transfer stopped because Droide exited; verified partial data retained"
        private const val EXIT_JOIN_TIMEOUT_MS = 15_000L

        internal fun recordSystemCancellationIf(transferId: String, reason: String, stop: () -> Boolean): Boolean =
            WAITERS.cancelAllIf(transferId, reason, stop)

        private fun cancelWorker(context: Context, transferId: String, reason: String) {
            val app = context.applicationContext
            val canceled = ArtifactTransferStore(app).markCanceled(transferId, reason)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val scheduler = app.getSystemService(JobScheduler::class.java)
                scheduler.cancel(ArtifactTransferStore.jobId(transferId))
            } else {
                PackageTransferForegroundService.cancel(app, transferId, canceled?.requestIdentity)
            }
        }

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun scheduleUidt(context: Context, request: ArtifactTransferRecord, epoch: String) {
            val transferId = request.id
            val extras = PersistableBundle().apply {
                putString(ArtifactTransferStore.EXTRA_TRANSFER_ID, transferId)
                putString(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY, request.requestIdentity)
                putString(ArtifactTransferStore.EXTRA_ADMISSION_EPOCH, epoch)
            }
            val network = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val estimated = request.totalBytes ?: request.maxBytes
            val info = JobInfo.Builder(
                ArtifactTransferStore.jobId(transferId),
                ComponentName(context, UserInitiatedArtifactTransferJobService::class.java),
            )
                .setExtras(extras)
                .setUserInitiated(true)
                .setRequiredNetwork(network)
                .setEstimatedNetworkBytes(estimated, 0L)
                .setMinimumNetworkChunkBytes(minOf(estimated, 1024L * 1024L).coerceAtLeast(1L))
                .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            val result = context.getSystemService(JobScheduler::class.java).schedule(info)
            check(result == JobScheduler.RESULT_SUCCESS) {
                "Android rejected the user-initiated transfer schedule. Start package downloads while Droide is visible."
            }
        }
    }
}

internal enum class ArtifactTransferState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELED }

internal data class ArtifactTransferRecord(
    val id: String,
    val artifactId: String,
    val url: String,
    val fileName: String,
    val maxBytes: Long,
    val totalBytes: Long?,
    val digestAlgorithm: String,
    val digestHex: String,
    val state: ArtifactTransferState,
    val bytesDownloaded: Long,
    val attempts: Int,
    val ownerPid: Int?,
    val ownerProcessIdentity: String?,
    val updatedAtEpochMs: Long,
    val detail: String,
    val requestIdentity: String? = null,
    val ownerWorkerIdentity: String? = null,
    val expectedBytes: Long? = totalBytes,
    val hasStoredExpectedBytes: Boolean = true,
) {
    fun descriptor(): TrustedArtifactDescriptor = when (digestAlgorithm) {
        "SHA-256" -> TrustedArtifactSpec(artifactId, url, digestHex, fileName, maxBytes, expectedBytes)
        "SHA-512" -> TrustedSha512ArtifactSpec(artifactId, url, digestHex, fileName, maxBytes, expectedBytes)
        else -> error("Unsupported persisted artifact digest")
    }
}

internal class ArtifactTransferStore internal constructor(
    private val root: File,
    private val processPid: Int,
    private val processIdentity: String,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "artifact-transfers"),
        android.os.Process.myPid(), LocalExecutionSubstrate.processIdentity(),
    )

    init { root.mkdirs() }

    fun ensure(spec: TrustedArtifactDescriptor): ArtifactTransferRecord {
        // No hostname resolution or other network work may hold the state-store lock.
        spec.validateStructure()
        return synchronized(FILE_LOCK) {
            val id = transferId(spec)
            val existing = readLocked(id)
            if (existing != null) {
                check(existing.artifactId == spec.id && existing.url == spec.url && existing.fileName == spec.fileName)
                check(existing.digestAlgorithm == spec.digestAlgorithm && existing.digestHex.equals(spec.digestHex, true))
                check(existing.maxBytes == spec.maxBytes)
                check(!existing.hasStoredExpectedBytes || existing.expectedBytes == spec.expectedBytes)

                val matching = if (existing.hasStoredExpectedBytes) existing else existing.copy(
                    expectedBytes = spec.expectedBytes, hasStoredExpectedBytes = true,
                )
                if (existing.state in setOf(ArtifactTransferState.SUCCEEDED, ArtifactTransferState.FAILED, ArtifactTransferState.CANCELED)) {
                    return@synchronized matching.copy(
                        state = ArtifactTransferState.QUEUED,
                        bytesDownloaded = 0L,
                        totalBytes = spec.expectedBytes,
                        attempts = 0,
                        ownerPid = null,
                        ownerProcessIdentity = null,
                        updatedAtEpochMs = System.currentTimeMillis(),
                        detail = "Queued; waiting for Android to start the transfer…",
                        requestIdentity = newIdentity(),
                        ownerWorkerIdentity = null,
                    ).also(::writeLocked)
                }
                val liveLegacyWorker = matching.requestIdentity == null && matching.state == ArtifactTransferState.RUNNING &&
                    matching.ownerWorkerIdentity != null && matching.ownerPid == processPid &&
                    matching.ownerProcessIdentity == processIdentity
                return@synchronized if ((matching.requestIdentity == null && !liveLegacyWorker) || !existing.hasStoredExpectedBytes) {
                    matching.copy(requestIdentity = matching.requestIdentity ?: if (liveLegacyWorker) null else newIdentity()).also(::writeLocked)
                } else matching
            }
            ArtifactTransferRecord(
                id = id,
                artifactId = spec.id,
                url = spec.url,
                fileName = spec.fileName,
                maxBytes = spec.maxBytes,
                totalBytes = spec.expectedBytes,
                digestAlgorithm = spec.digestAlgorithm,
                digestHex = spec.digestHex.lowercase(),
                state = ArtifactTransferState.QUEUED,
                bytesDownloaded = 0L,
                attempts = 0,
                ownerPid = null,
                ownerProcessIdentity = null,
                updatedAtEpochMs = System.currentTimeMillis(),
                detail = "Queued; waiting for Android to start the transfer…",
                requestIdentity = newIdentity(),
            ).also(::writeLocked)
        }
    }

    fun read(id: String): ArtifactTransferRecord? = synchronized(FILE_LOCK) { readLocked(id) }

    /** Only this private metadata directory is enumerated; cache/partial files are never removed. */
    internal fun idsForExit(): List<String> = synchronized(FILE_LOCK) {
        check(root.isDirectory) { "Artifact transfer records are unavailable" }
        requireNotNull(root.listFiles()) { "Could not enumerate artifact transfer records" }
            .filter { it.name.matches(Regex("[0-9a-f]{32}\\.properties")) }
            .map { it.name.removeSuffix(".properties") }.sorted()
    }

    internal fun markCanceledForExit(id: String, reason: String): ArtifactTransferRecord? = synchronized(FILE_LOCK) {
        if (!file(id).exists()) return@synchronized null
        val current = checkNotNull(readLocked(id)) { "Could not read artifact transfer record $id during Exit" }
        if (current.state in setOf(ArtifactTransferState.QUEUED, ArtifactTransferState.RUNNING)) markCanceled(id, reason) else current
    }

    fun markSchedulingFailed(id: String, requestIdentity: String?, detail: String): ArtifactTransferRecord? = synchronized(FILE_LOCK) {
        val current = readLocked(id) ?: return@synchronized null
        if (current.requestIdentity != requestIdentity || current.state != ArtifactTransferState.QUEUED ||
            current.ownerWorkerIdentity != null) return@synchronized null
        current.copy(
            state = ArtifactTransferState.FAILED,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = detail.take(MAX_DETAIL),
        ).also(::writeLocked)
    }

    fun markRunning(id: String, requestIdentity: String?, workerIdentity: String): ArtifactTransferRecord? = synchronized(FILE_LOCK) {
        val current = readLocked(id) ?: return@synchronized null
        if (current.requestIdentity != requestIdentity) return@synchronized null
        if (current.state in setOf(ArtifactTransferState.CANCELED, ArtifactTransferState.SUCCEEDED, ArtifactTransferState.FAILED)) {
            return@synchronized null
        }
        current.copy(
            state = ArtifactTransferState.RUNNING,
            attempts = current.attempts + 1,
            ownerPid = processPid,
            ownerProcessIdentity = processIdentity,
            ownerWorkerIdentity = workerIdentity,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = "Preparing artifact transfer…",
        ).also(::writeLocked)
    }

    fun markProgress(id: String, workerIdentity: String, progress: TrustedArtifactDownloadProgress) = updateWorker(id, workerIdentity) {
        if (it.state != ArtifactTransferState.RUNNING) it else it.copy(
            bytesDownloaded = progress.bytesDownloaded,
            totalBytes = progress.totalBytes ?: it.totalBytes,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = progress.detail.orEmpty().take(300),
        )
    }

    fun markSucceeded(id: String, workerIdentity: String, bytes: Long) = updateWorker(id, workerIdentity) {
        if (it.state == ArtifactTransferState.CANCELED) it else it.copy(
            state = ArtifactTransferState.SUCCEEDED,
            bytesDownloaded = bytes,
            totalBytes = it.totalBytes ?: bytes,
            ownerPid = null,
            ownerProcessIdentity = null,
            ownerWorkerIdentity = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = "Verified digest and published to trusted artifact cache",
        )
    }

    fun markRetry(id: String, workerIdentity: String, detail: String) = updateWorker(id, workerIdentity) {
        if (it.state in setOf(ArtifactTransferState.CANCELED, ArtifactTransferState.SUCCEEDED)) it else it.copy(
            state = ArtifactTransferState.QUEUED,
            ownerPid = null,
            ownerProcessIdentity = null,
            ownerWorkerIdentity = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = detail.take(MAX_DETAIL),
        )
    }

    fun markFailed(id: String, workerIdentity: String, detail: String) = updateWorker(id, workerIdentity) {
        if (it.state in setOf(ArtifactTransferState.CANCELED, ArtifactTransferState.SUCCEEDED)) it else it.copy(
            state = ArtifactTransferState.FAILED,
            ownerPid = null,
            ownerProcessIdentity = null,
            ownerWorkerIdentity = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = detail.take(MAX_DETAIL),
        )
    }

    fun markCanceled(id: String, detail: String): ArtifactTransferRecord? = updateIfPresent(id) {
        if (it.state == ArtifactTransferState.SUCCEEDED) it else it.copy(
            state = ArtifactTransferState.CANCELED,
            ownerPid = null,
            ownerProcessIdentity = null,
            ownerWorkerIdentity = null,
            updatedAtEpochMs = System.currentTimeMillis(),
            detail = detail.take(MAX_DETAIL),
        )
    }

    fun markCanceledIfRequest(id: String, requestIdentity: String?, detail: String): Boolean = synchronized(FILE_LOCK) {
        val current = readLocked(id) ?: return@synchronized false
        if (current.requestIdentity != requestIdentity || current.state == ArtifactTransferState.SUCCEEDED) {
            return@synchronized false
        }
        markCanceled(id, detail)
        true
    }

    fun recoverStaleRunning(id: String) = synchronized(FILE_LOCK) {
        val record = readLocked(id) ?: return@synchronized
        if (record.state == ArtifactTransferState.RUNNING && (
            record.ownerPid != processPid || record.ownerProcessIdentity != processIdentity
        )) {
            writeLocked(record.copy(
                state = ArtifactTransferState.QUEUED,
                ownerPid = null,
                ownerProcessIdentity = null,
                ownerWorkerIdentity = null,
                updatedAtEpochMs = System.currentTimeMillis(),
                detail = "Recovered interrupted transfer; resumable cache will be revalidated",
            ))
        }
    }

    private fun updateWorker(id: String, workerIdentity: String, transform: (ArtifactTransferRecord) -> ArtifactTransferRecord): ArtifactTransferRecord? =
        synchronized(FILE_LOCK) {
            val current = readLocked(id) ?: return@synchronized null
            if (current.ownerWorkerIdentity != workerIdentity) return@synchronized null
            transform(current).also(::writeLocked)
        }

    private fun updateIfPresent(id: String, transform: (ArtifactTransferRecord) -> ArtifactTransferRecord): ArtifactTransferRecord? =
        synchronized(FILE_LOCK) {
            val current = readLocked(id) ?: return@synchronized null
            transform(current).also(::writeLocked)
        }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{32}"))) { "Invalid artifact transfer id" }
        return File(root, "$id.properties")
    }

    private fun readLocked(id: String): ArtifactTransferRecord? {
        val file = file(id)
        if (!file.isFile || file.length() !in 1L..MAX_RECORD_BYTES) return null
        return runCatching {
            val p = Properties().apply { file.inputStream().buffered().use { input -> load(input) } }
            check(p.getProperty("schema") == "2")
            ArtifactTransferRecord(
                id = id,
                artifactId = p.required("artifactId"),
                url = p.required("url"),
                fileName = p.required("fileName"),
                maxBytes = p.required("maxBytes").toLong(),
                totalBytes = p.getProperty("totalBytes")?.takeIf(String::isNotBlank)?.toLong(),
                digestAlgorithm = p.required("digestAlgorithm"),
                digestHex = p.required("digestHex"),
                state = ArtifactTransferState.valueOf(p.required("state")),
                bytesDownloaded = p.required("bytesDownloaded").toLong(),
                attempts = p.required("attempts").toInt(),
                ownerPid = p.getProperty("ownerPid")?.takeIf(String::isNotBlank)?.toInt(),
                ownerProcessIdentity = p.getProperty("ownerProcessIdentity")?.takeIf(String::isNotBlank),
                updatedAtEpochMs = p.required("updatedAtEpochMs").toLong(),
                detail = p.getProperty("detail").orEmpty(),
                requestIdentity = p.getProperty("requestIdentity")?.takeIf(String::isNotBlank),
                ownerWorkerIdentity = p.getProperty("ownerWorkerIdentity")?.takeIf(String::isNotBlank),
                expectedBytes = if (p.containsKey("expectedBytes")) {
                    p.getProperty("expectedBytes")?.takeIf(String::isNotBlank)?.toLong()
                } else p.getProperty("totalBytes")?.takeIf(String::isNotBlank)?.toLong(),
                hasStoredExpectedBytes = p.containsKey("expectedBytes"),
            ).let { record ->
                // The old transfer ID still identifies an originally unknown expected length after
                // Content-Length has populated totalBytes; recover it without trusting that observation.
                if (!record.hasStoredExpectedBytes) {
                    val unknownLength = record.copy(expectedBytes = null)
                    if (transferId(unknownLength.descriptor()) == id) unknownLength else record
                } else record
            }.also { record ->
                record.descriptor().validateStructure()
                check(record.bytesDownloaded in 0L..record.maxBytes)
                record.totalBytes?.let { total ->
                    check(total in 1L..record.maxBytes)
                    check(record.bytesDownloaded <= total)
                }
                record.expectedBytes?.let { check(it in 1L..record.maxBytes) }
                check(record.attempts in 0..100)
                check(record.detail.length <= MAX_DETAIL)
                record.ownerPid?.let { check(it > 0) }
                record.ownerProcessIdentity?.let { check(it.matches(Regex("[0-9a-f]{32}"))) }
                record.requestIdentity?.let { check(it.matches(Regex("[0-9a-f]{32}"))) }
                record.ownerWorkerIdentity?.let { check(it.matches(Regex("[0-9a-f]{32}"))) }
                check((record.ownerPid == null) == (record.ownerProcessIdentity == null))
            }
        }.getOrNull()
    }

    private fun writeLocked(record: ArtifactTransferRecord) {
        val target = file(record.id)
        val temp = File(root, ".${record.id}.$processPid.tmp")
        val props = Properties().apply {
            setProperty("schema", "2")
            setProperty("artifactId", record.artifactId)
            setProperty("url", record.url)
            setProperty("fileName", record.fileName)
            setProperty("maxBytes", record.maxBytes.toString())
            setProperty("totalBytes", record.totalBytes?.toString().orEmpty())
            setProperty("digestAlgorithm", record.digestAlgorithm)
            setProperty("digestHex", record.digestHex)
            setProperty("state", record.state.name)
            setProperty("bytesDownloaded", record.bytesDownloaded.toString())
            setProperty("attempts", record.attempts.toString())
            setProperty("ownerPid", record.ownerPid?.toString().orEmpty())
            setProperty("ownerProcessIdentity", record.ownerProcessIdentity.orEmpty())
            setProperty("updatedAtEpochMs", record.updatedAtEpochMs.toString())
            setProperty("detail", record.detail.replace('\n', ' ').replace('\r', ' ').take(MAX_DETAIL))
            setProperty("requestIdentity", record.requestIdentity.orEmpty())
            setProperty("ownerWorkerIdentity", record.ownerWorkerIdentity.orEmpty())
            if (record.hasStoredExpectedBytes) setProperty("expectedBytes", record.expectedBytes?.toString().orEmpty())
        }
        FileOutputStream(temp).use { out ->
            props.store(out, null)
            out.fd.sync()
        }
        val moved = runCatching {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        moved.getOrElse { failure ->
            temp.delete()
            throw IllegalStateException("Could not persist artifact transfer state", failure)
        }
    }

    private fun Properties.required(key: String): String = getProperty(key)?.takeIf(String::isNotBlank)
        ?: error("Missing artifact transfer field $key")

    companion object {
        const val EXTRA_TRANSFER_ID = "transfer_id"
        const val EXTRA_REQUEST_IDENTITY = "request_identity"
        const val EXTRA_ADMISSION_EPOCH = "admission_epoch"
        private val FILE_LOCK = Any()
        private const val MAX_RECORD_BYTES = 32L * 1024L
        private const val MAX_DETAIL = 500
        private fun newIdentity(): String = UUID.randomUUID().toString().replace("-", "")

        fun transferId(spec: TrustedArtifactDescriptor): String {
            val canonical = listOf(
                spec.id, spec.url, spec.fileName, spec.digestAlgorithm, spec.digestHex.lowercase(),
                spec.maxBytes.toString(), spec.expectedBytes?.toString().orEmpty(),
            )
                .joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)
        }

        fun jobId(transferId: String): Int {
            require(transferId.matches(Regex("[0-9a-f]{32}")))
            return (transferId.take(8).toLong(16).and(0x7fffffffL).toInt()).coerceAtLeast(1)
        }
    }
}

/** Entries remain reachable until coroutine completion, even after registry cancellation/replacement. */
internal object ArtifactTransferExitWorkers {
    internal class Entry(val transferId: String, val epoch: String, val job: Job) {
        @Volatile var failure: Throwable? = null
    }
    private val lock = Any()
    private val entries = mutableMapOf<Job, Entry>()
    private val lifecycleFailures = mutableListOf<Throwable>()

    fun recordFailure(failure: Throwable) = synchronized(lock) { lifecycleFailures += failure }
    fun takeFailures(): List<Throwable> = synchronized(lock) { lifecycleFailures.toList().also { lifecycleFailures.clear() } }

    fun track(id: String, epoch: String, job: Job) {
        val entry = Entry(id, epoch, job)
        synchronized(lock) { entries[job] = entry }
        job.invokeOnCompletion { cause ->
            entry.failure = cause?.takeUnless { it is CancellationException }
                ?: cause?.suppressed?.firstOrNull()
            synchronized(lock) { entries.remove(job) }
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.values.toList() }
}

/** A close racing the optimistic check is a denied publication, not an operational error. */
private fun <T> withArtifactTransferAdmission(epoch: String, block: () -> T): T? {
    if (!ApplicationWorkAdmission.isCurrentOpen(epoch)) return null
    var entered = false
    return try { ApplicationWorkAdmission.withOpen(epoch) { entered = true; block() } }
    catch (failure: IllegalStateException) {
        if (!entered && !ApplicationWorkAdmission.isCurrentOpen(epoch)) null else throw failure
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UserInitiatedArtifactTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ArtifactTransferWorkerRegistry<String>()
    private val store by lazy { ArtifactTransferStore(applicationContext) }

    override fun onStartJob(params: JobParameters): Boolean {
        if (runCatching { ApplicationWorkAdmission.initialize(applicationContext) }.isFailure) return false
        val epoch = params.extras.getString(ArtifactTransferStore.EXTRA_ADMISSION_EPOCH) ?: return false
        val id = params.extras.getString(ArtifactTransferStore.EXTRA_TRANSFER_ID) ?: return false
        val requestIdentity = params.extras.getString(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY)
        return withArtifactTransferAdmission(epoch) {
            val record = store.read(id) ?: return@withArtifactTransferAdmission false
            if (record.requestIdentity != requestIdentity || record.state !in setOf(
                    ArtifactTransferState.QUEUED, ArtifactTransferState.RUNNING)) return@withArtifactTransferAdmission false
            ArtifactTransferNotifications.ensureChannel(this)
            setNotification(params, ArtifactTransferStore.jobId(id),
                ArtifactTransferNotifications.build(this, record, indeterminate = true), JOB_END_NOTIFICATION_POLICY_REMOVE)
            val worker = jobs.start(id, requestIdentity) { workerIdentity ->
                scope.launch(start = CoroutineStart.LAZY) {
                    val notificationGate = ArtifactTransferNotificationGate()
                    fun <T> publish(block: () -> T): T? = withArtifactTransferAdmission(epoch) {
                        jobs.withCurrent(id, workerIdentity, block)
                    }
                    try {
                        currentCoroutineContext().ensureActive()
                        val running = publish { store.markRunning(id, requestIdentity, workerIdentity) } ?: return@launch
                        val downloader = TrustedArtifactDownloader(applicationContext)
                        val progress: (TrustedArtifactDownloadProgress) -> Unit = { value ->
                            publish {
                                if (store.markProgress(id, workerIdentity, value) != null) {
                                    updateTransferredNetworkBytes(params, value.bytesDownloaded.coerceAtLeast(0L), 0L)
                                    if (notificationGate.shouldNotify(value, System.nanoTime() / 1_000_000L)) {
                                        store.read(id)?.let { current ->
                                            setNotification(params, ArtifactTransferStore.jobId(id),
                                                ArtifactTransferNotifications.build(this@UserInitiatedArtifactTransferJobService, current, false),
                                                JOB_END_NOTIFICATION_POLICY_REMOVE)
                                        }
                                    }
                                }
                            }
                        }
                        val file = when (val spec = running.descriptor()) {
                            is TrustedArtifactSpec -> downloader.download(spec, progress)
                            is TrustedSha512ArtifactSpec -> downloader.download(spec, progress)
                            else -> error("Unsupported artifact transfer descriptor")
                        }
                        publish {
                            if (store.markSucceeded(id, workerIdentity, file.length()) != null) jobFinished(params, false)
                        }
                    } catch (cancelled: CancellationException) {
                        publish { store.markRetry(id, workerIdentity, "Transfer interrupted before completion") }
                        throw cancelled
                    } catch (io: IOException) {
                        if (!ApplicationWorkAdmission.isCurrentOpen(epoch)) throw io
                        publish {
                            val attempts = store.read(id)?.attempts ?: 1
                            if (attempts < MAX_RETRIES) {
                                if (store.markRetry(id, workerIdentity, io.message ?: "Temporary network failure") != null) jobFinished(params, true)
                            } else if (store.markFailed(id, workerIdentity, io.message ?: "Network transfer failed after retries") != null) {
                                jobFinished(params, false)
                            }
                        }
                    } catch (failure: Throwable) {
                        if (!ApplicationWorkAdmission.isCurrentOpen(epoch)) throw failure
                        publish {
                            if (store.markFailed(id, workerIdentity, failure.message ?: "Artifact verification failed") != null) jobFinished(params, false)
                        }
                    } finally {
                        jobs.finish(id, workerIdentity)
                    }
                }.also { ArtifactTransferExitWorkers.track(id, epoch, it) }
            }
            worker?.job?.start()
            true
        } ?: false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getString(ArtifactTransferStore.EXTRA_TRANSFER_ID) ?: return false
        val requestIdentity = params.extras.getString(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY)
        val epoch = params.extras.getString(ArtifactTransferStore.EXTRA_ADMISSION_EPOCH) ?: return false
        if (!ApplicationWorkAdmission.isCurrentOpen(epoch)) {
            // Never request a retry while closed or mutate a fresh request from an earlier epoch.
            if (!ApplicationWorkAdmission.isOpen && ApplicationWorkAdmission.currentEpoch == epoch) {
                store.markCanceledIfRequest(id, requestIdentity, "Transfer stopped because Droide exited")
                jobs.cancel(id, requestIdentity)
            }
            return false
        }
        return withArtifactTransferAdmission(epoch) {
            val current = store.read(id) ?: run {
                jobs.cancel(id, requestIdentity)
                return@withArtifactTransferAdmission false
            }
            if (current.requestIdentity != requestIdentity || current.state == ArtifactTransferState.CANCELED) {
                jobs.cancel(id, requestIdentity)
                return@withArtifactTransferAdmission false
            }
            // Android Task Manager may kill the process without this callback. A later explicit request
            // still recovers its durable record and revalidates any retained partial data.
            if (params.stopReason == JobParameters.STOP_REASON_USER) {
                val reason = "Transfer stopped by the user through Android"
                UserInitiatedArtifactTransfer.recordSystemCancellationIf(id, reason) {
                    store.markCanceledIfRequest(id, requestIdentity, reason)
                }
                jobs.cancel(id, requestIdentity)
                return@withArtifactTransferAdmission false
            }
            val worker = jobs.cancel(id, requestIdentity)
            val workerIdentity = worker?.identity ?: current.ownerWorkerIdentity
            if (workerIdentity != null) store.markRetry(id, workerIdentity,
                "Android stopped transfer (reason=${params.stopReason}); verified partial data retained for resume")
            val stopped = store.read(id)
            stopped != null && stopped.requestIdentity == requestIdentity &&
                stopped.state in setOf(ArtifactTransferState.QUEUED, ArtifactTransferState.RUNNING)
        } ?: false
    }

    override fun onDestroy() {
        try {
            val epochs = ArtifactTransferExitWorkers.snapshot().associate { it.job to it.epoch }
            for ((id, worker) in jobs.clear()) {
                try {
                    epochs[worker.job]?.let { epoch ->
                        withArtifactTransferAdmission(epoch) {
                            store.markRetry(id, worker.identity, "Android transfer service destroyed; verified partial data retained")
                        }
                    }
                } catch (failure: Throwable) { ArtifactTransferExitWorkers.recordFailure(failure) }
            }
        } finally { scope.cancel(); super.onDestroy() }
    }

    companion object { private const val MAX_RETRIES = 4 }
}

class PackageTransferForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ArtifactTransferWorkerRegistry<String>()
    private val store by lazy { ArtifactTransferStore(applicationContext) }

    override fun onCreate() {
        super.onCreate()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialized = runCatching { ApplicationWorkAdmission.initialize(applicationContext) }.isSuccess
        val epoch = intent?.getStringExtra(ArtifactTransferStore.EXTRA_ADMISSION_EPOCH)
        if (!initialized || epoch == null || !ApplicationWorkAdmission.isCurrentOpen(epoch)) {
            refreshForeground()
            return START_NOT_STICKY
        }
        val id = intent.getStringExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID) ?: run {
            refreshForeground(); return START_NOT_STICKY
        }
        val requestIdentity = intent.getStringExtra(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY)
        withArtifactTransferAdmission(epoch) {
            when (intent.action) {
                ACTION_CANCEL -> { jobs.cancel(id, requestIdentity); refreshForeground() }
                ACTION_START -> startTransfer(id, requestIdentity, epoch)
                else -> refreshForeground()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTransfer(id: String, requestIdentity: String?, epoch: String) {
        val record = store.read(id)
        if (record == null || record.requestIdentity != requestIdentity || record.state !in setOf(
                ArtifactTransferState.QUEUED, ArtifactTransferState.RUNNING)) {
            refreshForeground()
            return
        }
        ArtifactTransferNotifications.ensureChannel(this)
        startVisible(record)
        val worker = jobs.start(id, requestIdentity) { workerIdentity ->
            scope.launch(start = CoroutineStart.LAZY) {
                fun <T> publish(block: () -> T): T? = withArtifactTransferAdmission(epoch) {
                    jobs.withCurrent(id, workerIdentity, block)
                }
                try {
                    currentCoroutineContext().ensureActive()
                    val running = publish { store.markRunning(id, requestIdentity, workerIdentity) } ?: return@launch
                    val downloader = TrustedArtifactDownloader(applicationContext)
                    val progress: (TrustedArtifactDownloadProgress) -> Unit = { value ->
                        publish { if (store.markProgress(id, workerIdentity, value) != null) refreshForeground() }
                    }
                    val file = when (val spec = running.descriptor()) {
                        is TrustedArtifactSpec -> downloader.download(spec, progress)
                        is TrustedSha512ArtifactSpec -> downloader.download(spec, progress)
                        else -> error("Unsupported artifact transfer descriptor")
                    }
                    publish { store.markSucceeded(id, workerIdentity, file.length()) }
                } catch (cancelled: CancellationException) {
                    publish { store.markRetry(id, workerIdentity, "Visible transfer interrupted") }
                    throw cancelled
                } catch (failure: Throwable) {
                    if (!ApplicationWorkAdmission.isCurrentOpen(epoch)) throw failure
                    publish { store.markFailed(id, workerIdentity, failure.message ?: "Visible transfer failed") }
                } finally {
                    if (jobs.finish(id, workerIdentity)) refreshForeground()
                }
            }.also { ArtifactTransferExitWorkers.track(id, epoch, it) }
        } ?: return
        worker.job.start()
    }

    private fun startVisible(record: ArtifactTransferRecord) {
        val notification = ArtifactTransferNotifications.build(this, record, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun refreshForeground() {
        if (!ApplicationWorkAdmission.isOpen) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        val epoch = ApplicationWorkAdmission.currentEpoch
        val published = withArtifactTransferAdmission(epoch) {
            val currentIds = ArtifactTransferExitWorkers.snapshot().filter { it.epoch == epoch }.mapTo(hashSetOf()) { it.transferId }
            val first = jobs.keys().firstOrNull { it in currentIds }?.let(store::read)
            if (first == null) false else {
                val count = jobs.size()
                val notification = if (count <= 1) ArtifactTransferNotifications.build(this, first, false)
                    else ArtifactTransferNotifications.buildAggregate(this, count)
                getSystemService(NotificationManager::class.java).notify(FOREGROUND_NOTIFICATION_ID, notification)
                true
            }
        } ?: false
        if (!published) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() {
        try {
            val epochs = ArtifactTransferExitWorkers.snapshot().associate { it.job to it.epoch }
            for ((id, worker) in jobs.clear()) {
                try {
                    epochs[worker.job]?.let { epoch ->
                        withArtifactTransferAdmission(epoch) {
                            store.markRetry(id, worker.identity, "Visible transfer service destroyed; verified partial data retained")
                        }
                    }
                } catch (failure: Throwable) { ArtifactTransferExitWorkers.recordFailure(failure) }
            }
        } finally { scope.cancel(); super.onDestroy() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START = "com.baystudio.droide.action.PACKAGE_TRANSFER_START"
        private const val ACTION_CANCEL = "com.baystudio.droide.action.PACKAGE_TRANSFER_CANCEL"
        internal const val FOREGROUND_NOTIFICATION_ID = 7211

        fun start(context: Context, id: String, requestIdentity: String?, epoch: String? = null) {
            ApplicationWorkAdmission.initialize(context)
            val admittedEpoch = epoch ?: ApplicationWorkAdmission.requireOpen()
            ApplicationWorkAdmission.withOpen(admittedEpoch) {
                val intent = Intent(context, PackageTransferForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID, id)
                    .putExtra(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY, requestIdentity)
                    .putExtra(ArtifactTransferStore.EXTRA_ADMISSION_EPOCH, admittedEpoch)
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        }

        fun cancel(context: Context, id: String, requestIdentity: String?) {
            ApplicationWorkAdmission.initialize(context)
            val epoch = ApplicationWorkAdmission.currentEpoch
            withArtifactTransferAdmission(epoch) {
                context.startService(Intent(context, PackageTransferForegroundService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID, id)
                    .putExtra(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY, requestIdentity)
                    .putExtra(ArtifactTransferStore.EXTRA_ADMISSION_EPOCH, epoch))
            }
        }
    }
}

class ArtifactTransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID) ?: return
        val requestIdentity = intent.getStringExtra(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY) ?: return
        val epoch = intent.getStringExtra(ArtifactTransferStore.EXTRA_ADMISSION_EPOCH) ?: return
        UserInitiatedArtifactTransfer.cancelIfCurrent(context, id, requestIdentity, epoch, "Transfer canceled from notification")
    }
}

internal class ArtifactTransferNotificationGate {
    private var lastPhase: TrustedArtifactDownloadPhase? = null
    private var lastNotificationAtMs: Long? = null

    fun shouldNotify(progress: TrustedArtifactDownloadProgress, nowMs: Long): Boolean {
        val previous = lastNotificationAtMs
        if (previous != null && progress.phase == lastPhase && nowMs - previous < 750L) return false
        lastNotificationAtMs = nowMs
        lastPhase = progress.phase
        return true
    }
}

internal object ArtifactTransferNotifications {
    private const val CHANNEL_ID = "droide_package_transfers"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Package transfers", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Downloads explicitly started for Droide packages, toolchains, and VM images"
                    setShowBadge(false)
                }
            )
        }
    }

    fun build(context: Context, record: ArtifactTransferRecord, indeterminate: Boolean): Notification {
        val total = record.totalBytes
        val percent = if (total != null && total > 0L) ((record.bytesDownloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        return Notification.Builder(context, CHANNEL_ID)
            .withDroideIcons(context)
            .setContentTitle("Droide package transfer")
            .setContentText(record.detail.ifBlank {
                TrustedArtifactDownloadProgress(record.bytesDownloaded, total).describe()
            }.take(300))
            .setSubText(record.fileName.take(80))
            .setOnlyAlertOnce(true)
            .setOngoing(record.state in setOf(ArtifactTransferState.QUEUED, ArtifactTransferState.RUNNING))
            .setProgress(100, percent, indeterminate || total == null || record.detail.isNotBlank())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent(context, record)).build())
            .build()
    }

    fun buildAggregate(context: Context, count: Int): Notification = Notification.Builder(context, CHANNEL_ID)
        .withDroideIcons(context)
        .setContentTitle("Droide package transfers")
        .setContentText("$count verified transfers running")
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()

    private fun cancelIntent(context: Context, record: ArtifactTransferRecord): PendingIntent {
        val epoch = ApplicationWorkAdmission.requireOpen()
        return PendingIntent.getBroadcast(context, ArtifactTransferStore.jobId(record.id),
            Intent(context, ArtifactTransferCancelReceiver::class.java)
                .setAction("com.baystudio.droide.action.CANCEL_ARTIFACT_REQUEST")
                // Distinct PendingIntent identity prevents FLAG_UPDATE_CURRENT from upgrading a stale
                // notification's cancellation authority to a later request/session.
                .setData(Uri.parse("droide-artifact-transfer://cancel/$epoch/${record.requestIdentity}/${record.id}"))
                .putExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID, record.id)
                .putExtra(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY, record.requestIdentity)
                .putExtra(ArtifactTransferStore.EXTRA_ADMISSION_EPOCH, epoch),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
