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

/**
 * Unit tests for ExportQueue — verifies staging, recovery, cancellation,
 * and metadata persistence logic.
 *
 * Uses Robolectric to provide android.util.Log shadow on the JVM.
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
    fun stageExport_storesBytes_andVerifiesHash() {
        val transferId = "test_transfer_1"
        val name = "test_output.pdf"
        val data = byteArrayOf(0x25, 0x50, 0x44, 0x46) // "%PDF"

        val job = queue.stageExport(transferId, name, false, data)

        assertEquals(transferId, job.transferId)
        assertEquals(name, job.name)
        assertEquals(data.size.toLong(), job.totalBytes)
        assertEquals(data.size.toLong(), job.stagedSize)
        assertTrue(job.stageFile.exists())
        assertTrue(job.stagedHash.isNotEmpty())
        assertEquals("STAGING_DURABLE", job.status.name)

        // Verify the stored hash matches
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(data).joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, job.stagedHash)
    }

    @Test
    fun validateStagingSize_rejectsOversizedLength_withoutAllocating() {
        // Must reject a length one byte over the limit without allocating a
        // multi-gigabyte array (the previous test overflowed Int and threw
        // NegativeArraySizeException before reaching the assertion).
        val ex = assertThrows(IOException::class.java) {
            ExportQueue.validateStagingSize(ExportQueue.MAX_STAGING_BYTES + 1)
        }
        assertTrue(ex.message?.contains("maximum") == true)

        // Exactly at the limit is allowed.
        ExportQueue.validateStagingSize(ExportQueue.MAX_STAGING_BYTES)
    }

    @Test
    fun cancelJob_preservesMetadata() {
        val job = queue.stageExport("cancel_test", "cancel.pdf", false, byteArrayOf(1, 2, 3))
        assertTrue(job.stageFile.exists())

        queue.cancelJob("cancel_test")

        // Stage file should still exist (preserved for retry)
        assertTrue(job.stageFile.exists())
        // Metadata should still exist with CANCELLED status
        val metaFile = File(metadataDir, "cancel_test.job")
        assertTrue(metaFile.exists())
        assertTrue(metaFile.readText().contains("CANCELLED"))
    }

    @Test
    fun recoverPendingExports_returnsJobsWithMatchingHash() {
        // Stage a job
        val job = queue.stageExport("recover_1", "recover.pdf", false, byteArrayOf(10, 20, 30))

        // Simulate process death by creating a new queue
        val recovered = ExportQueue.recoverPendingExports(stagingDir, metadataDir)

        assertEquals(1, recovered.size)
        val recoveredJob = recovered["recover_1"]
        assertNotNull(recoveredJob)
        assertEquals("recover.pdf", recoveredJob?.name)
        assertEquals(ExportQueue.ExportStatus.PENDING_RECOVERY, recoveredJob?.status)
        assertTrue(recoveredJob?.stageFile?.exists() == true)
    }

    @Test
    fun recoverPendingExports_discardsJobsWithMissingStageFile() {
        // Stage a job
        val job = queue.stageExport("recover_2", "recover.pdf", false, byteArrayOf(1, 2, 3))

        // Delete the staged file (simulate it being lost)
        job.stageFile.delete()

        // Recover should skip this job
        val recovered = ExportQueue.recoverPendingExports(stagingDir, metadataDir)
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun recoverPendingExports_discardsJobsWithHashMismatch() {
        // Stage a job
        val job = queue.stageExport("recover_3", "recover.pdf", false, byteArrayOf(1, 2, 3))

        // Corrupt the staged file (simulate truncation)
        job.stageFile.writeBytes(byteArrayOf(1, 2))

        val recovered = ExportQueue.recoverPendingExports(stagingDir, metadataDir)
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun clearRecoveredJob_removesFromRecoveredSet() {
        queue.stageExport("clear_test", "clear.pdf", false, byteArrayOf(1, 2, 3))
        val recovered = ExportQueue.recoverPendingExports(stagingDir, metadataDir)
        assertEquals(1, recovered.size)

        // Create a new queue to test the recovered set
        val queue2 = ExportQueue(stagingDir, metadataDir)
        assertEquals(1, queue2.getRecoverableJobs().size)

        queue2.clearRecoveredJob("clear_test")
        assertEquals(0, queue2.getRecoverableJobs().size)
    }
}
