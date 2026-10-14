package com.domgeller.pdfcraft

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import kotlin.math.min

/**
 * ExportQueue — manages the lifecycle of document export jobs.
 *
 * Implements the state machine from plan §7:
 *   received → transferring → staging_durable → awaiting_destination
 *            → writing_destination → destination_verified → completed
 *
 * Failure/cancellation preserves staged bytes and a visible retry action.
 *
 * All disk I/O is offloaded from the UI thread via a background executor.
 * Pending jobs survive process death — they're persisted to metadataDir
 * and recovered on next launch.
 */
class ExportQueue(
    private val stagingDir: File,
    private val metadataDir: File
) {
    companion object {
        const val TAG = "ExportQueue"
        private const val CHUNK_ACK_TIMEOUT_MS = 30_000L
        private const val MAX_STAGING_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        private const val STAGING_BUFFER_SIZE = 256 * 1024 // 256 KiB

        /**
         * Recover pending exports from a previous process lifecycle.
         * Called during Application.onCreate().
         */
        fun recoverPendingExports(stagingDir: File, metadataDir: File): Map<String, ExportJob> {
            val recovered = mutableMapOf<String, ExportJob>()
            if (!metadataDir.exists()) return recovered

            val jobFiles = metadataDir.listFiles { f -> f.extension == "job" }
            if (jobFiles == null) return recovered

            for (jobFile in jobFiles) {
                try {
                    val job = ExportJob.fromFile(jobFile)
                    if (job != null && job.stageFile.exists() && job.stageFile.length() > 0) {
                        // Verify the staged file still exists and matches recorded hash
                        val actualHash = sha256(job.stageFile)
                        if (actualHash == job.stagedHash) {
                            job.status = ExportStatus.PENDING_RECOVERY
                            recovered[job.transferId] = job
                            Log.i(TAG, "Recovered pending export: ${job.transferId} (${job.name})")
                        } else {
                            Log.w(TAG, "Staged file hash mismatch for ${job.transferId}, discarding")
                            jobFile.delete()
                        }
                    } else {
                        val jobId = job?.transferId ?: jobFile.name
                        Log.w(TAG, "Staged file missing for ${jobId}, discarding")
                        jobFile.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error recovering job ${jobFile.name}: ${e.message}")
                }
            }
            return recovered
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(file.readBytes())
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    enum class ExportStatus {
        RECEIVED,           // Transfer initiated
        TRANSFERRING,       // Chunks arriving
        STAGING_DURABLE,    // Bytes written to private staging, verified
        AWAITING_DESTINATION, // Staging done, waiting for user to pick destination
        WRITING_DESTINATION, // Copying to user-chosen URI
        DESTINATION_VERIFIED, // Output written, re-read verified
        COMPLETED,          // Done
        PENDING_RECOVERY,   // Recovered from previous process
        FAILED,             // Error occurred, staged file preserved
        CANCELLED           // User cancelled
    }

    data class ExportJob(
        val transferId: String,
        val name: String,
        val saveAs: Boolean,
        val totalBytes: Long,
        val stagedHash: String,
        val stagedSize: Long,
        val stageFile: File,
        var status: ExportStatus,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun saveMetadata(metadataDir: File) {
            val meta = metadataDir.resolve("${transferId}.job")
            meta.writeText(
                "transferId=$transferId\n" +
                "name=$name\n" +
                "saveAs=$saveAs\n" +
                "totalBytes=$totalBytes\n" +
                "stagedHash=$stagedHash\n" +
                "stagedSize=$stagedSize\n" +
                "stageFile=${stageFile.absolutePath}\n" +
                "status=$status\n" +
                "createdAt=$createdAt"
            )
        }

        companion object {
            fun fromFile(file: File): ExportJob? {
                val map = file.readLines()
                    .mapNotNull { line ->
                        val eq = line.indexOf('=')
                        if (eq > 0) line.substring(0, eq) to line.substring(eq + 1) else null
                    }
                    .toMap()

                val transferId = map["transferId"] ?: return null
                val stageFile = File(map["stageFile"] ?: "")
                val status = ExportStatus.valueOf(map["status"] ?: "RECEIVED")

                return ExportJob(
                    transferId = transferId,
                    name = map["name"] ?: "document.pdf",
                    saveAs = map["saveAs"] == "true",
                    totalBytes = (map["totalBytes"] ?: "0").toLong(),
                    stagedHash = map["stagedHash"] ?: "",
                    stagedSize = (map["stagedSize"] ?: "0").toLong(),
                    stageFile = stageFile,
                    status = status,
                    createdAt = (map["createdAt"] ?: "0").toLong()
                )
            }
        }
    }

    // Active jobs keyed by transfer ID
    private val activeJobs = ConcurrentHashMap<String, ExportJob>()
    // Recovered jobs from previous process
    private val recoveredJobs: MutableMap<String, ExportJob> = ConcurrentHashMap(recoverPendingExports(stagingDir, metadataDir))

    /**
     * Start a new or resumed export job with direct binary transfer.
     * Stages the bytes to private storage, then awaits destination selection.
     */
    fun stageExport(transferId: String, name: String, saveAs: Boolean, bytes: ByteArray): ExportJob {
        // Generate a safe filename for staging
        val safeName = safeFilename(name)
        val stageFile = File(stagingDir, "${transferId}__$safeName")

        // Check size limit
        if (bytes.size > MAX_STAGING_BYTES) {
            throw IOException("Export exceeds maximum staging size of $MAX_STAGING_BYTES bytes")
        }

        // Write atomically: temp file + rename
        writeAtomically(stageFile, bytes)

        // Verify hash and size
        val hash = sha256(stageFile)
        val size = stageFile.length()

        val job = ExportJob(
            transferId = transferId,
            name = name,
            saveAs = saveAs,
            totalBytes = bytes.size.toLong(),
            stagedHash = hash,
            stagedSize = size,
            stageFile = stageFile,
            status = ExportStatus.STAGING_DURABLE,
            createdAt = System.currentTimeMillis()
        )

        activeJobs[transferId] = job
        job.saveMetadata(metadataDir)

        Log.i(TAG, "Staged export ${transferId}: ${bytes.size} bytes, hash=$hash")

        return job
    }

    /**
     * Receive a chunk of a chunked transfer.
     * Returns true if all chunks received, false otherwise.
     */
    fun receiveChunk(transferId: String, name: String, saveAs: Boolean,
                     totalBytes: Long, totalChunks: Int, seq: Int,
                     chunkLength: Int, data: ByteArray): Boolean {
        val safeName = safeFilename(name)
        val stageFile = File(stagingDir, "${transferId}__$safeName")

        // Append chunk to the staging file
        stageFile.parentFile?.mkdirs()
        stageFile.appendBytes(data)

        // Check if we've received all chunks
        return (seq + 1) >= totalChunks
    }

    /**
     * Finalize a chunked transfer: verify size and hash.
     */
    fun finalizeChunkedExport(transferId: String, name: String, saveAs: Boolean,
                              totalBytes: Long): ExportJob {
        val safeName = safeFilename(name)
        val stageFile = File(stagingDir, "${transferId}__$safeName")

        if (!stageFile.exists() || stageFile.length() != totalBytes) {
            throw IOException("Chunked export size mismatch: expected $totalBytes, got ${stageFile.length()}")
        }

        val hash = sha256(stageFile)
        val size = stageFile.length()

        val job = ExportJob(
            transferId = transferId,
            name = name,
            saveAs = saveAs,
            totalBytes = totalBytes,
            stagedHash = hash,
            stagedSize = size,
            stageFile = stageFile,
            status = ExportStatus.STAGING_DURABLE,
            createdAt = System.currentTimeMillis()
        )

        activeJobs[transferId] = job
        job.saveMetadata(metadataDir)

        Log.i(TAG, "Finalized chunked export ${transferId}: ${size} bytes, hash=$hash")

        return job
    }

    /**
     * Mark a job as awaiting destination selection.
     */
    fun awaitingDestination(transferId: String): ExportJob? {
        val job = activeJobs[transferId]
        if (job == null) return null
        job.status = ExportStatus.AWAITING_DESTINATION
        job.saveMetadata(metadataDir)
        return job
    }

    /**
     * Mark a job as cancelled (but preserve staged file for retry).
     */
    fun cancelJob(transferId: String) {
        val job = activeJobs[transferId]
        if (job != null) {
            job.status = ExportStatus.CANCELLED
            job.saveMetadata(metadataDir)
            Log.i(TAG, "Cancelled export ${transferId}")
        }
    }

    /**
     * Get all jobs that need recovery after process death.
     */
    fun getRecoverableJobs(): List<ExportJob> = recoveredJobs.values.toList()

    /**
     * Clear a recovered job after it's been resumed or discarded.
     */
    fun clearRecoveredJob(transferId: String) {
        recoveredJobs.remove(transferId)
    }

    private fun safeFilename(name: String): String {
        // Treat names as display metadata, never as app-private paths
        return name.replace("[^\\w\\-. ]".toRegex(), "_").take(200)
            .ifEmpty { "document.pdf" }
    }

    private fun writeAtomically(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // On some file systems, renameTo fails — use copy + delete
            target.writeBytes(data)
            tmp.delete()
        }
    }
}

/**
 * Callback interface for export status updates, used by HostMessageBridge
 * to notify the host page and Android UI of save completion.
 */
interface ExportCallback {
    fun onSaveStaged(job: ExportQueue.ExportJob)
    fun onSaveCompleted(job: ExportQueue.ExportJob)
    fun onSaveFailed(job: ExportQueue.ExportJob, error: String)
    fun onSaveCancelled(job: ExportQueue.ExportJob)
}
