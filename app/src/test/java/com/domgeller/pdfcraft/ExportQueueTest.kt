package com.domgeller.pdfcraft

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Unit tests for ExportQueue — verifies single-shot staging, sender SHA-256 checks,
 * chunked streaming to disk, duplicate/missing/out-of-order/metadata/concurrency guards,
 * orphan stage-file reconciliation, durable destination-picker state across recreation,
 * MIME persistence, cancellation, and recovery logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ExportQueueTest {

    private lateinit var stagingDir: File
    private lateinit var metadataDir: File
    private lateinit var queue: ExportQueue

    @Before
    fun setUp() {
        stagingDir = File(System.getProperty("java.io.tmpdir"), "pdfcraft_staging_" + System.nanoTime())
        metadataDir = File(System.getProperty("java.io.tmpdir"), "pdfcraft_metadata_" + System.nanoTime())
        stagingDir.mkdirs()
        metadataDir.mkdirs()
        queue = ExportQueue(stagingDir, metadataDir)
    }

    @After
    fun tearDown() {
        stagingDir.deleteRecursively()
        metadataDir.deleteRecursively()
    }

    @Test
    fun stageExport_storesBytes_andVerifiesSenderHashAndMimeType() {
        val transferId = "test_transfer_1"
        val name = "test_output.pdf"
        val data = byteArrayOf(0x25, 0x50, 0x44, 0x46) // "%PDF"
        val expectedHash = DocumentIo.sha256(data)

        val job = queue.stageExport(transferId, name, false, data, "application/pdf", expectedHash)

        assertEquals(transferId, job.transferId)
        assertEquals(name, job.name)
        assertEquals("application/pdf", job.mimeType)
        assertEquals(data.size.toLong(), job.totalBytes)
        assertEquals(data.size.toLong(), job.stagedSize)
        assertTrue(job.stageFile.exists())
        assertEquals(expectedHash, job.stagedHash)
        assertEquals("STAGING_DURABLE", job.status.name)
    }

    @Test
    fun stageExport_rejectsSenderSha256Mismatch_andCleansUpStageFile() {
        val data = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        val wrongHash = "0".repeat(64)

        assertThrows(IOException::class.java) {
            queue.stageExport("bad_hash_tx", "bad.pdf", false, data, "application/pdf", wrongHash)
        }
        assertTrue(stagingDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun stageExport_preservesNonPdfExportMimeTypeAcrossRecovery() {
        val data = "x,y,value\n1,2,3\n".toByteArray(Charsets.UTF_8)
        val job = queue.stageExport("csv_export_1", "measurements.csv", true, data, "application/pdf")

        assertEquals("text/csv", job.mimeType)

        val recovered = ExportQueue.recoverPendingExports(stagingDir, metadataDir)
        assertEquals("text/csv", recovered["csv_export_1"]?.mimeType)
    }

    @Test
    fun validateStagingSize_rejectsOversizedLength_withoutAllocating() {
        val ex = assertThrows(IOException::class.java) {
            ExportQueue.validateStagingSize(ExportQueue.MAX_STAGING_BYTES + 1)
        }
        assertTrue(ex.message?.contains("maximum") == true)
        ExportQueue.validateStagingSize(ExportQueue.MAX_STAGING_BYTES)
    }

    @Test
    fun receiveChunk_andFinalizeChunkedExport_streamsAndVerifiesSenderHash() {
        val chunk0 = "Hello, ".toByteArray(Charsets.UTF_8)
        val chunk1 = "chunked ".toByteArray(Charsets.UTF_8)
        val chunk2 = "PdfCraft!".toByteArray(Charsets.UTF_8)
        val combined = chunk0 + chunk1 + chunk2
        val totalBytes = combined.size.toLong()
        val expectedSha256 = DocumentIo.sha256(combined)

        assertFalse(
            queue.receiveChunk("chunk_tx_1", "split_1.pdf", true, totalBytes, 3, 0, chunk0.size, chunk0)
        )
        assertTrue(queue.hasInFlightTransfers())
        assertFalse(
            queue.receiveChunk("chunk_tx_1", "split_1.pdf", true, totalBytes, 3, 1, chunk1.size, chunk1)
        )
        assertTrue(
            queue.receiveChunk("chunk_tx_1", "split_1.pdf", true, totalBytes, 3, 2, chunk2.size, chunk2)
        )

        val job = queue.finalizeChunkedExport(
            "chunk_tx_1",
            "split_1.pdf",
            true,
            totalBytes,
            "application/pdf",
            expectedSha256
        )
        assertFalse(queue.hasInFlightTransfers())
        assertEquals(totalBytes, job.stagedSize)
        assertArrayEquals(combined, job.stageFile.readBytes())
        assertEquals(expectedSha256, job.stagedHash)
    }

    @Test
    fun receiveChunk_rejectsDuplicateChunkZero() {
        val chunk0 = byteArrayOf(1, 2, 3, 4)
        queue.receiveChunk("dup_tx", "dup.pdf", false, 8L, 2, 0, chunk0.size, chunk0)

        // Delivering seq=0 a second time must be rejected and abort the transfer
        assertThrows(IOException::class.java) {
            queue.receiveChunk("dup_tx", "dup.pdf", false, 8L, 2, 0, chunk0.size, chunk0)
        }
        assertFalse(queue.hasInFlightTransfers())
    }

    @Test
    fun receiveChunk_rejectsOutOfOrderOrSkippedSequence() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        assertThrows(IOException::class.java) {
            queue.receiveChunk("bad_seq_tx", "bad.pdf", false, 8L, 2, 1, chunk.size, chunk)
        }
    }

    @Test
    fun receiveChunk_rejectsMetadataChangeMidTransfer() {
        val chunk0 = byteArrayOf(1, 2, 3, 4)
        val chunk1 = byteArrayOf(5, 6, 7, 8)
        queue.receiveChunk("meta_tx", "orig.pdf", false, 8L, 2, 0, chunk0.size, chunk0)

        assertThrows(IOException::class.java) {
            // Changing filename mid-transfer must fail
            queue.receiveChunk("meta_tx", "changed.pdf", false, 8L, 2, 1, chunk1.size, chunk1)
        }
    }

    @Test
    fun receiveChunk_enforcesMaxConcurrentTransfers() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        for (i in 1..ExportQueue.MAX_CONCURRENT_TRANSFERS) {
            queue.receiveChunk("tx_$i", "doc_$i.pdf", false, 8L, 2, 0, chunk.size, chunk)
        }
        assertThrows(IOException::class.java) {
            queue.receiveChunk("tx_overflow", "overflow.pdf", false, 8L, 2, 0, chunk.size, chunk)
        }
    }

    @Test
    fun recoverPendingExports_reconcilesOrphanStageFileWhenMetadataMissingOrTruncated() {
        // Simulate a crash after the PDF stage file was atomically written to stagingDir,
        // but before its .job metadata file was committed (or with a truncated .job file).
        val orphanBytes = "%PDF-1.7 orphan content".toByteArray(Charsets.UTF_8)
        val orphanStageFile = File(stagingDir, "t_orphan_1__recovered_doc.pdf")
        DocumentIo.writeAtomically(orphanStageFile, orphanBytes)

        // Also write a truncated/corrupt .job file to verify it doesn't block orphan recovery
        File(metadataDir, "t_orphan_1.job").writeText("transferId=t_orphan_1\ntruncated_line")

        val recovered = ExportQueue.recoverPendingExports(stagingDir, metadataDir)
        assertEquals(1, recovered.size)
        val job = recovered["t_orphan_1"]
        assertNotNull(job)
        assertEquals("recovered_doc.pdf", job?.name)
        assertEquals(DocumentIo.sha256(orphanBytes), job?.stagedHash)
        assertTrue(File(metadataDir, "t_orphan_1.job").exists())
    }

    @Test
    fun pickerState_persistsAcrossQueueReconstructionAndActivityRecreation() {
        val job1 = queue.stageExport("pick_1", "first.pdf", true, byteArrayOf(1, 2, 3))
        val job2 = queue.stageExport("pick_2", "second.pdf", true, byteArrayOf(4, 5, 6))

        assertTrue(queue.acquireOrEnqueuePicker(job1))
        assertFalse(queue.acquireOrEnqueuePicker(job2))
        assertEquals("pick_1", queue.getActivePickerJob()?.transferId)
        assertEquals(listOf("pick_2"), queue.getQueuedPickerIds())

        // Simulate Activity recreation / process restart by constructing a new ExportQueue
        val queueAfterRestart = ExportQueue(stagingDir, metadataDir)
        assertEquals("pick_1", queueAfterRestart.getActivePickerJob()?.transferId)
        assertEquals(listOf("pick_2"), queueAfterRestart.getQueuedPickerIds())

        val (finished, next) = queueAfterRestart.consumeCurrentAndAdvancePicker()
        assertEquals("pick_1", finished?.transferId)
        assertEquals("pick_2", next?.transferId)
        assertEquals("pick_2", queueAfterRestart.getActivePickerJob()?.transferId)
    }

    @Test
    fun cancelJob_preservesMetadata() {
        val job = queue.stageExport("cancel_test", "cancel.pdf", false, byteArrayOf(1, 2, 3))
        assertTrue(job.stageFile.exists())

        queue.cancelJob("cancel_test")

        assertTrue(job.stageFile.exists())
        val metaFile = File(metadataDir, "cancel_test.job")
        assertTrue(metaFile.exists())
        assertTrue(metaFile.readText().contains("CANCELLED"))
    }

    @Test
    fun recoverPendingExports_returnsJobsWithMatchingHash() {
        queue.stageExport("recover_1", "recover.pdf", false, byteArrayOf(10, 20, 30))
        val recovered = ExportQueue.recoverPendingExports(stagingDir, metadataDir)

        assertEquals(1, recovered.size)
        val recoveredJob = recovered["recover_1"]
        assertNotNull(recoveredJob)
        assertEquals("recover.pdf", recoveredJob?.name)
        assertEquals(ExportQueue.ExportStatus.PENDING_RECOVERY, recoveredJob?.status)
    }

    @Test
    fun recoverPendingExports_discardsJobsWithMissingStageFile() {
        val job = queue.stageExport("recover_2", "recover.pdf", false, byteArrayOf(1, 2, 3))
        job.stageFile.delete()

        val recovered = ExportQueue.recoverPendingExports(stagingDir, metadataDir)
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun recoverPendingExports_discardsJobsWithHashMismatch() {
        val job = queue.stageExport("recover_3", "recover.pdf", false, byteArrayOf(1, 2, 3))
        job.stageFile.writeBytes(byteArrayOf(1, 2))

        val recovered = ExportQueue.recoverPendingExports(stagingDir, metadataDir)
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun clearRecoveredJob_andCompleteAndRemoveJob_cleanUpFiles() {
        val job = queue.stageExport("clear_test", "clear.pdf", false, byteArrayOf(1, 2, 3))
        val queue2 = ExportQueue(stagingDir, metadataDir)
        assertEquals(1, queue2.getRecoverableJobs().size)

        queue2.completeAndRemoveJob("clear_test")
        assertEquals(0, queue2.getRecoverableJobs().size)
        assertFalse(job.stageFile.exists())
        assertFalse(File(metadataDir, "clear_test.job").exists())
    }
}
