package com.domgeller.pdfcraft

import android.app.Application
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Application class for PdfCraft Android.
 *
 * Owns the single shared ExportQueue and the background disk I/O executor so that
 * Activity recreation (e.g., during a system file picker or configuration change)
 * never aborts an in-flight staging or destination-copy task.
 */
class PdfCraftApplication : Application() {

    companion object {
        const val TAG = "PdfCraftApp"
    }

    val stagingDir: File by lazy {
        File(filesDir, "staging").apply { mkdirs() }
    }

    val metadataDir: File by lazy {
        File(filesDir, "metadata").apply { mkdirs() }
    }

    val exportQueue: ExportQueue by lazy {
        ExportQueue(stagingDir, metadataDir)
    }

    /**
     * Application-scoped background executor for document I/O and export staging.
     */
    val ioExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PdfCraft-IO").apply { isDaemon = true }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PdfCraft application created")

        val recovered = exportQueue.getRecoverableJobs()
        if (recovered.isNotEmpty()) {
            Log.i(TAG, "Recovered ${recovered.size} pending exports")
        }
    }

    fun getRecoverableJobs(): Map<String, ExportQueue.ExportJob> =
        exportQueue.getRecoverableJobsMap()

    fun clearRecoveredJob(transferId: String) {
        exportQueue.clearRecoveredJob(transferId)
    }
}
