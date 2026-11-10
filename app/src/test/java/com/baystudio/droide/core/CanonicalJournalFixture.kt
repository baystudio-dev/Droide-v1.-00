package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.coroutines.EmptyCoroutineContext

/** Explicit test enrollment only. The production journal must never create an authority. */
internal class CanonicalJournalFixture(
    val base: File = Files.createTempDirectory("canonical-journal-").toFile(),
) : Closeable {
    val root: File = File(base, "project").also { check(it.mkdir()) }
    val authority: File = File(base, "authority").also { check(it.mkdir()) }
    val custody: File = File(authority, "custody").also { check(it.mkdir()) }
    val database = File(authority, "state.sqlite")
    val path = "src/deep/file.kt"
    val source = File(root, path)
    val bytes = ByteArray(150_000) { (it % 251).toByte() }
    val proposedBytes = ByteArray(97) { (255 - it).toByte() }
    val enrollment: CanonicalFileOperationJournal.Enrollment

    init {
        check(source.parentFile.mkdirs())
        write(source, bytes)
        SQLiteDatabase.openDatabase(database.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or
                SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            configure(db)
            CanonicalFileOperationJournal.SCHEMA_SQL.forEach(db::execSQL)
            db.version = 1
        }
        enrollment = CanonicalFileOperationJournal.Enrollment(
            projectId = "canonical-journal-fixture",
            rootPath = root.canonicalPath,
            rootKey = key(root),
            authorityPath = authority.canonicalPath,
            authorityKey = key(authority),
            databaseKey = key(database),
            custodyKey = key(custody),
            generation = "1066e46f-30db-4415-8197-66d972246771",
            authorityParentKey = key(authority.parentFile),
        )
        sql { db ->
            db.execSQL("INSERT INTO meta VALUES(1,?,?,NULL,NULL)",
                arrayOf(CanonicalFileOperationJournal.enrollmentHash(enrollment), enrollment.generation))
        }
    }

    fun journal(checkpoint: (String) -> Unit = {}, sizeLimit: Long? = null) =
        CanonicalFileOperationJournal(root, authority, enrollment, bytes.size.toLong(), checkpoint, sizeLimit)

    fun before(file: File = source): CanonicalFileSnapshot.Version =
        AnchoredWorkspaceFiles(file.parentFile).use {
            CanonicalFileSnapshot(bytes.size.toLong()).inspect(it, file.name, EmptyCoroutineContext)
        }

    fun proposal() = CanonicalFileOperationJournal.Content(
        proposedBytes.size.toLong(), sha256(proposedBytes), 420,
    )

    fun sql(action: (SQLiteDatabase) -> Unit) = openSql(database, action)

    fun snapshotFile(id: String) = File(custody, "$id.before")

    override fun close() {
        // No FOLLOW_LINKS: only the fixture tree and links in it are removed.
        Files.walkFileTree(base.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    companion object {
        fun key(file: File): String = checkNotNull(
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey(),
        ).toString()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

        fun write(file: File, bytes: ByteArray) {
            Files.write(file.toPath(), bytes)
            Files.setAttribute(file.toPath(), "unix:mode", 420)
        }

        fun openSql(file: File, action: (SQLiteDatabase) -> Unit) {
            SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                configure(db)
                action(db)
            }
        }

        private fun configure(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            db.execSQL("PRAGMA synchronous=EXTRA")
        }
    }
}
