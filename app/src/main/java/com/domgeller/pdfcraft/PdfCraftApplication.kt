package com.domgeller.pdfcraft

import android.app.Application
import android.util.Log
import java.io.File

/**
 * Application class for PdfCraft Android.
 *
 * Initializes the export queue and recovers any pending exports from a previous process.
 * Does NOT hold an Activity-owned WebView in a long-lived object — the WebView is
 * created and destroyed with the Activity lifecycle.
 */
class PdfCraftApplication : Application() {

    companion object {
        const val TAG = "PdfCraftApp"
    }

    /**
     * App-private persistent staging directory for exported documents.
     * Not disposable cache: survives process death for pending-export recovery.
     */
    val stagingDir: File by lazy {
        File(filesDir, "staging").apply { mkdirs() }
    }

    /**
     * App-private metadata directory for tracking pending exports.
     */
    val metadataDir: File by lazy {
        File(filesDir, "metadata").apply { mkdirs() }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PdfCraft application created")

        // Recover any pending exports from a previous process lifecycle.
        ExportQueue.recoverPendingExports(stagingDir, metadataDir)
    }
}
