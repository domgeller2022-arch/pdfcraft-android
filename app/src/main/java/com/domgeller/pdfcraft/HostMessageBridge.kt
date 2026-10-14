package com.domgeller.pdfcraft

import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * HostMessageBridge — origin-restricted AndroidX WebKit WebMessageListener
 * that bridges between the Android shell and the host page (host.js).
 *
 * Protocol: all messages are JSON strings; binary data is base64-encoded inline.
 * Small files (< 256 KiB): single message. Large files: chunked protocol.
 */
class HostMessageBridge(
    private val webView: WebView,
    private val assetOrigin: String,
    private val bridgeObjectName: String = "WebMessageListener",
    private val stagingDir: File,
    private val metadataDir: File,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val callback: BridgeCallback
) : WebViewCompat.WebMessageListener {

    companion object {
        const val TAG = "HostMessageBridge"
        const val CHUNK_SIZE = 256 * 1024 // 256 KiB per plan §5
    }

    interface BridgeCallback {
        fun onSaveStaged(transferId: String, job: ExportQueue.ExportJob)
        fun onSaveCompleted(transferId: String, success: Boolean, error: String?)
        fun onImportRequested()
        fun onAppReady(version: String)
        fun onDirtyChanged(dirty: Boolean)
        fun onAppFailed(error: String)
    }

    private val exportQueue: ExportQueue = ExportQueue(stagingDir, metadataDir)
    private var appReady = false
    private var appVersion: String? = null
    private var isDestroyed = false

    // Chunked transfers currently in progress
    private val chunkedTransfers = mutableMapOf<String, ChunkedTransfer>()

    data class ChunkedTransfer(
        val transferId: String,
        val name: String,
        val saveAs: Boolean,
        val totalBytes: Long,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray>
    ) {
        fun isComplete(): Boolean = chunks.size >= totalChunks
    }

    fun register() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.e(TAG, "WEB_MESSAGE_LISTENER not supported")
            callback.onAppFailed("WebView does not support WEB_MESSAGE_LISTENER")
            return
        }
        // Inject download-adapter.js as a document-start script so it intercepts
        // Blob downloads inside the iframe before upstream app scripts run.
        // The adapter self-exits on the host page via pathname check.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val adapterScript = readAsset("shell/download-adapter.js")
            if (adapterScript != null) {
                WebViewCompat.addDocumentStartJavaScript(webView, adapterScript, setOf(assetOrigin))
                Log.d(TAG, "Download adapter injected as document-start script")
            }
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT not supported; adapter injection deferred to host.js")
        }
        WebViewCompat.addWebMessageListener(webView, bridgeObjectName, setOf(assetOrigin), this)
        Log.d(TAG, "WebMessageListener registered: name=$bridgeObjectName, origin=$assetOrigin")
    }

    private fun readAsset(path: String): String? {
        return try {
            webView.context.assets.open(path).bufferedReader().readText()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read asset: $path", e)
            null
        }
    }

    fun unregister() {
        isDestroyed = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, bridgeObjectName)
        }
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        uri: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        if (isDestroyed) return
        if (!isMainFrame) return

        val payload = message.data ?: return
        executor.execute {
            try {
                handleMessage(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message: ${e.message}", e)
            }
        }
    }

    private fun handleMessage(payload: String) {
        val json = JSONObject(payload)
        val type = json.optString("type", "")

        when (type) {
            "host:ready" -> {
                val version = json.optString("version", "unknown")
                appReady = true
                appVersion = version
                callback.onAppReady(version)
            }

            "host:dirty" -> {
                callback.onDirtyChanged(json.optBoolean("dirty", false))
            }

            "host:failed" -> {
                callback.onAppFailed(json.optString("error", "unknown error"))
            }

            "host:save" -> {
                val transferId = json.optString("transferId", "")
                val name = json.optString("name", "document.pdf")
                val saveAs = json.optBoolean("saveAs", false)
                val bytes = decodeBase64(json.optString("bytes"))
                if (bytes != null) {
                    handleSave(transferId, name, saveAs, bytes)
                } else {
                    Log.e(TAG, "host:save: invalid or missing bytes")
                }
            }

            "host:saveChunk" -> {
                val transferId = json.optString("transferId", "")
                val name = json.optString("name", "document.pdf")
                val saveAs = json.optBoolean("saveAs", false)
                val totalBytes = json.optLong("totalBytes", 0)
                val seq = json.optInt("seq", 0)
                val totalChunks = json.optInt("totalChunks", 1)
                val chunkBytes = decodeBase64(json.optString("data"))

                chunkBytes?.let {
                    handleSaveChunk(transferId, name, saveAs, totalBytes, totalChunks, seq, it)
                }
            }

            "host:saveComplete" -> {
                val transferId = json.optString("transferId", "")
                val name = json.optString("name", "document.pdf")
                val saveAs = json.optBoolean("saveAs", false)
                val totalBytes = json.optLong("totalBytes", 0)
                finalizeChunkedSave(transferId, name, saveAs, totalBytes)
            }

            "host:blobDownload" -> {
                val transferId = json.optString("transferId", "blob_" + System.currentTimeMillis())
                val name = json.optString("filename", json.optString("name", "download.pdf"))
                val bytes = decodeBase64(json.optString("bytes"))
                if (bytes != null) {
                    handleSave(transferId, name, false, bytes)
                } else {
                    Log.e(TAG, "host:blobDownload: invalid or missing bytes")
                }
            }
        }
    }

    private fun handleSave(transferId: String, name: String, saveAs: Boolean, bytes: ByteArray) {
        Log.d(TAG, "Save: $name, ${bytes.size} bytes, saveAs=$saveAs")
        try {
            val job = exportQueue.stageExport(transferId, name, saveAs, bytes)
            callback.onSaveStaged(transferId, job)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage save: ${e.message}", e)
            callback.onSaveCompleted(transferId, false, e.message)
        }
    }

    private fun handleSaveChunk(
        transferId: String, name: String, saveAs: Boolean,
        totalBytes: Long, totalChunks: Int, seq: Int, chunkBytes: ByteArray
    ) {
        try {
            var transfer = chunkedTransfers[transferId]
            if (transfer == null) {
                transfer = ChunkedTransfer(
                    transferId = transferId,
                    name = name,
                    saveAs = saveAs,
                    totalBytes = totalBytes,
                    totalChunks = totalChunks,
                    chunks = mutableMapOf()
                )
                chunkedTransfers[transferId] = transfer
            }
            transfer.chunks[seq] = chunkBytes

            if (transfer.isComplete()) {
                chunkedTransfers.remove(transferId)
                val total = transfer.chunks.values.sumOf { it.size }
                val combined = ByteArray(total)
                var offset = 0
                // Sort by sequence to ensure correct order
                for (i in 0 until totalChunks) {
                    val chunk = transfer.chunks[i] ?: continue
                    System.arraycopy(chunk, 0, combined, offset, chunk.size)
                    offset += chunk.size
                }
                handleSave(transferId, name, saveAs, combined)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle save chunk: ${e.message}", e)
        }
    }

    private fun finalizeChunkedSave(transferId: String, name: String, saveAs: Boolean, totalBytes: Long) {
        // Chunks are finalized in handleSaveChunk when all received.
        // This method is kept for protocol completeness.
        Log.d(TAG, "finalizeChunkedSave: $transferId (totalBytes=$totalBytes)")
    }

    /**
     * Send a JSON string message to the host page via postWebMessage.
     */
    fun sendToHost(message: String) {
        if (isDestroyed) return
        val webMessage = WebMessageCompat(message)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) {
            WebViewCompat.postWebMessage(webView, webMessage, Uri.parse(assetOrigin))
        } else {
            Log.e(TAG, "POST_WEB_MESSAGE not supported")
        }
    }

    /**
     * Import a PDF from Android (intent or file picker) by sending bytes to the host page,
     * which forwards them to the iframe as pdfcraft:open.
     */
    fun sendImport(name: String, bytes: ByteArray) {
        if (bytes.size <= CHUNK_SIZE) {
            // Single message — base64-encode inline
            sendToHost("""{"type":"host:import","name":"${escapeJson(name)}","bytes":"${encodeBase64(bytes)}"}""")
        } else {
            // Chunked transfer
            val transferId = "import:" + System.currentTimeMillis()
            val totalChunks = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            for (i in 0 until totalChunks) {
                val start = i * CHUNK_SIZE
                val end = minOf(start + CHUNK_SIZE, bytes.size)
                val chunk = bytes.copyOfRange(start, end)
                sendToHost(
                    """{"type":"host:importChunk","transferId":"$transferId","name":"${escapeJson(name)}","totalBytes":${bytes.size},"seq":$i,"totalChunks":$totalChunks,"data":"${encodeBase64(chunk)}"}"""
                )
            }
        }
    }

    fun sendSaveResult(transferId: String, success: Boolean, error: String? = null) {
        val err = if (error != null) """"error":"${escapeJson(error)}"""" else ""
        sendToHost("""{"type":"host:saveResult","transferId":"$transferId","success":$success,$err}""")
    }

    fun isAppReady(): Boolean = appReady
    fun getAppVersion(): String? = appVersion

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun encodeBase64(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    private fun decodeBase64(data: String): ByteArray? =
        try { Base64.decode(data, Base64.NO_WRAP) } catch (e: Exception) { null }
}
