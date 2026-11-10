package com.baystudio.droide.core

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** API30+ public platform calls only. API29 never probes an unapproved raw syscall in the app. */
internal object AndroidSealedHookEvent : ImmutableHookEventFactory {
    override fun create(write: (OutputStream) -> Unit, takeOwnership: (ImmutableHookEvent) -> Unit, checkCurrent: () -> Unit): ImmutableHookEvent {
        if (Build.VERSION.SDK_INT < 30) throw SourceBackedHookTransportUnavailableException(
            "Immutable large hook input requires a supported sealed-descriptor backend; Android 29 is not yet supported")
        return Api30.create(write, takeOwnership, checkCurrent)
    }

    @android.annotation.TargetApi(30)
    private object Api30 {

        private const val ADD_SEALS = 1033
        private const val GET_SEALS = 1034
        private const val ALL_SEALS = 0x0001 or 0x0002 or 0x0004 or 0x0008
        fun create(write: (OutputStream) -> Unit, takeOwnership: (ImmutableHookEvent) -> Unit, checkCurrent: () -> Unit): ImmutableHookEvent {
            checkCurrent()
            val reservation = HookKernelMemory.reserve()
            var lease: Lease? = null
            try {
                val original = Os.memfd_create("droide-hook-event", OsConstants.MFD_CLOEXEC or 2  )
                val descriptor = try { ParcelFileDescriptor.dup(original) } finally { Os.close(original) }
                lease = Lease(descriptor, reservation)
                takeOwnership(lease) // Before writes and before any suspended return can lose cleanup ownership.
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { raw ->
                    val admitted = object : OutputStream() {
                        override fun write(value: Int) { val b = byteArrayOf(value.toByte()); write(b, 0, 1) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            checkCurrent()
                            val next = Math.addExact(count, length.toLong())
                            reservation.grow(next)
                            raw.write(bytes, offset, length)
                            count = next
                        }
                    }
                    DigestOutputStream(admitted, digest).buffered(16 * 1024).use(write)
                }
                checkCurrent()
                val expected = digest.digest()
                Os.fcntlInt(descriptor.fileDescriptor, ADD_SEALS, ALL_SEALS)
                lease.requireSealed()
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                val actual = MessageDigest.getInstance("SHA-256")
                var read = 0L
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                    val bytes = ByteArray(16 * 1024)
                    while (true) {
                        checkCurrent(); val n = input.read(bytes); if (n < 0) break
                        check(n > 0); read = Math.addExact(read, n.toLong()); actual.update(bytes, 0, n)
                    }
                }
                check(read == count && MessageDigest.isEqual(expected, actual.digest())) { "Sealed hook bytes did not verify" }
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                return lease
            } catch (failure: Throwable) {
                try { lease?.close() ?: reservation.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                if (failure is kotlinx.coroutines.CancellationException || failure is SourceBackedHookTransportUnavailableException ||
                    (failure !is Exception && failure !is LinkageError)) throw failure
                throw SourceBackedHookTransportUnavailableException("Immutable hook input is unavailable on this execution environment", failure)
            }
        }

        private class Lease(private val descriptor: ParcelFileDescriptor, private val reservation: HookKernelMemory.Reservation) : ImmutableHookEvent {
            private val closed = AtomicBoolean(false)
            override val parentDescriptorPath: String get() {
                check(!closed.get()); return "/proc/${android.os.Process.myPid()}/fd/${descriptor.fd}"
            }
            override fun requireSealed() {
                check(!closed.get()) { "Immutable hook input is closed" }
                check(Os.fcntlInt(descriptor.fileDescriptor, GET_SEALS, 0) and ALL_SEALS == ALL_SEALS) {
                    "Kernel did not seal the hook event"
                }
            }
            override fun close() {
                if (closed.compareAndSet(false, true)) try { descriptor.close() } finally { reservation.close() }
            }
        }
    }
}

/** memfd consumes kernel memory, not disk. Reserve adaptively, before each allocation quantum. */
internal object HookKernelMemory {
    private const val QUANTUM = 1024L * 1024L
    private var reserved = 0L
    internal var availableBytes: () -> Long = {
        File("/proc/meminfo").bufferedReader().useLines { lines ->
            val kb = lines.firstOrNull { it.startsWith("MemAvailable:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
                ?: throw IllegalStateException("Available kernel memory is unknown")
            Math.multiplyExact(kb, 1024L)
        }
    }
    internal var maximumProcessShare: () -> Long = { Runtime.getRuntime().maxMemory() / 4L }
    fun reserve() = Reservation()
    class Reservation internal constructor() : AutoCloseable {
        private var bytes = 0L
        private var closed = false
        fun grow(required: Long) = synchronized(HookKernelMemory) {
            check(!closed); require(required >= 0)
            if (required <= bytes) return@synchronized
            val next = Math.multiplyExact(Math.addExact(required, QUANTUM - 1) / QUANTUM, QUANTUM)
            val delta = next - bytes
            val available = availableBytes()
            if (available <= 32L * QUANTUM || delta > (available - 32L * QUANTUM) / 8L ||
                Math.addExact(reserved, delta) > maximumProcessShare())
                throw SourceBackedHookTransportUnavailableException("Insufficient memory for immutable hook input; no hook was started")
            reserved += delta; bytes = next
        }
        override fun close() = synchronized(HookKernelMemory) {
            if (!closed) { reserved -= bytes; bytes = 0; closed = true }
        }
    }
}
