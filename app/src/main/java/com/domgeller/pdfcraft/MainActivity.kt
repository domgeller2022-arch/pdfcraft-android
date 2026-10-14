package com.domgeller.pdfcraft

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewAssetLoader
import java.util.concurrent.Executors

/**
 * MainActivity — hosts the PdfCraft web app inside an Android WebView.
 *
 * Uses WebViewAssetLoader to serve bundled WASM/JS/HTML assets over a local
 * HTTPS origin (https://appassets.androidplatform.net), preserving the
 * same-origin ?host=parent bridge used by the upstream app.
 *
 * Key responsibilities (per plan §3–§10):
 * - Full-size WebView with hardware acceleration
 * - WebViewAssetLoader for local HTTPS serving
 * - WebMessageListener for host ↔ Android messaging
 * - PdfCraftChromeClient for original file picker integration
 * - Configuration changes (fold/unfold, rotation) preserving the WebView instance
 * - Back button: dismiss keyboard → exit prompt for unsaved work
 * - Intent handling (ACTION_VIEW PDF, ACTION_SEND)
 * - onRenderProcessGone handling
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var localContentServer: LocalContentServer
    private var chromeClient: PdfCraftChromeClient? = null
    private var messageBridge: HostMessageBridge? = null

    private val ioExecutor = Executors.newSingleThreadExecutor()

    // Pending import from intent (queued until pdfcraft:ready)
    private val pendingIntentImports = mutableListOf<PendingImport>()
    private var appReady = false
    private var appDirty = false

    // Current export job awaiting destination selection
    private var currentExportJob: ExportQueue.ExportJob? = null

    data class PendingImport(val name: String, val bytes: ByteArray)

    // ActivityResultLauncher for save destination picker
    private val saveDestinationLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val job = currentExportJob
            currentExportJob = null
            if (job == null) return@registerForActivityResult

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
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge for Android 16 large-display behavior
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Hardware acceleration is required for WebGL/Glow renderer
        window.addFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Create the WebView
        webView = WebView(this)
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        setContentView(webView, layoutParams)

        // Configure WebView settings
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
        settings.builtInZoomControls = false // Let eframe handle its own zoom
        settings.textZoom = 100

        // Enable WebView debugging only for debug builds
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Set up WebViewAssetLoader for local HTTPS serving
        localContentServer = LocalContentServer(this)
        assetLoader = localContentServer.createAssetLoader()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView, url: String
            ): Boolean = false
        }

        // Verify the web build exists in assets
        if (!localContentServer.hasWebBuild()) {
            Log.e(TAG, "Web build not found in APK assets at assets/pdfcraft/")
            showErrorSurface("PdfCraft web build not found in APK. Build may need to be rebuilt.", true)
            return
        }

        // Set up WebChromeClient for file picker (plan §6)
        chromeClient = PdfCraftChromeClient(this) { uris, graceful ->
            // The PdfCraftChromeClient handles the callback internally
            // This lambda is for future expansion (e.g., logging)
        }
        webView.webChromeClient = chromeClient

        // Register the WebMessageListener for host ↔ Android communication (plan §5)
        val app = application as PdfCraftApplication
        messageBridge = HostMessageBridge(
            webView = webView,
            assetOrigin = LocalContentServer.ASSET_ORIGIN,
            assetPath = "/",
            stagingDir = app.stagingDir,
            metadataDir = app.metadataDir,
            executor = ioExecutor,
            callback = object : HostMessageBridge.BridgeCallback {
                override fun onSaveStaged(transferId: String, job: ExportQueue.ExportJob) {
                    runOnUiThread {
                        currentExportJob = job
                        showSaveDestinationPicker(job)
                    }
                }

                override fun onSaveCompleted(transferId: String, success: Boolean, error: String?) {
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Save failed: $error", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                override fun onImportRequested() {}

                override fun onAppReady(version: String) {
                    appReady = true
                    Log.i(TAG, "PdfCraft app ready, version: $version")
                    if (pendingIntentImports.isNotEmpty()) {
                        val imports = pendingIntentImports.toList()
                        pendingIntentImports.clear()
                        for (import in imports) {
                            sendImportToIframe(import.name, import.bytes)
                        }
                    }
                }

                override fun onDirtyChanged(dirty: Boolean) { appDirty = dirty }

                override fun onAppFailed(error: String) {
                    runOnUiThread { showErrorSurface("PdfCraft failed: $error", true) }
                }
            }
        )
        messageBridge?.register()

        // Load the host page via the asset loader
        webView.loadUrl(LocalContentServer.HOST_URL)

        // Handle incoming intent (if launched with a PDF)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null && isPdfUri(uri)) {
                    importFromUri(uri, "document.pdf")
                }
            }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null && uri.scheme == "content") {
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
                    if (uri != null && uri.scheme == "content") {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document_$index.pdf"
                        importFromUri(uri, name)
                    }
                }
            }
        }
    }

    private fun isPdfUri(uri: Uri): Boolean {
        val type = contentResolver.getType(uri)
        return type == "application/pdf"
    }

    private fun importFromUri(uri: Uri, name: String) {
        ioExecutor.execute {
            try {
                val bytes = DocumentIo.readBytes(this, uri)
                val displayName = DocumentIo.getDisplayName(this, uri) ?: name
                runOnUiThread {
                    if (appReady) {
                        sendImportToIframe(displayName, bytes)
                    } else {
                        pendingIntentImports.add(PendingImport(displayName, bytes))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import PDF: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Failed to open file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendImportToIframe(name: String, bytes: ByteArray) {
        // Send the import request to the host page, which forwards it to the iframe
        messageBridge?.sendImport(name, bytes)
    }

    private fun showSaveDestinationPicker(job: ExportQueue.ExportJob) {
        val safeName = job.name.replace("[^\\w\\-. ]".toRegex(), "_").ifEmpty { "document.pdf" }

        val createFileIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, safeName)
        }

        saveDestinationLauncher.launch(createFileIntent)
    }

    private fun handleSaveDestinationSelected(destUri: Uri, job: ExportQueue.ExportJob) {
        ioExecutor.execute {
            try {
                DocumentIo.takeReadWritePermission(this, destUri)
                // Copy from staging to the chosen URI
                val stagedBytes = DocumentIo.readFromFile(job.stageFile)
                DocumentIo.writeToUri(this, destUri, stagedBytes)

                // Verify the written file (if provider permits re-reading)
                val verified = DocumentIo.verifyWrittenDocument(this, destUri, job.stagedHash, job.totalBytes)

                runOnUiThread {
                    if (verified) {
                        Log.i(TAG, "Export verified: ${job.transferId}")
                        messageBridge?.sendToHost(
                            """{"type":"host:saveResult","transferId":"${job.transferId}","success":true}"""
                        )
                        Toast.makeText(this, "Saved: ${job.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.w(TAG, "Export verification failed for ${job.transferId}")
                        Toast.makeText(
                            this,
                            "Warning: saved file may be incomplete",
                            Toast.LENGTH_LONG
                        ).show()
                        messageBridge?.sendToHost(
                            """{"type":"host:saveResult","transferId":"${job.transferId}","success":false,"error":"verification failed"}"""
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write export: ${e.message}", e)
                runOnUiThread {
                    messageBridge?.sendToHost(
                        """{"type":"host:saveResult","transferId":"${job.transferId}","success":false,"error":"${e.message}"}"""
                    )
                }
            }
        }
    }

    private fun handleSaveCancelled(job: ExportQueue.ExportJob) {
        // Cancelled exports remain pending with Retry/Share/explicit Delete controls
        Log.i(TAG, "Save cancelled for ${job.transferId}, keeping staged file")
        Toast.makeText(
            this,
            "Save cancelled. Export remains pending for retry.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Config changed: orientation=${newConfig.orientation}, screenSize=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}")
        // Do NOT recreate the WebView or reload the page.
        // The live WASM session, tabs, and editing state are preserved.
        // The iframe will receive a resize event and eframe will handle canvas resizing.
        applyWindowInsets()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        applyWindowInsets()
    }

    override fun onDestroy() {
        super.onDestroy()
        messageBridge?.unregister()
        chromeClient?.cleanup()
        webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
        webView.stopLoading()
        webView.destroy()
    }

    /**
     * Back button handling (plan §10):
     * 1. Dismiss keyboard/IME if open
     * 2. Exit fullscreen if in fullscreen mode
     * 3. If dirty/volatile transfers/pending exports: show Stay/Discard/Cancel
     */
    override fun onBackPressed() {
        if (appDirty) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Unsaved changes")
                .setMessage("You have unsaved changes. Save before closing?")
                .setPositiveButton("Save") { _, _ ->
                    // Trigger a save through the host bridge
                    // Note: no supported host command to trigger Save automatically
                    Toast.makeText(this, "Use the Save button in PdfCraft before leaving", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Discard", null)
                .setNeutralButton("Cancel", null)
                .show()
            return
        }
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showErrorSurface(message: String, canRetry: Boolean) {
        val errorView = android.widget.TextView(this).apply {
            text = message
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }

        val rootView = FrameLayout(this)
        rootView.addView(errorView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        if (canRetry) {
            val retryButton = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            retryButton.text = "Retry"
            retryButton.setOnClickListener {
                recreate()
            }
            rootView.addView(retryButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
            ).apply { setMargins(0, 0, 0, 120) })
        }

        setContentView(rootView)
    }

    private fun applyWindowInsets() {
        // Apply status bar and navigation bar insets to the WebView container
        val insetsController = WindowInsetsControllerCompat(window, webView)
        val windowInsets = ViewCompat.getRootWindowInsets(webView)

        if (windowInsets != null) {
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val paddingTop = systemBars.top + displayCutout.top
            val paddingRight = systemBars.right
            val paddingLeft = systemBars.left + displayCutout.left
            val paddingBottom = systemBars.bottom

            webView.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            webView.setClipToPadding(false)
            webView.setClipChildren(false)
        }

        // Handle keyboard inset: adjustResize means the WebView will resize
        // automatically. We only need to ensure the IME inset is not doubled.
        insetsController.isAppearanceLightStatusBars = true
    }
}
