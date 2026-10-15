package com.domgeller.pdfcraft

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.button.MaterialButton

/**
 * MainActivity — hosts the PdfCraft web app inside an Android WebView.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "MainActivity"
        private const val STARTUP_READY_TIMEOUT_MS = 25_000L
        private const val STATE_LAST_PICKER = "last_picker_launched"
        private const val STATE_ACTIVE_PICKER_JOB_ID = "state_active_picker_job_id"
        private const val STATE_QUEUED_PICKER_JOB_IDS = "state_queued_picker_job_ids"
        private const val STATE_STARTUP_PROMPT_SHOWN = "state_startup_prompt_shown"
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var localContentServer: LocalContentServer
    private var chromeClient: PdfCraftChromeClient? = null
    private var messageBridge: HostMessageBridge? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pendingIntentImports = mutableListOf<PendingImport>()
    private var appReady = false
    private var appDirty = false
    private var errorSurfaceShowing = false
    private var startupRecoveryPromptShown = false

    private val startupTimeoutRunnable = Runnable {
        if (!appReady && !errorSurfaceShowing && !isFinishing && !isDestroyed) {
            Log.e(TAG, "Timed out waiting for pdfcraft:ready from WASM application")
            showErrorSurface(
                "PdfCraft took too long to initialize. Check that Android System WebView is up to date and retry.",
                canRetry = true
            )
        }
    }

    data class PendingImport(val name: String, val bytes: ByteArray)

    // ActivityResultLauncher for save destination picker.
    // host.js-side pending work (queued + active) and retained-but-unsent exports.
    @Volatile private var hostQueuedExports = 0
    @Volatile private var hostFailedExports = 0
    // Transfer id of the last job this Activity launched a picker for, so onResume can
    // pick up a job deferred by a finishing Activity without double-launching.
    private var lastPickerLaunchedFor: String? = null

    // Resolves the active ExportJob from the durable ExportQueue so Activity recreation
    // or process death while DocumentsUI is open never orphans the returned URI.
    private val saveDestinationLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            lastPickerLaunchedFor = null
            val app = application as PdfCraftApplication
            val (job, nextJob) = app.exportQueue.consumeCurrentAndAdvancePicker()
            checkPickerStatePersistence()
            if (job != null) {
                if (result.resultCode == RESULT_OK) {
                    val destUri = result.data?.data
                    if (destUri != null) {
                        handleSaveDestinationSelected(destUri, job)
                    } else {
                        handleSaveCancelled(job)
                    }
                } else {
                    handleSaveCancelled(job)
                }
            } else {
                Log.w(TAG, "Destination picker returned with no active ExportJob in queue")
            }

            if (nextJob != null) {
                showSaveDestinationPicker(nextJob)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as PdfCraftApplication
        if (savedInstanceState != null) {
            lastPickerLaunchedFor = savedInstanceState.getString(STATE_LAST_PICKER)
            startupRecoveryPromptShown = savedInstanceState.getBoolean(STATE_STARTUP_PROMPT_SHOWN, false)
            val savedActiveId = savedInstanceState.getString(STATE_ACTIVE_PICKER_JOB_ID)
            val savedQueuedIds = savedInstanceState.getStringArrayList(STATE_QUEUED_PICKER_JOB_IDS).orEmpty()
            app.exportQueue.restorePickerSelection(savedActiveId, savedQueuedIds)
            checkPickerStatePersistence()
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        webView = WebView(this)
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        setContentView(webView, layoutParams)
        setupWindowInsetsListener()

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.displayZoomControls = false
        settings.builtInZoomControls = false
        settings.textZoom = 100

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        localContentServer = LocalContentServer(this)
        assetLoader = localContentServer.createAssetLoader()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = localContentServer.interceptRequest(assetLoader, request.url)

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    mainHandler.removeCallbacks(startupTimeoutRunnable)
                    showErrorSurface("Unable to load bundled PdfCraft: ${error.description}", true)
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    mainHandler.removeCallbacks(startupTimeoutRunnable)
                    showErrorSurface("Bundled PdfCraft resource failed (${response.statusCode}).", true)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                return !url.toString().startsWith(LocalContentServer.ASSET_ORIGIN)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.e(
                    TAG,
                    "WebView renderer exited (didCrash=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()})"
                )
                mainHandler.removeCallbacks(startupTimeoutRunnable)
                appReady = false
                runCatching {
                    (view.parent as? FrameLayout)?.removeView(view)
                    view.destroy()
                }
                showErrorSurface(getString(R.string.error_webview), canRetry = true)
                return true
            }
        }

        if (!localContentServer.hasWebBuild()) {
            Log.e(TAG, "Web build not found in APK assets at assets/pdfcraft/")
            showErrorSurface("PdfCraft web build not found in APK. Build may need to be rebuilt.", true)
            return
        }

        chromeClient = PdfCraftChromeClient(this) { _, _ -> }
        webView.webChromeClient = chromeClient

        messageBridge = HostMessageBridge(
            webView = webView,
            assetOrigin = LocalContentServer.ASSET_ORIGIN,
            bridgeObjectName = "WebMessageListener",
            stagingDir = app.stagingDir,
            metadataDir = app.metadataDir,
            exportQueue = app.exportQueue,
            executor = app.ioExecutor,
            callback = object : HostMessageBridge.BridgeCallback {
                override fun onSaveStaged(transferId: String, job: ExportQueue.ExportJob) {
                    app.notifyExportStaged(job)
                }

                override fun onSaveCompleted(transferId: String, success: Boolean, error: String?) {
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                        } else if (!isFinishing && !isDestroyed) {
                            val existingJob = app.exportQueue.getJob(transferId)
                            if (existingJob != null) {
                                showExportActionDialog(
                                    existingJob,
                                    title = "Export error",
                                    message = "Export ($transferId) encountered an error: ${error ?: "unknown error"}."
                                )
                            } else {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Export failed")
                                    .setMessage("Could not stage export ($transferId): ${error ?: "unknown error"}. Please try saving again.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                }

                override fun onSaveFailed(transferId: String, name: String, reason: String, error: String?, retained: Boolean) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (!retained) {
                            AlertDialog.Builder(this@MainActivity).setTitle("Export needs attention")
                                .setMessage("$name: ${error ?: reason}").setPositiveButton("Keep editing", null)
                                .setNegativeButton("Discard failed export") { _, _ -> messageBridge?.sendDiscardExport(transferId) }
                                .show()
                            return@runOnUiThread
                        }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Export needs attention")
                            .setMessage(
                                "\"$name\" could not be handed to the app ($reason).\n\n" +
                                    (error ?: "") +
                                    "\n\nThe payload is retained in the editor so you can retry or discard it."
                            )
                            .setPositiveButton("Retry") { _, _ ->
                                messageBridge?.sendRetryExport(transferId)
                            }
                            .setNegativeButton("Discard") { _, _ ->
                                messageBridge?.sendDiscardExport(transferId)
                            }
                            .setNeutralButton("Later", null)
                            .show()
                    }
                }

                override fun onPendingWork(queued: Int, active: Int, failed: Int, queuedBytes: Long) {
                    hostQueuedExports = queued + active
                    hostFailedExports = failed
                }

                override fun onImportRequested() {}

                override fun onAppReady(version: String) {
                    runOnUiThread {
                        mainHandler.removeCallbacks(startupTimeoutRunnable)
                        appReady = true
                        Log.i(TAG, "PdfCraft app ready, version: $version")
                        if (pendingIntentImports.isNotEmpty()) {
                            val imports = pendingIntentImports.toList()
                            pendingIntentImports.clear()
                            for (import in imports) {
                                sendImportToIframe(import.name, import.bytes)
                            }
                        }
                        resumeDeferredPickerIfNeeded()
                        maybePromptRecoveredExportsAtStartup()
                    }
                }

                override fun onDirtyChanged(dirty: Boolean) {
                    runOnUiThread {
                        appDirty = dirty
                    }
                }

                override fun onAppFailed(error: String) {
                    runOnUiThread {
                        mainHandler.removeCallbacks(startupTimeoutRunnable)
                        showErrorSurface("PdfCraft failed: $error", true)
                    }
                }
            }
        )
        messageBridge?.register()

        setupBackHandling()

        mainHandler.postDelayed(startupTimeoutRunnable, STARTUP_READY_TIMEOUT_MS)
        webView.loadUrl(LocalContentServer.HOST_URL)

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_LAST_PICKER, lastPickerLaunchedFor)
        super.onSaveInstanceState(outState)
        val app = application as PdfCraftApplication
        outState.putBoolean(STATE_STARTUP_PROMPT_SHOWN, startupRecoveryPromptShown)
        outState.putString(STATE_ACTIVE_PICKER_JOB_ID, app.exportQueue.getActivePickerJob()?.transferId)
        outState.putStringArrayList(
            STATE_QUEUED_PICKER_JOB_IDS,
            ArrayList(app.exportQueue.getQueuedPickerIds())
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null && isSupportedImportUri(uri, intent.type)) {
                    val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
                    importFromUri(uri, fallbackName)
                } else if (uri != null) {
                    Toast.makeText(this, "Unsupported file URI or format", Toast.LENGTH_SHORT).show()
                }
            }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

                if (uri != null && isSupportedImportUri(uri, intent.type)) {
                    val name = intent.getStringExtra(Intent.EXTRA_TITLE) ?: "document.pdf"
                    importFromUri(uri, name)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.forEachIndexed { index, uri ->
                    if (uri != null && isSupportedImportUri(uri, intent.type)) {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document_$index.pdf"
                        importFromUri(uri, name)
                    }
                }
            }
        }
    }

    private fun isSupportedImportUri(uri: Uri, intentMimeType: String?): Boolean {
        if (uri.scheme != "content" && uri.scheme != "file") return false
        val resolverType = contentResolver.getType(uri)?.lowercase()
        val declaredType = intentMimeType?.lowercase()
        val displayName = runCatching { DocumentIo.getDisplayName(this, uri) }.getOrNull().orEmpty().lowercase()
        return resolverType == "application/pdf" ||
            declaredType == "application/pdf" ||
            displayName.endsWith(".pdf")
    }

    private fun importFromUri(uri: Uri, fallbackName: String) {
        val app = application as PdfCraftApplication
        val appContext = applicationContext
        app.ioExecutor.execute {
            try {
                val bytes = DocumentIo.readBytes(appContext, uri)
                val displayName = DocumentIo.getDisplayName(appContext, uri).ifBlank { fallbackName }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (appReady) {
                        sendImportToIframe(displayName, bytes)
                    } else {
                        pendingIntentImports.add(PendingImport(displayName, bytes))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import PDF: ${e.message}", e)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this)
                            .setTitle("Unable to open PDF")
                            .setMessage("Could not read the selected file: ${e.message}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun sendImportToIframe(name: String, bytes: ByteArray) {
        messageBridge?.sendImport(name, bytes)
    }

    private fun getAllPendingExports(): Map<String, ExportQueue.ExportJob> {
        val app = application as PdfCraftApplication
        return app.exportQueue.getAllPendingJobs()
    }

    private fun maybePromptRecoveredExportsAtStartup() {
        if (startupRecoveryPromptShown) return
        val app = application as PdfCraftApplication
        val recovered = app.getRecoverableJobs()
        if (recovered.isNotEmpty() && app.exportQueue.getActivePickerJob() == null) {
            startupRecoveryPromptShown = true
            showPendingExportsDialog(getAllPendingExports(), isExitFlow = false)
        }
    }

    private fun checkPickerStatePersistence() {
        val queue = (application as PdfCraftApplication).exportQueue
        if (queue.didPickerStatePersistFail()) {
            Log.w(TAG, "Could not persist export recovery state: ${queue.getLastPickerStatePersistError()}")
            if (!isFinishing && !isDestroyed) Toast.makeText(this,
                "Could not save recovery state. Keep the app open and save the staged PDF.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestSaveDestination(job: ExportQueue.ExportJob) {
        if (isFinishing || isDestroyed) return
        val app = application as PdfCraftApplication
        try {
            val shouldLaunch = app.exportQueue.acquireOrEnqueuePicker(job)
            checkPickerStatePersistence()
            if (shouldLaunch) showSaveDestinationPicker(job)
        } catch (e: Exception) {
            showExportActionDialog(job, "Export recovery needs attention",
                "Could not persist the destination request: ${e.message}. The staged PDF is retained.")
        }
    }

    private fun removeStagedExport(job: ExportQueue.ExportJob) {
        val app = application as PdfCraftApplication
        app.exportQueue.completeAndRemoveJob(job.transferId)
        checkPickerStatePersistence()
    }

    private fun showSaveDestinationPicker(job: ExportQueue.ExportJob) {
        val safeName = job.name.replace("[^\\w\\-. ()]".toRegex(), "_").ifEmpty { "document.pdf" }
        val resolvedMime = LocalContentServer.resolveExportMimeType(safeName, job.mimeType)

        val createFileIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = resolvedMime
            putExtra(Intent.EXTRA_TITLE, safeName)
        }

        try {
            lastPickerLaunchedFor = job.transferId
            saveDestinationLauncher.launch(createFileIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch destination picker: ${e.message}", e)
            val app = application as PdfCraftApplication
            lastPickerLaunchedFor = null
            val (_, nextJob) = app.exportQueue.consumeCurrentAndAdvancePicker()
            checkPickerStatePersistence()
            handleSaveCancelled(job)
            if (nextJob != null) {
                showSaveDestinationPicker(nextJob)
            }
        }
    }

    private fun handleSaveDestinationSelected(destUri: Uri, job: ExportQueue.ExportJob) {
        val app = application as PdfCraftApplication
        val appContext = applicationContext
        app.ioExecutor.execute {
            try {
                DocumentIo.takeReadWritePermission(appContext, destUri)
                DocumentIo.copyFileToUri(appContext, job.stageFile, destUri)

                val verification = DocumentIo.verifyWrittenDocumentDetailed(
                    appContext,
                    destUri,
                    job.stagedHash,
                    job.totalBytes
                )

                runOnUiThread {
                    when (verification.outcome) {
                        DocumentIo.VerificationOutcome.VERIFIED -> {
                            Log.i(TAG, "Export independently verified: ${job.transferId}")
                            removeStagedExport(job)
                            messageBridge?.sendSaveResult(job.transferId, true)
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(
                                    this,
                                    "Saved & verified: ${job.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        DocumentIo.VerificationOutcome.UNAVAILABLE -> {
                            // Provider accepted and flushed all bytes, but does not allow re-reading.
                            Log.i(
                                TAG,
                                "Provider accepted write for ${job.transferId}; read-back verification unavailable: ${verification.detail}"
                            )
                            messageBridge?.sendSaveResult(job.transferId, true)
                            if (!isFinishing && !isDestroyed) {
                                AlertDialog.Builder(this)
                                    .setTitle("Saved to storage provider")
                                    .setMessage(
                                        "\"${job.name}\" (${job.totalBytes} bytes) was written to the selected destination, " +
                                            "but this storage provider does not support reading back the file for independent checksum verification.\n\n" +
                                            "Would you like to mark this export complete or keep the private staged copy as a backup?"
                                    )
                                    .setPositiveButton("Mark complete") { _, _ ->
                                        removeStagedExport(job)
                                    }
                                    .setNeutralButton("Keep backup") { _, _ ->
                                        app.exportQueue.cancelJob(job.transferId)
        checkPickerStatePersistence()
                                    }
                                    .setNegativeButton("Share copy…") { _, _ ->
                                        shareStagedExport(job)
                                    }
                                    .show()
                            }
                        }

                        DocumentIo.VerificationOutcome.MISMATCH -> {
                            Log.w(
                                TAG,
                                "Export verification mismatch for ${job.transferId}: ${verification.detail}"
                            )
                            app.exportQueue.failJob(job.transferId)
                            messageBridge?.sendSaveResult(job.transferId, false, "verification mismatch")
                            showExportActionDialog(
                                job,
                                title = "Save verification failed",
                                message = "The destination file did not match the expected checksum or length for \"${job.name}\" (${verification.detail}). Your private staged copy is safely preserved."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write export: ${e.message}", e)
                app.exportQueue.failJob(job.transferId)
                runOnUiThread {
                    messageBridge?.sendSaveResult(job.transferId, false, e.message ?: "write failed")
                    showExportActionDialog(
                        job,
                        title = "Save failed",
                        message = "Could not write \"${job.name}\" (${e.message}). Your staged copy is preserved so you can retry or share it."
                    )
                }
            }
        }
    }

    private fun handleSaveCancelled(job: ExportQueue.ExportJob) {
        val app = application as PdfCraftApplication
        app.exportQueue.cancelJob(job.transferId)
        checkPickerStatePersistence()
        Log.i(TAG, "Save cancelled for ${job.transferId}, keeping staged file")
        showExportActionDialog(
            job,
            title = "Export still pending",
            message = "\"${job.name}\" was not saved to a folder yet. You can choose a destination now, share it to another app, or keep it pending."
        )
    }

    private fun showExportActionDialog(
        job: ExportQueue.ExportJob,
        title: String,
        message: String
    ) {
        if (isFinishing || isDestroyed) return
        val options = arrayOf(
            "Save to device…",
            "Share via Android…",
            "Keep pending for later",
            "Discard this export"
        )
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> requestSaveDestination(job)
                    1 -> shareStagedExport(job)
                    2 -> Toast.makeText(
                        this,
                        "Kept \"${job.name}\" in pending exports",
                        Toast.LENGTH_SHORT
                    ).show()
                    3 -> {
                        removeStagedExport(job)
                        Toast.makeText(this, "Discarded \"${job.name}\"", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun shareStagedExport(job: ExportQueue.ExportJob) {
        if (!job.stageFile.exists()) {
            Toast.makeText(this, "Staged file is no longer available", Toast.LENGTH_SHORT).show()
            removeStagedExport(job)
            return
        }
        try {
            val authority = "${applicationContext.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(this, authority, job.stageFile)
            val resolvedMime = LocalContentServer.resolveExportMimeType(job.name, job.mimeType)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = resolvedMime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TITLE, job.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share ${job.name}"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share staged export: ${e.message}", e)
            Toast.makeText(this, "Unable to share file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(
            TAG,
            "Config changed: orientation=${newConfig.orientation}, screenSize=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}"
        )
        updateStatusBarAppearance(newConfig)
        ViewCompat.requestApplyInsets(webView)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onStart() {
        super.onStart()
        (application as PdfCraftApplication).attachExportListener(this) { job -> requestSaveDestination(job) }
    }

    override fun onStop() {
        (application as PdfCraftApplication).detachExportListener(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        updateStatusBarAppearance(resources.configuration)
        ViewCompat.requestApplyInsets(webView)
        resumeDeferredPickerIfNeeded()
    }

    /**
     * If a staging callback was deferred because the previous Activity was finishing,
     * or a picker job survived recreation/process death, launch the destination picker
     * for the durable active job exactly once in THIS Activity.
     */
    private fun resumeDeferredPickerIfNeeded() {
        if (isFinishing || isDestroyed) return
        val app = application as PdfCraftApplication
        val active = app.exportQueue.getActivePickerJob() ?: return
        if (active.transferId == lastPickerLaunchedFor) return
        lastPickerLaunchedFor = active.transferId
        showSaveDestinationPicker(active)
    }

    override fun onDestroy() {
        (application as PdfCraftApplication).detachExportListener(this)
        mainHandler.removeCallbacks(startupTimeoutRunnable)
        messageBridge?.unregister()
        chromeClient?.cleanup()
        runCatching {
            webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            webView.stopLoading()
            webView.destroy()
        }
        // Note: app.ioExecutor is owned by PdfCraftApplication and intentionally NOT shut down
        // on Activity destruction so in-flight staging/writes complete across Activity recreation.
        super.onDestroy()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val hasInFlight = messageBridge?.hasInFlightTransfers() == true
                // Also block when host.js still holds queued/active transfers that have
                // not reached native staging yet — otherwise their bytes are lost.
                if (hasInFlight || hostQueuedExports > 0) {
                    Toast.makeText(
                        this@MainActivity,
                        "An export is still being sent to the app — please wait a moment.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                if (hostFailedExports > 0) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Exports still need attention")
                        .setMessage("$hostFailedExports export(s) have not been saved. Leaving now discards any retry data held only in the editor.")
                        .setPositiveButton("Review export") { _, _ -> messageBridge?.sendReviewFailedExport() }
                        .setNeutralButton("Stay", null)
                        .setNegativeButton("Leave without saving") { _, _ ->
                            val jobs = getAllPendingExports()
                            if (jobs.isNotEmpty()) showPendingExportsDialog(jobs, isExitFlow = true) else finish()
                        }.show()
                    return
                }
                if (appDirty) {
                    showUnsavedChangesDialog()
                } else {
                    val recoverableJobs = getAllPendingExports()
                    if (recoverableJobs.isNotEmpty()) {
                        showPendingExportsDialog(recoverableJobs, isExitFlow = true)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun showUnsavedChangesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("You have unsaved edits in PdfCraft. Use the Save button inside the editor before leaving, or discard your unsaved edits.")
            .setPositiveButton("Stay in editor", null)
            .setNegativeButton("Discard edits") { _, _ ->
                val recoverableJobs = getAllPendingExports()
                if (recoverableJobs.isNotEmpty()) {
                    showPendingExportsDialog(recoverableJobs, isExitFlow = true)
                } else {
                    finish()
                }
            }
            .show()
    }

    private fun showPendingExportsDialog(
        jobs: Map<String, ExportQueue.ExportJob>,
        isExitFlow: Boolean
    ) {
        if (jobs.isEmpty()) return
        val jobList = jobs.values.toList()
        val labels = jobList.map { "${it.name} (${it.totalBytes / 1024} KiB)" }.toTypedArray()

        val builder = AlertDialog.Builder(this)
            .setTitle("Pending exports (${jobList.size})")
            .setItems(labels) { _, which ->
                val selected = jobList[which]
                showExportActionDialog(
                    selected,
                    title = selected.name,
                    message = "Choose what to do with this staged export (${selected.totalBytes} bytes):"
                )
            }
            .setPositiveButton("Stay", null)
            .setNegativeButton("Discard all") { _, _ ->
                jobList.forEach { job -> removeStagedExport(job) }
                if (isExitFlow) {
                    finish()
                }
            }

        if (isExitFlow) {
            builder.setNeutralButton("Exit & keep for next launch") { _, _ ->
                finish()
            }
        }

        builder.show()
    }

    private fun showErrorSurface(message: String, canRetry: Boolean) {
        errorSurfaceShowing = true
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }

        val errorView = TextView(this).apply {
            text = message
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        container.addView(errorView)

        if (canRetry) {
            val retryButton = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.retry)
                setOnClickListener {
                    errorSurfaceShowing = false
                    recreate()
                }
            }
            container.addView(retryButton)
        }

        val pending = getAllPendingExports()
        if (pending.isNotEmpty()) {
            val pendingButton = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "Pending exports (${pending.size})"
                setOnClickListener {
                    showPendingExportsDialog(pending, isExitFlow = false)
                }
            }
            container.addView(pendingButton)
        }

        setContentView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun setupWindowInsetsListener() {
        webView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        updateStatusBarAppearance(resources.configuration)
    }

    private fun updateStatusBarAppearance(config: Configuration) {
        val isNightMode =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode
    }
}
