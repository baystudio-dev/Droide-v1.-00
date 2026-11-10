package com.baystudio.droide.core

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android no-follow open/fstat adapter used by both selection validation and owner admission. */
object CMakeSelectionInspector : CMakeAnalysisSelections.Inspector {
    internal fun requireCanonicalNoSymlinks(file: File) {
        require(file.isAbsolute && file.absolutePath.length <= 4096)
        var current: File? = file
        while (current != null) {
            require(!Files.isSymbolicLink(current.toPath())) { "CMake path has a symbolic-link component" }
            current = current.parentFile
        }
        require(file.canonicalPath == file.absolutePath) { "CMake path is not canonical" }
    }
    override fun directoryIdentity(directory: File, environmentIdentity: String): CMakeDirectoryIdentity {
        requireCanonicalNoSymlinks(directory)
        val stat = Os.lstat(directory.absolutePath)
        require(OsConstants.S_ISDIR(stat.st_mode)) { "Selected CMake directory is unavailable" }
        return CMakeDirectoryIdentity(environmentIdentity, stat.st_dev, stat.st_ino, stat.st_uid.toLong(), stat.st_mode)
    }
    override fun readCache(cache: File, maximumBytes: Int): ByteArray {
        require(maximumBytes in 1..1_048_576)
        requireCanonicalNoSymlinks(cache)
        val descriptor = Os.open(cache.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        return try { FileInputStream(descriptor).use { stream ->
            val before = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(before.st_mode) && before.st_size in 1..maximumBytes.toLong()) { "CMake cache is not bounded regular data" }
            val bytes = stream.readNBytesBounded(maximumBytes)
            val after = Os.fstat(descriptor)
            val named = Os.lstat(cache.absolutePath)
            require(before.st_dev == after.st_dev && before.st_ino == after.st_ino && before.st_size == after.st_size &&
                before.st_mtime == after.st_mtime && before.st_ctime == after.st_ctime &&
                named.st_dev == after.st_dev && named.st_ino == after.st_ino && named.st_size == after.st_size &&
                named.st_mtime == after.st_mtime && named.st_ctime == after.st_ctime && OsConstants.S_ISREG(named.st_mode) &&
                bytes.size.toLong() == after.st_size) { "CMake cache changed during observation" }
            bytes
        } } finally {
            // Android's FileInputStream(FileDescriptor) does not own the supplied descriptor. JVM fixture
            // streams may close it; never close an already invalid descriptor twice.
            if (descriptor.valid()) Os.close(descriptor)
        }
    }
    private fun FileInputStream.readNBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= limit) { "CMake cache exceeded bound" }
        }
        return output.toByteArray()
    }
}

