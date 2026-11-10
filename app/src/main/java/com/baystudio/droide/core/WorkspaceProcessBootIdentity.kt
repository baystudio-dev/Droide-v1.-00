package com.baystudio.droide.core

import android.content.Context
import android.provider.Settings
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** A different boot on the same Android device cannot contain an earlier boot's processes or
 * descriptors. */
internal object WorkspaceProcessBootIdentity {
    data class Identity(val device: String, val boot: String) {
        init {
            require(device.matches(Regex("[0-9a-f]{64}")))
            require(UUID.fromString(boot).toString() == boot && boot != ZERO_UUID)
        }
    }

    fun forApplication(context: Context, working: File,
        boot: () -> String? = { File("/proc/sys/kernel/random/boot_id").inputStream().use(::readBootId) },
    ): Identity? = fromSources(context, working, boot) {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
    }

    internal fun fromSources(context: Context, working: File, boot: () -> String?, bootCount: () -> Int?,
    ): Identity? = try {
        // Production mirrors belong to app-private storage, not a shared filesystem on another machine.
        // Device identity must also match before records can retire.
        if (!working.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath())) null
        else {
            val device = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.lowercase(Locale.ROOT)
            if (device == null || !device.matches(Regex("[0-9a-f]{16}")) ||
                device in setOf("0000000000000000", "9774d56d682e549c")) null
            else {
                val currentBoot = runCatching { boot() }.getOrNull()?.takeIf { value ->
                    runCatching { UUID.fromString(value).toString() == value && value != ZERO_UUID }.getOrDefault(false)
                }
                if (currentBoot != null) Identity(hash("droide-process-device-v1\n${context.packageName}\n$device"), currentBoot)
                else runCatching { bootCount() }.getOrNull()?.takeIf { it >= 0 }?.let { count ->
                    // BOOT_COUNT is a system-owned boot generation (API 24+), not uptime or a PID.

                    Identity(hash("droide-process-device-boot-count-v1\n${context.packageName}\n$device"),
                        UUID.nameUUIDFromBytes("droide-android-boot-count-v1\n$count".toByteArray(Charsets.UTF_8)).toString())
                }
            }
        }
    } catch (_: Exception) { null }

    internal fun readBootId(input: InputStream): String? {
        val bytes = ByteArray(65); var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            if (count == 0) return null
            size += count
        }
        if (size !in 36..64) return null
        val value = String(bytes, 0, size, Charsets.US_ASCII).trim()
        return runCatching { value.takeIf { UUID.fromString(it).toString() == it && it != ZERO_UUID } }.getOrNull()
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"
}
