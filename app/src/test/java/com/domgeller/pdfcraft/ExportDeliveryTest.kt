package com.domgeller.pdfcraft

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = PdfCraftApplication::class)
class ExportDeliveryTest {
    @Test fun delayedStageCompletionUsesReplacementOwner() {
        val app = RuntimeEnvironment.getApplication() as PdfCraftApplication
        val oldOwner = Any(); val newOwner = Any()
        var oldCalls = 0; var newCalls = 0
        app.attachExportListener(oldOwner) { oldCalls++ }
        val job = app.exportQueue.stageExport("late", "late.pdf", true, byteArrayOf(1,2,3))
        app.notifyExportStaged(job)
        app.detachExportListener(oldOwner)
        app.attachExportListener(newOwner) { newCalls++ }
        // A stale teardown must not detach the new Activity's listener.
        app.detachExportListener(oldOwner)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, oldCalls)
        assertTrue(newCalls > 0)
        app.detachExportListener(newOwner)
    }

    @Test fun completionWithoutActivityIsReplayedOnAttach() {
        val app = RuntimeEnvironment.getApplication() as PdfCraftApplication
        val job = app.exportQueue.stageExport("paused", "paused.pdf", true, byteArrayOf(1,2,3))
        app.notifyExportStaged(job)
        shadowOf(Looper.getMainLooper()).idle()
        val received = mutableListOf<String>()
        val owner = Any()
        app.attachExportListener(owner) { received.add(it.transferId) }
        assertTrue(received.contains("paused"))
        app.detachExportListener(owner)
    }
    @Test fun activityDestroyedDuringStagingReplacementReceivesCompletedJob() {
        val app = RuntimeEnvironment.getApplication() as PdfCraftApplication
        val original = Robolectric.buildActivity(MainActivity::class.java).setup()
        // Completion arrives only after the original Activity has been destroyed.
        original.pause().stop().destroy()
        val job = app.exportQueue.stageExport("recreated", "recreated.pdf", true, byteArrayOf(1, 2, 3))
        app.notifyExportStaged(job)
        val replacement = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("recreated", app.exportQueue.getActivePickerJob()?.transferId)
        val (finished, next) = app.exportQueue.consumeCurrentAndAdvancePicker()
        assertEquals("recreated", finished?.transferId)
        assertNull("replayed completion must not queue the same picker twice", next)
        replacement.pause().stop().destroy()
    }

    @Test fun backRequiresAnExplicitChoiceWhenOnlyHostFailuresRemain() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        MainActivity::class.java.getDeclaredField("hostFailedExports").apply { isAccessible = true }.setInt(activity, 1)
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
        assertNotNull(dialog)
        assertEquals("Review export", dialog!!.getButton(android.content.DialogInterface.BUTTON_POSITIVE).text.toString())
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

}