/** All directories belong to one exact owner; private residue never becomes a project grant. */
class CMakePrivateState private constructor(
    val localRoot: File,
    val guestRoot: String,
    val identity: CMakeDirectoryIdentity,
    private val originalRootFileKey: Any,
    private val registry: File,
    private val directoryIdentities: Map<String, CMakeDirectoryIdentity>,
) {
    @Volatile var cleanupStatus: CMakePrivateCleanupStatus = CMakePrivateCleanupStatus.NOT_STARTED
        private set
    private var guestBindTarget: Pair<File, CMakeDirectoryIdentity>? = null
    internal fun registerGuestBindTarget(target: File) {
        check(guestBindTarget == null)
        guestBindTarget = target to CMakeSelectionInspector.directoryIdentity(target, identity.environmentIdentity)
    }
    fun guest(child: String): String {
        require(child in DIRECTORIES || child in setOf("launch.json", "outer.pid", "ready.json", "receipt.json", "stop"))
        return "$guestRoot/$child"
    }
    fun verifyRoot() {
        check(CMakeSelectionInspector.directoryIdentity(localRoot, identity.environmentIdentity) == identity)
        check(identity.ownerUid == Os.getuid().toLong() && identity.mode and 511 == 448)
    }
    fun verify() {
        verifyRoot()
        DIRECTORIES.forEach { child ->
            val value = CMakeSelectionInspector.directoryIdentity(File(localRoot, child), identity.environmentIdentity)
            check(value == directoryIdentities[child] && value.ownerUid == identity.ownerUid && value.mode and 511 == 448)
        }
    }
    fun writeRecord(name: String, text: String) {
        require(name in setOf("launch.json", "stop") && text.toByteArray().size <= LocalUbuntuCMakeLaunchPolicy.MAXIMUM_RECORD_BYTES)
        verify()
        val target = File(localRoot, name)
        check(!target.exists() && !Files.isSymbolicLink(target.toPath()))
        durableWrite(target, text)
    }
    suspend fun cleanup(groupProvedGone: Boolean): CMakePrivateCleanupStatus = withContext(Dispatchers.IO) {
        if (cleanupStatus == CMakePrivateCleanupStatus.COMPLETE) return@withContext cleanupStatus
        if (!groupProvedGone) { quarantine("group release unproved"); return@withContext cleanupStatus }
        val deadline = System.nanoTime() + LocalUbuntuCMakeLaunchPolicy.PRIVATE_CLEANUP_MS * 1_000_000
        val complete = runCatching {
            verify()
            val counts = longArrayOf(0, 0)
            Files.newDirectoryStream(localRoot.parentFile.toPath()).use { parentStream ->
                val parent = parentStream as? SecureDirectoryStream<Path>
                    ?: error("No fd-relative private cleanup support")
                val name = localRoot.toPath().fileName
                parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { owned ->
                    val rootKey = attributes(owned, null).fileKey()
                    require(rootKey == originalRootFileKey && rootKey == attributes(parent, name).fileKey())
                    cleanTree(owned, 0, deadline, counts)
                    require(attributes(parent, name).fileKey() == rootKey)
                    parent.deleteDirectory(name)
                }
            }
            guestBindTarget?.let { (target, expected) ->
                require(CMakeSelectionInspector.directoryIdentity(target, identity.environmentIdentity) == expected)
                require(target.listFiles()?.isEmpty() == true && target.delete())
                syncDirectory(target.parentFile)
            }
            true
        }.getOrDefault(false)
        if (complete) {
            cleanupStatus = CMakePrivateCleanupStatus.COMPLETE
            File(registry, "${localRoot.name}.allocation").delete()
            File(registry, "${localRoot.name}.quarantine").delete()
            syncDirectory(registry)
        } else quarantine("bounded identity-checked private cleanup incomplete")
        cleanupStatus
    }
    private fun cleanTree(directory: SecureDirectoryStream<Path>, depth: Int, deadline: Long, counts: LongArray) {
        require(depth <= 32)
        for (entry in directory) {
            require(System.nanoTime() < deadline && ++counts[0] <= 4096)
            val name = entry.fileName
            val before = attributes(directory, name)
            counts[1] += before.size()
            require(counts[1] <= 67_108_864 && !before.isSymbolicLink && before.fileKey() != null)
            // Unix owner and link identity are observed before any unlink. Same-UID races are not contained.
            val native = Os.lstat(entry.toString())
            require(native.st_uid.toLong() == identity.ownerUid)
            if (before.isDirectory) {
                directory.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
                    require(attributes(child, null).fileKey() == before.fileKey())
                    cleanTree(child, depth + 1, deadline, counts)
                }
                require(attributes(directory, name).fileKey() == before.fileKey())
                directory.deleteDirectory(name)
            } else {
                require(before.isRegularFile && native.st_nlink == 1L)
                require(attributes(directory, name).fileKey() == before.fileKey())
                directory.deleteFile(name)
            }
        }
    }
    private fun quarantine(reason: String) = synchronized(lock) {
        cleanupStatus = CMakePrivateCleanupStatus.QUARANTINED
        val target = File(registry, "${localRoot.name}.quarantine")
        val count = registry.listFiles()?.count { it.name.endsWith(".quarantine") }
            ?: error("CMake private quarantine registry unavailable")
        if (target.exists() || count < 32) {
            durableWrite(target, reason)
            File(registry, "${localRoot.name}.allocation").delete()
        } else {
            // Already admitted owners keep their original allocation slot. Never evict residue or
            // manufacture a 33rd quarantine record when simultaneous cleanup fails.
            durableWrite(File(registry, "${localRoot.name}.allocation"), "Private quarantine capacity exhausted; $reason; ${localRoot.path}")
        }
        syncDirectory(registry)
    }
    companion object {
        private val lock = Any()
        private val DIRECTORIES = setOf("cwd", "build", "home", "tmp", "xdg-config", "xdg-cache", "xdg-data", "xdg-state", "xdg-run")
        fun allocate(appRoot: File, environmentIdentity: String): CMakePrivateState = synchronized(lock) {
            val parent = File(appRoot, "run/cmake-private")
            val registry = File(appRoot, "run/cmake-private-records")
            listOf(parent, registry).forEach { dir ->
                CMakeSelectionInspector.requireCanonicalNoSymlinks(dir)
                check(!Files.isSymbolicLink(dir.toPath()) && (dir.isDirectory || dir.mkdirs()))
                Os.chmod(dir.absolutePath, 448)
                CMakeSelectionInspector.directoryIdentity(dir.canonicalFile, environmentIdentity)
            }
            val records = registry.listFiles() ?: error("Private owner registry unavailable")
            check(records.count { it.name.endsWith(".allocation") } < 32) { "CMake private owner capacity exhausted" }
            check(records.count { it.name.endsWith(".quarantine") } < 32) { "CMake private quarantine capacity exhausted" }
            // Unrecorded residue is never silently reclaimed or evicted.
            check((parent.listFiles()?.size ?: 33) < 64) { "CMake private residue capacity exhausted" }
            val root = File(parent, UUID.randomUUID().toString().replace("-", ""))
            check(root.mkdir()); Os.chmod(root.absolutePath, 448)
            DIRECTORIES.forEach { name -> check(File(root, name).mkdir()); Os.chmod(File(root, name).absolutePath, 448) }
            val state = CMakePrivateState(root.canonicalFile, "/opt/droide/cmake-owner/${root.name}",
                CMakeSelectionInspector.directoryIdentity(root.canonicalFile, environmentIdentity),
                requireNotNull(Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()), registry,
                DIRECTORIES.associateWith { CMakeSelectionInspector.directoryIdentity(File(root, it), environmentIdentity) })
            durableWrite(File(registry, "${root.name}.allocation"), root.canonicalPath)
            state.verify(); state
        }
        private fun attributes(stream: SecureDirectoryStream<Path>, name: Path?): BasicFileAttributes =
            if (name == null) stream.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
            else stream.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
        internal fun durableWrite(file: File, value: String) {
            require(value.toByteArray().size <= 16_384)
            val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
            val fd = Os.open(temporary.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
            try { FileOutputStream(fd).use { it.write(value.toByteArray()); it.flush(); it.fd.sync() } }
            finally { if (fd.valid()) Os.close(fd) }
            Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(file.parentFile)
        }
        internal fun syncDirectory(directory: File) {
            // O_DIRECTORY is not exposed by the public Android SDK. Inspect the opened descriptor, retaining
            // no-follow and avoiding a blocking open of a replaced FIFO.
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
            try {
                check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "CMake durability target is not a directory" }
                Os.fsync(fd)
            } finally { Os.close(fd) }
        }
    }
}
