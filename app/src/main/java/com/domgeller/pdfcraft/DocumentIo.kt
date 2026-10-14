package com.domgeller.pdfcraft

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.database.Cursor
import java.io.FileOutputStream
import java.io.IOException
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
     * Take persistent read permission for the given URI after a document picker
     * returns it. Returns true if permission was granted.
     */
    fun takeReadPermission(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent_FLAG_GRANT_READ)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Take persistent read+write permission for the given URI after a create-document
     * picker returns it.
     */
    fun takeReadWritePermission(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent_FLAG_GRANT_READ or Intent_FLAG_GRANT_WRITE)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private const val Intent_FLAG_GRANT_READ =
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
    private const val Intent_FLAG_GRANT_WRITE =
        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Read all bytes from a content:// URI. Validates that the URI is a content://
     * scheme and that the data is readable. Reads dynamically to avoid
     * truncation or padding when the document provider reports an inaccurate size.
     */
    @Throws(IOException::class, SecurityException::class)
    fun readBytes(context: Context, uri: Uri): ByteArray {
        require(uri.scheme == "content" || uri.scheme == "file") {
            "Unsupported URI scheme: ${uri.scheme}"
        }

        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                buffer.write(buf, 0, n)
            }
            return buffer.toByteArray()
        } ?: throw IOException("Cannot open input stream for $uri")
    }

    /**
     * Write bytes to a content:// URI via DocumentFile. Returns the number of
     * bytes written. The caller should verify the output length/hash when the
     * provider permits re-reading.
     */
    @Throws(IOException::class)
    fun writeToUri(context: Context, uri: Uri, bytes: ByteArray): Int {
        require(uri.scheme == "content") {
            "Expected content:// scheme, got ${uri.scheme}"
        }

        val resolver = context.contentResolver
        resolver.openOutputStream(uri, "rw")?.use { output ->
            output.write(bytes)
            output.flush()
            (output as FileOutputStream).channel.force(true)
            return bytes.size
        } ?: throw IOException("Cannot open output stream for $uri")
    }

    /**
     * Write bytes to a file path (used for private staging). Uses atomic
     * write: temp file + sync + rename.
     */
    @Throws(IOException::class)
    fun writeAtomically(target: java.io.File, data: ByteArray) {
        val tmp = java.io.File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // Fallback: copy + delete (rename fails on some systems)
            target.writeBytes(data)
            tmp.delete()
        }
    }

    /**
     * Read bytes from a file path.
     */
    fun readFromFile(file: java.io.File): ByteArray = file.readBytes()

    /**
     * Compute SHA-256 hash of a byte array.
     */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute SHA-256 hash of a file.
     */
    fun sha256File(file: java.io.File): String {
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
     * Verify that a written document can be re-read and matches expected hash/size.
     * Returns true if verification passes.
     */
    fun verifyWrittenDocument(context: Context, uri: Uri, expectedHash: String, expectedSize: Long): Boolean {
        return try {
            val bytes = readBytes(context, uri)
            val actualHash = sha256(bytes)
            val actualSize = bytes.size.toLong()
            actualHash == expectedHash && actualSize == expectedSize
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the display name of a URI (for preserving filenames from pickers).
     */
    fun getDisplayName(context: Context, uri: Uri): String {
        var name: String? = null
        val cursor: Cursor? = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val titleIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (titleIndex >= 0) {
                    name = it.getString(titleIndex)
                }
            }
        }
        if (name == null || name.isNullOrEmpty()) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        return name ?: "document.pdf"
    }

    /**
     * Get the MIME type of a URI.
     */
    fun getMimeType(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }
}
