package com.domgeller.pdfcraft

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ExportQueue — manages the lifecycle of document export jobs.
 *
 * Implements the state machine from plan §7:
 *   received → transferring → staging_durable → awaiting_destination
 *            → writing_destination → destination_verified → completed
 *
 * Failure/cancellation preserves staged bytes and a visible retry action.
 * Pending jobs and active destination-picker state survive Activity recreation
 * and process death. Orphan stage files (created if the process crashed after
 * syncing the PDF but before committing .job metadata) are automatically
 * reconciled during recovery.
 */
class ExportQueue(
    private val stagingDir: File,
    private val metadataDir: File
) {
    companion object {
        const val TAG = "ExportQueue"
        internal const val MAX_STAGING_BYTES = 256L * 1024 * 1024 // 256 MiB phone-safe staging bound
        internal const val MAX_CONCURRENT_TRANSFERS = 4
        internal const val TRANSFER_TIMEOUT_MS = 60_000L
        private const val HASH_BUFFER_SIZE = 8192
        private const val PICKER_STATE_FILE = "picker_state.prop"

        internal fun validateStagingSize(byteCount: Long) {
            if (byteCount < 0L) {
                throw IOException("Invalid negative export size: $byteCount")
            }
            if (byteCount > MAX_STAGING_BYTES) {
                throw IOException("Export exceeds maximum staging size of $MAX_STAGING_BYTES bytes")
            }
        }

        /**
         * Recover pending exports from a previous process lifecycle, including
         * orphan stage files whose .job metadata write was interrupted by a crash.
         */
        fun recoverPendingExports(stagingDir: File, metadataDir: File): Map<String, ExportJob> {
            val recovered = LinkedHashMap<String, ExportJob>()
            stagingDir.mkdirs()
            metadataDir.mkdirs()

            // Remove incomplete temporary or partial files
            stagingDir.listFiles { f -> f.extension == "part" || f.extension == "tmp" }?.forEach {
                runCatching { it.delete() }
            }
            metadataDir.listFiles { f -> f.extension == "tmp" }?.forEach {
                runCatching { it.delete() }
            }

            val claimedStagePaths = mutableSetOf<String>()
            val jobFiles = metadataDir.listFiles { f -> f.extension == "job" }.orEmpty()

            for (jobFile in jobFiles) {
                try {
                    val job = ExportJob.fromFile(jobFile)
                    if (job == null) {
                        // Corrupt or truncated .job file: delete the broken metadata file,
                        // but leave any underlying stage file for orphan reconciliation below!
                        Log.w(TAG, "Corrupt job metadata ${jobFile.name}; deferring to orphan check")
                        runCatching { jobFile.delete() }
                        continue
                    }
                    if (job.status == ExportStatus.COMPLETED) {
                        runCatching { job.stageFile.delete() }
                        runCatching { jobFile.delete() }
                        continue
                    }
                    if (job.stageFile.exists() && job.stageFile.length() > 0L &&
                        job.stageFile.length() == job.stagedSize
                    ) {
                        val actualHash = sha256(job.stageFile)
                        if (actualHash.equals(job.stagedHash, ignoreCase = true)) {
                            if (job.status != ExportStatus.AWAITING_DESTINATION) {
                                job.status = ExportStatus.PENDING_RECOVERY
                            }
                            recovered[job.transferId] = job
                            claimedStagePaths.add(job.stageFile.canonicalPath)
                            Log.i(TAG, "Recovered pending export: ${job.transferId} (${job.name})")
                        } else {
                            Log.w(TAG, "Staged file hash mismatch for ${job.transferId}, discarding")
                            runCatching { job.stageFile.delete() }
                            runCatching { jobFile.delete() }
                        }
                    } else {
                        Log.w(TAG, "Staged file missing or size mismatch for ${job.transferId}, discarding")
                        runCatching { job.stageFile.delete() }
                        runCatching { jobFile.delete() }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error recovering job ${jobFile.name}: ${e.message}")
                }
            }

            // Orphan stage-file reconciliation: if the process died after atomically syncing
            // a stage file to stagingDir but before its .job metadata committed, reconstruct it.
            val stageFiles = stagingDir.listFiles { f ->
                f.isFile && f.extension != "part" && f.extension != "tmp" && f.length() > 0L
            }.orEmpty()

            for (stageFile in stageFiles) {
                val canonical = runCatching { stageFile.canonicalPath }.getOrDefault(stageFile.absolutePath)
                if (claimedStagePaths.contains(canonical)) continue

                try {
                    val fileName = stageFile.name
                    val sepIdx = fileName.indexOf("__")
                    val transferId = if (sepIdx > 0) fileName.substring(0, sepIdx) else "orphan_${stageFile.lastModified()}"
                    val displayName = if (sepIdx > 0 && sepIdx + 2 < fileName.length) {
                        fileName.substring(sepIdx + 2)
                    } else {
                        fileName
                    }
                    if (recovered.containsKey(transferId)) continue

                    val hash = sha256(stageFile)
                    val size = stageFile.length()
                    val orphanJob = ExportJob(
                        transferId = transferId,
                        name = displayName,
                        saveAs = true,
                        totalBytes = size,
                        stagedHash = hash,
                        stagedSize = size,
                        stageFile = stageFile,
                        status = ExportStatus.PENDING_RECOVERY,
                        createdAt = stageFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                        mimeType = LocalContentServer.resolveExportMimeType(displayName, null)
                    )
                    orphanJob.saveMetadata(metadataDir)
                    recovered[transferId] = orphanJob
                    Log.i(TAG, "Reconciled orphan staged export: $transferId ($displayName, $size bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reconcile orphan stage file ${stageFile.name}: ${e.message}")
                }
            }

            return recovered
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(HASH_BUFFER_SIZE)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    enum class ExportStatus {
        RECEIVED,
        TRANSFERRING,
        STAGING_DURABLE,
        AWAITING_DESTINATION,
        WRITING_DESTINATION,
        DESTINATION_VERIFIED,
        COMPLETED,
        PENDING_RECOVERY,
        FAILED,
        CANCELLED
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
        val createdAt: Long = System.currentTimeMillis(),
        val mimeType: String = LocalContentServer.resolveExportMimeType(name, null)
    ) {
        fun saveMetadata(metadataDir: File) {
            metadataDir.mkdirs()
            val safeId = transferId.replace("[^\\w\\-.]".toRegex(), "_").ifEmpty { "job" }
            val meta = metadataDir.resolve("${safeId}.job")
            val content = buildString {
                append("transferId=").append(transferId).append('\n')
                append("name=").append(name.replace("\n", " ").replace("\r", " ")).append('\n')
                append("saveAs=").append(saveAs).append('\n')
                append("mimeType=").append(mimeType).append('\n')
                append("totalBytes=").append(totalBytes).append('\n')
                append("stagedHash=").append(stagedHash).append('\n')
                append("stagedSize=").append(stagedSize).append('\n')
                append("stageFile=").append(stageFile.absolutePath).append('\n')
                append("status=").append(status.name).append('\n')
                append("createdAt=").append(createdAt)
            }
            DocumentIo.writeAtomically(meta, content.toByteArray(Charsets.UTF_8))
        }

        companion object {
            fun fromFile(file: File): ExportJob? {
                if (!file.exists() || file.length() == 0L) return null
                val map = file.readLines()
                    .mapNotNull { line ->
                        val eq = line.indexOf('=')
                        if (eq > 0) line.substring(0, eq) to line.substring(eq + 1) else null
                    }
                    .toMap()

                val transferId = map["transferId"]?.takeIf { it.isNotBlank() } ?: return null
                val name = map["name"]?.takeIf { it.isNotBlank() } ?: "document.pdf"
                val stagePath = map["stageFile"]?.takeIf { it.isNotBlank() } ?: return null
                val stagedHash = map["stagedHash"]?.takeIf { it.length == 64 } ?: return null
                val totalBytes = map["totalBytes"]?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
                val stagedSize = map["stagedSize"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
                val createdAt = map["createdAt"]?.toLongOrNull() ?: return null
                val status = runCatching {
                    ExportStatus.valueOf(map["status"] ?: return null)
                }.getOrNull() ?: return null
                val reportedMime = map["mimeType"]

                return ExportJob(
                    transferId = transferId,
                    name = name,
                    saveAs = map["saveAs"] == "true",
                    totalBytes = totalBytes,
                    stagedHash = stagedHash,
                    stagedSize = stagedSize,
                    stageFile = File(stagePath),
                    status = status,
                    createdAt = createdAt,
                    mimeType = LocalContentServer.resolveExportMimeType(name, reportedMime)
                )
            }
        }
    }

    private data class InProgressChunkTransfer(
        val transferId: String,
        val name: String,
        val saveAs: Boolean,
        val mimeType: String,
        val totalBytes: Long,
        val totalChunks: Int,
        val partFile: File,
        val startedAt: Long = System.currentTimeMillis(),
        var lastUpdatedAt: Long = System.currentTimeMillis(),
        var expectedSeq: Int = 0,
        var receivedBytes: Long = 0L
    )

    private val activeJobs = ConcurrentHashMap<String, ExportJob>()
    private val chunkTransfers = ConcurrentHashMap<String, InProgressChunkTransfer>()
    private val recoveredJobs: MutableMap<String, ExportJob> =
        ConcurrentHashMap(recoverPendingExports(stagingDir, metadataDir))

    // Durable destination picker state so Activity recreation or process death while
    // ACTION_CREATE_DOCUMENT is open never loses the association between the picker
    // result and its ExportJob.
    private var activePickerJobId: String? = null
    private val pickerQueueIds = ArrayDeque<String>()
    @Volatile
    private var pickerStatePersistFailed: Boolean = false
    @Volatile
    private var lastPickerStatePersistError: String? = null

    init {
        restorePickerState()
    }

    fun stageExport(
        transferId: String,
        name: String,
        saveAs: Boolean,
        bytes: ByteArray,
        mimeType: String? = null,
        expectedSha256: String? = null
    ): ExportJob {
        val safeId = safeTransferId(transferId)
        val safeName = safeFilename(name)
        val stageFile = File(stagingDir, "${safeId}__$safeName")

        validateStagingSize(bytes.size.toLong())
        DocumentIo.writeAtomically(stageFile, bytes)

        val hash = sha256(stageFile)
        val size = stageFile.length()
        if (!expectedSha256.isNullOrBlank() && !hash.equals(expectedSha256, ignoreCase = true)) {
            runCatching { stageFile.delete() }
            throw IOException("Sender SHA-256 mismatch for $transferId: expected $expectedSha256, got $hash")
        }

        val resolvedMime = LocalContentServer.resolveExportMimeType(name, mimeType)

        val job = ExportJob(
            transferId = transferId,
            name = name,
            saveAs = saveAs,
            totalBytes = bytes.size.toLong(),
            stagedHash = hash,
            stagedSize = size,
            stageFile = stageFile,
            status = ExportStatus.STAGING_DURABLE,
            createdAt = System.currentTimeMillis(),
            mimeType = resolvedMime
        )

        activeJobs[transferId] = job
        job.saveMetadata(metadataDir)

        Log.i(TAG, "Staged export $transferId: ${bytes.size} bytes, mime=$resolvedMime, hash=$hash")
        return job
    }

    @Synchronized
    private fun evictTimedOutChunkTransfers(now: Long = System.currentTimeMillis()) {
        val iter = chunkTransfers.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value.lastUpdatedAt > TRANSFER_TIMEOUT_MS) {
                Log.w(TAG, "Evicting timed-out chunk transfer: ${entry.key}")
                runCatching { entry.value.partFile.delete() }
                iter.remove()
            }
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun receiveChunk(
        transferId: String,
        name: String,
        saveAs: Boolean,
        totalBytes: Long,
        totalChunks: Int,
        seq: Int,
        chunkLength: Int,
        data: ByteArray,
        mimeType: String? = null
    ): Boolean {
        val now = System.currentTimeMillis()
        evictTimedOutChunkTransfers(now)

        validateStagingSize(totalBytes)
        if (totalBytes <= 0L) {
            throw IOException("Invalid non-positive totalBytes ($totalBytes) for $transferId")
        }
        if (totalChunks <= 0 || seq < 0 || seq >= totalChunks) {
            throw IOException("Invalid chunk coordinates for $transferId: seq=$seq, totalChunks=$totalChunks")
        }
        if (data.isEmpty() || chunkLength != data.size) {
            throw IOException("Chunk length mismatch for $transferId: declared=$chunkLength, actual=${data.size}")
        }

        val safeId = safeTransferId(transferId)
        val safeName = safeFilename(name)
        stagingDir.mkdirs()
        val partFile = File(stagingDir, "${safeId}__${safeName}.part")
        val resolvedMime = LocalContentServer.resolveExportMimeType(name, mimeType)

        var state = chunkTransfers[transferId]
        if (state == null) {
            if (seq != 0) {
                throw IOException("Out-of-order chunk start for $transferId: expected seq=0, got seq=$seq")
            }
            if (chunkTransfers.size >= MAX_CONCURRENT_TRANSFERS) {
                throw IOException("Maximum concurrent chunked transfers ($MAX_CONCURRENT_TRANSFERS) exceeded")
            }
            if (partFile.exists()) partFile.delete()
            state = InProgressChunkTransfer(
                transferId = transferId,
                name = name,
                saveAs = saveAs,
                mimeType = resolvedMime,
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                partFile = partFile,
                startedAt = now,
                lastUpdatedAt = now
            )
            chunkTransfers[transferId] = state
        } else {
            // Reject duplicate seq=0 or inconsistent transfer metadata mid-stream
            if (seq != state.expectedSeq) {
                chunkTransfers.remove(transferId)
                runCatching { partFile.delete() }
                throw IOException("Out-of-order or duplicate chunk for $transferId: expected seq=${state.expectedSeq}, got seq=$seq")
            }
            if (state.name != name || state.saveAs != saveAs ||
                state.totalBytes != totalBytes || state.totalChunks != totalChunks
            ) {
                chunkTransfers.remove(transferId)
                runCatching { partFile.delete() }
                throw IOException("Inconsistent transfer metadata across chunks for $transferId")
            }
        }

        val newReceived = state.receivedBytes + data.size
        if (newReceived > state.totalBytes || (seq + 1 == totalChunks && newReceived != state.totalBytes)) {
            chunkTransfers.remove(transferId)
            runCatching { partFile.delete() }
            throw IOException(
                "Chunked export byte count mismatch for $transferId at seq=$seq: accumulated=$newReceived, declared=${state.totalBytes}"
            )
        }

        try {
            FileOutputStream(partFile, true).use { out ->
                out.write(data)
                if (seq + 1 == totalChunks) {
                    out.flush()
                    DocumentIo.syncDescriptor(out.fd)
                }
            }
        } catch (e: IOException) {
            chunkTransfers.remove(transferId)
            runCatching { partFile.delete() }
            throw e
        }

        state.expectedSeq = seq + 1
        state.receivedBytes = newReceived
        state.lastUpdatedAt = now
        return state.expectedSeq == totalChunks
    }

    @Synchronized
    @Throws(IOException::class)
    fun finalizeChunkedExport(
        transferId: String,
        name: String,
        saveAs: Boolean,
        totalBytes: Long,
        mimeType: String? = null,
        expectedSha256: String? = null
    ): ExportJob {
        val state = chunkTransfers.remove(transferId)
            ?: throw IOException("No active chunked transfer found for $transferId")

        if (state.expectedSeq != state.totalChunks || state.receivedBytes != totalBytes) {
            runCatching { state.partFile.delete() }
            throw IOException(
                "Incomplete chunked transfer for $transferId: seq=${state.expectedSeq}/${state.totalChunks}, bytes=${state.receivedBytes}/$totalBytes"
            )
        }
        if (state.name != name || state.saveAs != saveAs || state.totalBytes != totalBytes) {
            runCatching { state.partFile.delete() }
            throw IOException("Finalization metadata mismatch for $transferId")
        }

        val safeId = safeTransferId(transferId)
        val safeName = safeFilename(name)
        val partFile = state.partFile
        val stageFile = File(stagingDir, "${safeId}__$safeName")

        if (!partFile.exists() || partFile.length() != totalBytes) {
            runCatching { partFile.delete() }
            throw IOException("Chunked export staging file size mismatch: expected $totalBytes, got ${partFile.length()}")
        }

        DocumentIo.moveAtomically(partFile, stageFile)

        val hash = sha256(stageFile)
        val size = stageFile.length()
        if (!expectedSha256.isNullOrBlank() && !hash.equals(expectedSha256, ignoreCase = true)) {
            runCatching { stageFile.delete() }
            throw IOException("Chunked export SHA-256 mismatch for $transferId: expected $expectedSha256, got $hash")
        }

        val resolvedMime = LocalContentServer.resolveExportMimeType(name, mimeType ?: state.mimeType)

        val job = ExportJob(
            transferId = transferId,
            name = name,
            saveAs = saveAs,
            totalBytes = totalBytes,
            stagedHash = hash,
            stagedSize = size,
            stageFile = stageFile,
            status = ExportStatus.STAGING_DURABLE,
            createdAt = System.currentTimeMillis(),
            mimeType = resolvedMime
        )

        activeJobs[transferId] = job
        job.saveMetadata(metadataDir)

        Log.i(TAG, "Finalized chunked export $transferId: $size bytes, mime=$resolvedMime, hash=$hash")
        return job
    }

    @Synchronized
    fun hasInFlightTransfers(): Boolean {
        evictTimedOutChunkTransfers()
        return chunkTransfers.isNotEmpty()
    }

    fun getJob(transferId: String): ExportJob? =
        activeJobs[transferId] ?: recoveredJobs[transferId]

    fun getAllPendingJobs(): Map<String, ExportJob> {
        val combined = LinkedHashMap<String, ExportJob>()
        combined.putAll(recoveredJobs)
        combined.putAll(activeJobs)
        return combined
    }

    fun awaitingDestination(transferId: String): ExportJob? {
        val job = getJob(transferId) ?: return null
        job.status = ExportStatus.AWAITING_DESTINATION
        job.saveMetadata(metadataDir)
        return job
    }

    /**
     * Acquire the destination-picker slot for [job] if idle, or enqueue it if another
     * picker is currently active. Persists the picker state to disk so Activity recreation
     * can always resolve the active job when the picker ActivityResult returns.
     *
     * @return true if [job] is now the active picker job and should launch ACTION_CREATE_DOCUMENT immediately.
     */
    @Synchronized
    fun acquireOrEnqueuePicker(job: ExportJob): Boolean {
        activeJobs[job.transferId] = job
        awaitingDestination(job.transferId)

        val currentId = activePickerJobId
        if (currentId == null || getJob(currentId) == null) {
            activePickerJobId = job.transferId
            savePickerState()
            return true
        }
        if (currentId != job.transferId && !pickerQueueIds.contains(job.transferId)) {
            pickerQueueIds.addLast(job.transferId)
            savePickerState()
        }
        return false
    }

    @Synchronized
    fun getActivePickerJob(): ExportJob? {
        val id = activePickerJobId ?: return null
        return getJob(id)
    }

    @Synchronized
    fun consumeCurrentAndAdvancePicker(): Pair<ExportJob?, ExportJob?> {
        val finishedJob = activePickerJobId?.let { getJob(it) }
        activePickerJobId = null
        while (pickerQueueIds.isNotEmpty()) {
            val nextId = pickerQueueIds.removeFirst()
            val nextJob = getJob(nextId)
            if (nextJob != null) {
                activePickerJobId = nextJob.transferId
                savePickerState()
                return finishedJob to nextJob
            }
        }
        savePickerState()
        return finishedJob to null
    }

    @Synchronized
    fun restorePickerSelection(activeId: String?, queuedIds: List<String>) {
        if (!activeId.isNullOrBlank() && getJob(activeId) != null) {
            activePickerJobId = activeId
        }
        for (id in queuedIds) {
            if (id != activePickerJobId && !pickerQueueIds.contains(id) && getJob(id) != null) {
                pickerQueueIds.addLast(id)
            }
        }
        savePickerState()
    }

    @Synchronized
    fun getQueuedPickerIds(): List<String> = pickerQueueIds.toList()

    fun didPickerStatePersistFail(): Boolean = pickerStatePersistFailed

    fun getLastPickerStatePersistError(): String? = lastPickerStatePersistError

    @Synchronized
    private fun savePickerState() {
        val file = File(metadataDir, PICKER_STATE_FILE)
        val content = buildString {
            append("active=").append(activePickerJobId.orEmpty()).append('\n')
            append("queue=").append(pickerQueueIds.joinToString(","))
        }
        try {
            metadataDir.mkdirs()
            DocumentIo.writeAtomically(file, content.toByteArray(Charsets.UTF_8))
            pickerStatePersistFailed = false
            lastPickerStatePersistError = null
        } catch (e: Exception) {
            pickerStatePersistFailed = true
            lastPickerStatePersistError = e.message ?: "Unable to persist picker state"
            Log.e(TAG, "Failed to persist picker state to ${file.absolutePath}: ${e.message}", e)
        }
    }

    @Synchronized
    private fun restorePickerState() {
        val file = File(metadataDir, PICKER_STATE_FILE)
        if (file.exists()) {
            try {
                val map = file.readLines()
                    .mapNotNull { line ->
                        val eqIdx = line.indexOf('=')
                        if (eqIdx > 0) line.substring(0, eqIdx) to line.substring(eqIdx + 1) else null
                    }
                    .toMap()
                val active = map["active"]?.takeIf { it.isNotBlank() && getJob(it) != null }
                val queued = map["queue"]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() && getJob(it) != null }
                    .orEmpty()
                activePickerJobId = active
                pickerQueueIds.clear()
                for (q in queued) {
                    if (q != active && !pickerQueueIds.contains(q)) {
                        pickerQueueIds.addLast(q)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read $PICKER_STATE_FILE: ${e.message}", e)
            }
        }
        if (activePickerJobId == null) {
            // Prefer the saved queue order; otherwise recover deterministically.
            activePickerJobId = pickerQueueIds.firstOrNull { getJob(it) != null }
                ?: recoveredJobs.values
                    .filter { it.status == ExportStatus.AWAITING_DESTINATION }
                    .sortedWith(compareBy<ExportJob> { it.createdAt }.thenBy { it.transferId })
                    .firstOrNull()?.transferId
        }
        activePickerJobId?.let { pickerQueueIds.remove(it) }
    }

    private fun persistStatus(job: ExportJob) {
        try { job.saveMetadata(metadataDir) } catch (e: Exception) {
            pickerStatePersistFailed = true
            lastPickerStatePersistError = e.message ?: "Could not persist export status"
            Log.e(TAG, "Export status remains in memory; staged PDF retained", e)
        }
    }

    fun cancelJob(transferId: String) {
        val job = getJob(transferId)
        if (job != null) {
            job.status = ExportStatus.CANCELLED
            persistStatus(job)
            Log.i(TAG, "Cancelled export $transferId")
        }
    }

    fun failJob(transferId: String) {
        val job = getJob(transferId)
        if (job != null) {
            job.status = ExportStatus.FAILED
            persistStatus(job)
            Log.w(TAG, "Marked export failed (preserved for retry): $transferId")
        }
    }

    @Synchronized
    fun completeAndRemoveJob(transferId: String) {
        val job = activeJobs.remove(transferId) ?: recoveredJobs.remove(transferId)
        recoveredJobs.remove(transferId)
        if (activePickerJobId == transferId) {
            activePickerJobId = null
        }
        pickerQueueIds.remove(transferId)
        savePickerState()

        val safeId = safeTransferId(transferId)
        runCatching { job?.stageFile?.delete() }
        runCatching { File(metadataDir, "${safeId}.job").delete() }
        runCatching { File(metadataDir, "${transferId}.job").delete() }
    }

    fun getRecoverableJobs(): List<ExportJob> = recoveredJobs.values.toList()
    fun getRecoverableJobsMap(): Map<String, ExportJob> = recoveredJobs.toMap()

    fun clearRecoveredJob(transferId: String) {
        recoveredJobs.remove(transferId)
    }

    private fun safeTransferId(id: String): String {
        return id.replace("[^\\w\\-.]".toRegex(), "_").take(120).ifEmpty { "transfer" }
    }

    private fun safeFilename(name: String): String {
        return name.replace("[^\\w\\-. ]".toRegex(), "_").take(200)
            .ifEmpty { "document.pdf" }
    }
}

interface ExportCallback {
    fun onSaveStaged(job: ExportQueue.ExportJob)
    fun onSaveCompleted(job: ExportQueue.ExportJob)
    fun onSaveFailed(job: ExportQueue.ExportJob, error: String)
    fun onSaveCancelled(job: ExportQueue.ExportJob)
}
