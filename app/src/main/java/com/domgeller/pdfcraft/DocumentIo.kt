package com.domgeller.pdfcraft

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * DocumentIo — utilities for reading from and writing to Android document URIs.
 *
 * All file access comes through user-selected content:// URIs (Storage Access
 * Framework). We never convert document-provider URIs into filesystem paths.
 *
 * Never treat an arbitrary incoming URI as a remote navigation URL.
 */
object DocumentIo {

    private const val TAG = "DocumentIo"
    private const val BUFFER_SIZE = 8192

    /**
     * Phone-safe maximum single-file import size (100 MiB) to protect WebView and
     * JVM heap memory before Base64 chunking and WASM allocation.
     */
    const val MAX_IMPORT_BYTES: Long = 100L * 1024 * 1024

    private const val Intent_FLAG_GRANT_READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private const val Intent_FLAG_GRANT_WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Hook for descriptor durability sync. Defaults to direct FileDescriptor.sync()
     * so disk-sync failures propagate rather than being swallowed, and can be overridden
     * in unit tests for deterministic failure-injection coverage.
     */
    @Volatile
    internal var syncDescriptor: (FileDescriptor) -> Unit = { fd -> fd.sync() }

    enum class VerificationOutcome {
        /** Provider allowed re-reading and both byte length and SHA-256 matched. */
        VERIFIED,
        /** Write flushed cleanly, but provider does not support re-reading the URI. */
        UNAVAILABLE,
        /** Re-read succeeded or partially succeeded, and size or SHA-256 did not match. */
        MISMATCH
    }

    data class VerificationResult(
        val outcome: VerificationOutcome,
        val detail: String? = null
    )

    fun takeReadPermission(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(uri, Intent_FLAG_GRANT_READ)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun takeReadWritePermission(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent_FLAG_GRANT_READ or Intent_FLAG_GRANT_WRITE
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Read all bytes from a content:// URI under a phone-safe size cap (MAX_IMPORT_BYTES).
     * Checks provider-reported OpenableColumns.SIZE as an early reject hint, then reads
     * dynamically to EOF while enforcing maxBytes on every chunk.
     */
    @Throws(IOException::class, SecurityException::class)
    fun readBytes(context: Context, uri: Uri, maxBytes: Long = MAX_IMPORT_BYTES): ByteArray {
        require(uri.scheme == "content" || uri.scheme == "file") {
            "Unsupported URI scheme: ${uri.scheme}"
        }
        if (maxBytes <= 0L) {
            throw IOException("Invalid maximum import byte limit: $maxBytes")
        }

        val reportedSize = queryReportedSize(context, uri)
        if (reportedSize != null && reportedSize > maxBytes) {
            throw IOException(
                "Document size ($reportedSize bytes) exceeds phone-safe limit ($maxBytes bytes)"
            )
        }

        val resolver = context.contentResolver
        try {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                var totalRead = 0L
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    totalRead += n
                    if (totalRead > maxBytes) {
                        throw IOException(
                            "Document exceeds maximum supported import size ($maxBytes bytes)"
                        )
                    }
                    buffer.write(buf, 0, n)
                }
                return buffer.toByteArray()
            } ?: throw IOException("Cannot open input stream for $uri")
        } catch (oom: OutOfMemoryError) {
            throw IOException("Document is too large for available device memory", oom)
        }
    }

