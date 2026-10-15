package com.domgeller.pdfcraft

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PickerFallbackReviewTest {
    @Test
    fun restoredFallbackMustNotRemainInWaitingQueue() {
        val root = File(System.getProperty("java.io.tmpdir"), "picker_fallback_review_" + System.nanoTime())
        val stages = File(root, "staging").apply { mkdirs() }
        val metadata = File(root, "metadata").apply { mkdirs() }
        try {
            val queue = ExportQueue(stages, metadata)
            val first = queue.stageExport("first", "first.pdf", true, byteArrayOf(1, 2, 3))
            val second = queue.stageExport("second", "second.pdf", true, byteArrayOf(4, 5, 6))
            assertTrue(queue.acquireOrEnqueuePicker(first))
            assertFalse(queue.acquireOrEnqueuePicker(second))
            // Model a missing/corrupt active staged PDF while the queued PDF remains valid.
            first.stageFile.delete()
            val recovered = ExportQueue(stages, metadata)
            assertEquals("second", recovered.getActivePickerJob()?.transferId)
            val (finished, next) = recovered.consumeCurrentAndAdvancePicker()
            assertEquals("second", finished?.transferId)
            assertNull("The same export must not be offered to the destination picker again", next)
        } finally {
            root.deleteRecursively()
        }
    }
}
