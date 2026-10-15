package com.androidharness.app.data.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Logical file bytes, with links never followed. Hard links count once per inventory. */
internal class StorageCounter {
    private val identities = mutableSetOf<Any>()
    var incomplete = false
        private set
    private var visited = 0

    suspend fun bytes(root: File, excluded: Set<Path> = emptySet(), depth: Int = 0): Long {
        currentCoroutineContext().ensureActive()
        val path = root.toPath().toAbsolutePath().normalize()
        if (path in excluded) return 0
        if (++visited > 200_000 || depth > 64) { incomplete = true; return 0 }
        return try {
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            when {
                a.isSymbolicLink -> 0
                a.isRegularFile -> if (identities.add(a.fileKey() ?: path)) a.size() else 0
                a.isDirectory -> Files.newDirectoryStream(path).use { entries ->
                    var sum = 0L
                    for (child in entries) sum += bytes(child.toFile(), excluded, depth + 1)
                    sum
                }
                else -> 0
            }
        } catch (_: java.nio.file.NoSuchFileException) { 0 }
        catch (e: java.io.IOException) { incomplete = true; 0 }
        catch (e: SecurityException) { incomplete = true; 0 }
    }
}

@kotlinx.serialization.Serializable
data class StorageStamp(val path: String, val size: Long, val modified: Long,
    val identity: String?, val directory: Boolean, val sha256: String)

/** A preview is a capability for one exact tree, not a caller-supplied delete path. */
class StorageCandidate internal constructor(
    val id: String, val title: String, val detail: String, val group: String,
    internal val anchor: File, internal val target: File,
    internal val stamps: List<StorageStamp>,
    internal val packageName: String? = null,
) {
    val bytes: Long = stamps.filterNot { it.directory }.sumOf { it.size }
}

data class StorageCleanupResult(val removedBytes: Long, val removedFiles: Int, val skipped: Int)

internal object StorageFiles {
    private const val MAX_ENTRIES = 50_000
    private fun attrs(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    /** Refuse symlinks in or below the selected root; allow Android aliases above it. */
    fun contained(anchor: File, target: File): Boolean {
        val a = anchor.toPath().toAbsolutePath().normalize()
        val t = target.toPath().toAbsolutePath().normalize()
        if (t == a || !t.startsWith(a)) return false
        var p: Path? = t
        while (p != null && p.startsWith(a)) {
            if (Files.isSymbolicLink(p)) return false
            p = p.parent
        }
        // Android's /data/user/0 and /sdcard aliases above the chosen root are legitimate.
        return target.canonicalFile.toPath().startsWith(anchor.canonicalFile.toPath())
    }

    suspend fun snapshot(anchor: File, target: File, withHashes: Boolean = true): List<StorageStamp> {
        require(contained(anchor, target)) { "Storage path changed. Scan again." }
        val out = mutableListOf<StorageStamp>()
        suspend fun visit(path: Path, depth: Int = 0) {
            check(depth <= 64) { "Folder is too deep to review safely." }
            currentCoroutineContext().ensureActive()
            check(out.size < MAX_ENTRIES) { "Too many files to review safely." }
            val a = attrs(path)
            check(a.isDirectory || a.isRegularFile) { "Linked or special files are protected." }
            val hash = if (a.isRegularFile && withHashes) {
                val digest = MessageDigest.getInstance("SHA-256")
                // NOFOLLOW also rejects a last-component symlink swapped after the attribute read.
                Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
                    val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = channel.read(buffer)
                        if (n < 0) break
                        digest.update(buffer.array(), 0, n); buffer.clear()
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } else ""
            check(attrs(path).let { it.fileKey() == a.fileKey() && it.size() == a.size() && it.lastModifiedTime() == a.lastModifiedTime() }) {
                "Files changed during the scan. Try again."
            }
            out += StorageStamp(target.toPath().relativize(path).toString(), a.size().takeUnless { a.isDirectory } ?: 0,
                a.lastModifiedTime().toMillis(), a.fileKey()?.toString(), a.isDirectory, hash)
            if (a.isDirectory) Files.newDirectoryStream(path).use { entries ->
                for (child in entries.sortedBy { it.fileName.toString() }) visit(child, depth + 1)
            }
        }
        visit(target.toPath())
        return out
    }

    suspend fun candidate(id: String, title: String, detail: String, group: String, anchor: File, target: File,
        olderThan: Long): StorageCandidate? {
        if (!target.exists()) return null
        return try {
            val metadata = snapshot(anchor, target, withHashes = false)
            if (metadata.any { it.modified >= olderThan } || metadata.none { !it.directory && it.size > 0 }) null
            else {
                val stamps = snapshot(anchor, target)
                check(stamps.map { it.copy(sha256 = "") } == metadata) { "Files changed while preparing cleanup." }
                StorageCandidate(id, title, detail, group, anchor, target, stamps)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null } // Unknown ownership/access is never a cleanup candidate.
    }

    suspend fun clean(candidates: List<StorageCandidate>): StorageCleanupResult {
        var bytes = 0L; var files = 0; var skipped = 0
        val seen = mutableSetOf<String>()
        for (c in candidates) {
            currentCoroutineContext().ensureActive()
            if (!seen.add(c.target.path)) continue
            val fresh = try { snapshot(c.anchor, c.target) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
            if (fresh != c.stamps) { skipped++; continue }
            // Delete ONLY the reviewed files. Never recursively delete a directory: new files stay intact.
            for (s in c.stamps.filterNot { it.directory }) {
                val file = if (s.path.isEmpty()) c.target else File(c.target, s.path)
                if (!contained(c.anchor, file)) { skipped++; break }
                try {
                    val a = attrs(file.toPath())
                    if (!a.isRegularFile || a.fileKey()?.toString() != s.identity || a.size() != s.size ||
                        a.lastModifiedTime().toMillis() != s.modified) { skipped++; continue }
                    Files.delete(file.toPath()); bytes += s.size; files++
                } catch (_: java.io.IOException) { skipped++ }
            }
            c.stamps.filter { it.directory }.asReversed().forEach { s ->
                val dir = if (s.path.isEmpty()) c.target else File(c.target, s.path)
                if (contained(c.anchor, dir)) runCatching { Files.delete(dir.toPath()) }
            }
        }
        return StorageCleanupResult(bytes, files, skipped)
    }
}