    private fun queryReportedSize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    @Throws(IOException::class)
    private fun openTruncatingOutputStream(context: Context, uri: Uri): OutputStream {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            resolver.openOutputStream(uri)
        } ?: throw IOException("Cannot open output stream for $uri")
        if (stream is FileOutputStream) {
            runCatching { stream.channel.truncate(0L) }
        }
        return stream
    }

    private fun flushAndForceQuietly(output: OutputStream) {
        output.flush()
        if (output is FileOutputStream) {
            runCatching { output.channel.force(true) }
        }
    }

    @Throws(IOException::class)
    fun writeToUri(context: Context, uri: Uri, bytes: ByteArray): Int {
        require(uri.scheme == "content") {
            "Expected content:// scheme, got ${uri.scheme}"
        }
        openTruncatingOutputStream(context, uri).use { output ->
            output.write(bytes)
            flushAndForceQuietly(output)
            return bytes.size
        }
    }

    @Throws(IOException::class)
    fun copyFileToUri(context: Context, sourceFile: File, destUri: Uri): Long {
        require(destUri.scheme == "content") {
            "Expected content:// scheme, got ${destUri.scheme}"
        }
        if (!sourceFile.exists()) {
            throw IOException("Staged file does not exist: ${sourceFile.name}")
        }

        var totalWritten = 0L
        sourceFile.inputStream().use { input ->
            openTruncatingOutputStream(context, destUri).use { output ->
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    totalWritten += n
                }
                flushAndForceQuietly(output)
            }
        }
        return totalWritten
    }

    /**
     * Write bytes to a file path atomically using a synced temporary file and NIO
     * atomic move (never overwriting the target file non-atomically in place).
     */
    @Throws(IOException::class)
    fun writeAtomically(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(data)
                out.flush()
                syncDescriptor(out.fd)
            }
            moveAtomically(tmp, target)
        } finally {
            if (tmp.exists()) {
                runCatching { tmp.delete() }
            }
        }
    }

    @Throws(IOException::class)
    internal fun moveAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    fun readFromFile(file: File): ByteArray = file.readBytes()

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Detailed 3-state verification distinguishing "independently verified",
     * "verification unavailable on this provider", and "content mismatch".
     */
    fun verifyWrittenDocumentDetailed(
        context: Context,
        uri: Uri,
        expectedHash: String,
        expectedSize: Long
    ): VerificationResult {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            return VerificationResult(
                VerificationOutcome.UNAVAILABLE,
                "Provider does not grant read-back permission (${e.message})"
            )
        } catch (e: UnsupportedOperationException) {
            return VerificationResult(
                VerificationOutcome.UNAVAILABLE,
                "Provider does not support reading back written documents (${e.message})"
            )
        } catch (e: FileNotFoundException) {
            return VerificationResult(
                VerificationOutcome.UNAVAILABLE,
                "Write-only provider stream (${e.message})"
            )
        } catch (e: Exception) {
            return VerificationResult(
                VerificationOutcome.MISMATCH,
                "Unable to open written document for verification: ${e.message}"
            )
        }

        if (stream == null) {
            return VerificationResult(
                VerificationOutcome.UNAVAILABLE,
                "Provider returned null input stream for read-back verification"
            )
        }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var actualSize = 0L
            stream.use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                    actualSize += n
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSize == expectedSize && actualHash.equals(expectedHash, ignoreCase = true)) {
                VerificationResult(VerificationOutcome.VERIFIED)
            } else {
                VerificationResult(
                    VerificationOutcome.MISMATCH,
                    "Expected $expectedSize bytes (sha256=$expectedHash), got $actualSize bytes (sha256=$actualHash)"
                )
            }
        } catch (e: Exception) {
            VerificationResult(
                VerificationOutcome.MISMATCH,
                "Error reading back written document: ${e.message}"
            )
        }
    }

    fun verifyWrittenDocument(
        context: Context,
        uri: Uri,
        expectedHash: String,
        expectedSize: Long
    ): Boolean = verifyWrittenDocumentDetailed(context, uri, expectedHash, expectedSize).outcome ==
        VerificationOutcome.VERIFIED

    fun getDisplayName(context: Context, uri: Uri): String {
        var name: String? = null
        val cursor: Cursor? = try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )
        } catch (_: Exception) {
            null
        }
        cursor?.use {
            if (it.moveToFirst()) {
                val titleIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (titleIndex >= 0) {
                    name = it.getString(titleIndex)
                }
            }
        }
        if (name.isNullOrEmpty()) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        return name?.takeIf { it.isNotBlank() } ?: "document.pdf"
    }

    fun getMimeType(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }
}
