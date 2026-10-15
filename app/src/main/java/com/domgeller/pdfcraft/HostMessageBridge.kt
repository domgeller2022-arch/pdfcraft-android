package com.domgeller.pdfcraft

import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicLong

/**
 * HostMessageBridge — origin-restricted AndroidX WebKit WebMessageListener
 * that bridges between the Android shell and the host page (host.js).
 */
class HostMessageBridge(
    private val webView: WebView,
    private val assetOrigin: String,
    private val bridgeObjectName: String = "WebMessageListener",
    private val stagingDir: File,
    private val metadataDir: File,
    private val exportQueue: ExportQueue = ExportQueue(stagingDir, metadataDir),
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
        /** host.js refused/failed a transfer but RETAINED it (oversize, queue-full, ack timeout, native reject). */
        fun onSaveFailed(transferId: String, name: String, reason: String, error: String?, retained: Boolean)
        /** host.js still holds queued/active/retained transfers not yet staged natively. */
        fun onPendingWork(queued: Int, active: Int, failed: Int, queuedBytes: Long)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val importCounter = AtomicLong(0L)

    @Volatile
    private var appReady = false

    @Volatile
    private var appVersion: String? = null

    @Volatile
    private var isDestroyed = false

    fun register() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.e(TAG, "WEB_MESSAGE_LISTENER not supported")
            callback.onAppFailed("WebView does not support WEB_MESSAGE_LISTENER")
            return
        }
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
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        if (isDestroyed) return
        if (!isMainFrame) return
        if (sourceOrigin.toString() != assetOrigin) {
            Log.w(TAG, "Rejected WebMessage from unexpected origin: $sourceOrigin")
            return
        }

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
                val transferId = json.optString("transferId", "save_" + System.currentTimeMillis())
                val name = json.optString("name", "document.pdf")
                val saveAs = json.optBoolean("saveAs", false)
                val mimeType = json.optString("mimeType", "").takeIf { it.isNotBlank() }
                val expectedSha256 = json.optString("sha256", "").takeIf { it.isNotBlank() }
                val declaredBytes = json.optLong("totalBytes", -1L)
                val bytes = decodeBase64(json.optString("bytes"))
                if (bytes != null && (declaredBytes < 0L || bytes.size.toLong() == declaredBytes)) {
                    handleSave(transferId, name, saveAs, bytes, mimeType, expectedSha256)
                } else {
                    Log.e(TAG, "host:save: invalid or size-mismatched payload")
                    callback.onSaveCompleted(transferId, false, "Invalid save payload")
                }
            }

            "host:saveChunk" -> {
                val transferId = json.optString("transferId", "")
                val name = json.optString("name", "document.pdf")
                val saveAs = json.optBoolean("saveAs", false)
                val mimeType = json.optString("mimeType", "").takeIf { it.isNotBlank() }
                val totalBytes = json.optLong("totalBytes", 0L)
                val seq = json.optInt("seq", -1)
                val totalChunks = json.optInt("totalChunks", 0)
                val chunkBytes = decodeBase64(json.optString("data"))
                val declaredChunkLen = json.optInt("chunkLength", chunkBytes?.size ?: -1)

                if (transferId.isBlank() || chunkBytes == null) {
                    Log.e(TAG, "host:saveChunk: invalid chunk payload for $transferId")
                    sendChunkAck(transferId, seq, false, "Invalid chunk payload")
                    callback.onSaveCompleted(transferId, false, "Invalid chunk payload")
                    return
                }
                handleSaveChunk(
                    transferId,
                    name,
                    saveAs,
                    totalBytes,
                    totalChunks,
                    seq,
                    declaredChunkLen,
                    chunkBytes,
                    mimeType
                )
            }

            "host:saveComplete" -> {
                val transferId = json.optString("transferId", "")
                val name = json.optString("name", "document.pdf")
                val saveAs = json.optBoolean("saveAs", false)
                val mimeType = json.optString("mimeType", "").takeIf { it.isNotBlank() }
                val totalBytes = json.optLong("totalBytes", 0L)
                val expectedSha256 = json.optString("sha256", "").takeIf { it.isNotBlank() }
                if (transferId.isNotBlank()) {
                    finalizeChunkedSave(transferId, name, saveAs, totalBytes, mimeType, expectedSha256)
                }
            }

            "host:blobDownload" -> {
                val transferId = json.optString("transferId", "blob_" + System.currentTimeMillis())
                val name = json.optString("filename", json.optString("name", "download.pdf"))
                val mimeType = json.optString("mimeType", "").takeIf { it.isNotBlank() }
                val expectedSha256 = json.optString("sha256", "").takeIf { it.isNotBlank() }
                val declaredBytes = json.optLong("totalBytes", -1L)
                val bytes = decodeBase64(json.optString("bytes"))
                if (bytes != null && (declaredBytes < 0L || bytes.size.toLong() == declaredBytes)) {
                    handleSave(transferId, name, true, bytes, mimeType, expectedSha256)
                } else {
                    Log.e(TAG, "host:blobDownload: invalid or size-mismatched payload")
                    callback.onSaveCompleted(transferId, false, "Invalid blob download payload")
                }
            }

            "host:saveFailed" -> {
                // host.js retained the payload and is asking Android to surface a retry path.
                callback.onSaveFailed(
                    json.optString("transferId", ""),
                    json.optString("name", "document.pdf"),
                    json.optString("reason", "unknown"),
                    json.optString("error", "").takeIf { it.isNotBlank() },
                    json.optBoolean("retained", true)
                )
            }

            "host:pendingWork" -> {
                callback.onPendingWork(
                    json.optInt("queued", 0),
                    json.optInt("active", 0),
                    json.optInt("failed", 0),
                    json.optLong("queuedBytes", 0L)
                )
            }
        }
    }

    private fun handleSave(
        transferId: String,
        name: String,
        saveAs: Boolean,
        bytes: ByteArray,
        mimeType: String?,
        expectedSha256: String?
    ) {
        try {
            val job = exportQueue.stageExport(transferId, name, saveAs, bytes, mimeType, expectedSha256)
            callback.onSaveStaged(transferId, job)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage save: ${e.message}", e)
            sendSaveResult(transferId, false, e.message)
            callback.onSaveCompleted(transferId, false, e.message)
        }
    }

    private fun handleSaveChunk(
        transferId: String,
        name: String,
        saveAs: Boolean,
        totalBytes: Long,
        totalChunks: Int,
        seq: Int,
        declaredChunkLen: Int,
        chunkBytes: ByteArray,
        mimeType: String?
    ) {
        try {
            exportQueue.receiveChunk(
                transferId = transferId,
                name = name,
                saveAs = saveAs,
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                seq = seq,
                chunkLength = declaredChunkLen,
                data = chunkBytes,
                mimeType = mimeType
            )
            sendChunkAck(transferId, seq, true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream save chunk ($transferId, seq=$seq): ${e.message}", e)
            sendChunkAck(transferId, seq, false, e.message)
            sendSaveResult(transferId, false, e.message)
            callback.onSaveCompleted(transferId, false, e.message)
        }
    }

    private fun finalizeChunkedSave(
        transferId: String,
        name: String,
        saveAs: Boolean,
        totalBytes: Long,
        mimeType: String?,
        expectedSha256: String?
    ) {
        try {
            val job = exportQueue.finalizeChunkedExport(
                transferId = transferId,
                name = name,
                saveAs = saveAs,
                totalBytes = totalBytes,
                mimeType = mimeType,
                expectedSha256 = expectedSha256
            )
            callback.onSaveStaged(transferId, job)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize chunked save ($transferId): ${e.message}", e)
            sendSaveResult(transferId, false, e.message)
            callback.onSaveCompleted(transferId, false, e.message)
        }
    }

    fun sendToHost(message: String) {
        if (isDestroyed) return
        val deliver = Runnable {
            if (isDestroyed) return@Runnable
            val webMessage = WebMessageCompat(message)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) {
                WebViewCompat.postWebMessage(webView, webMessage, Uri.parse(assetOrigin))
            } else {
                Log.e(TAG, "POST_WEB_MESSAGE not supported")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver.run()
        } else {
            mainHandler.post(deliver)
        }
    }

    fun sendImport(name: String, bytes: ByteArray) {
        val sha256 = DocumentIo.sha256(bytes)
        if (bytes.size <= CHUNK_SIZE) {
            val payload = JSONObject().apply {
                put("type", "host:import")
                put("name", name)
                put("totalBytes", bytes.size)
                put("sha256", sha256)
                put("bytes", encodeBase64(bytes))
            }.toString()
            sendToHost(payload)
        } else {
            val transferId = "import_${System.currentTimeMillis()}_${importCounter.incrementAndGet()}"
            val totalChunks = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            for (i in 0 until totalChunks) {
                val start = i * CHUNK_SIZE
                val end = minOf(start + CHUNK_SIZE, bytes.size)
                val chunk = bytes.copyOfRange(start, end)
                val payload = JSONObject().apply {
                    put("type", "host:importChunk")
                    put("transferId", transferId)
                    put("name", name)
                    put("totalBytes", bytes.size)
                    put("seq", i)
                    put("totalChunks", totalChunks)
                    put("sha256", sha256)
                    put("data", encodeBase64(chunk))
                }.toString()
                sendToHost(payload)
            }
        }
    }

    private fun sendChunkAck(transferId: String, seq: Int, success: Boolean, error: String?) {
        val payload = JSONObject().apply {
            put("type", "host:saveChunkAck")
            put("transferId", transferId)
            put("seq", seq)
            put("success", success)
            if (error != null) {
                put("error", error)
            }
        }.toString()
        sendToHost(payload)
    }

    fun sendSaveResult(transferId: String, success: Boolean, error: String? = null) {
        val payload = JSONObject().apply {
            put("type", "host:saveResult")
            put("transferId", transferId)
            put("success", success)
            if (error != null) {
                put("error", error)
            }
        }.toString()
        sendToHost(payload)
    }

    fun hasInFlightTransfers(): Boolean = exportQueue.hasInFlightTransfers()

    /** Ask host.js to re-send a retained export under a fresh transfer id. */
    fun sendRetryExport(transferId: String) {
        sendToHost(JSONObject().apply {
            put("type", "host:retryExport")
            put("transferId", transferId)
        }.toString())
    }

    /** Tell host.js to drop a retained export the user chose to discard. */
    fun sendDiscardExport(transferId: String) {
        sendToHost(JSONObject().apply {
            put("type", "host:discardExport")
            put("transferId", transferId)
        }.toString())
    }

    fun sendReviewFailedExport() {
        sendToHost(JSONObject().put("type", "host:reviewFailedExport").toString())
    }

    fun isAppReady(): Boolean = appReady
    fun getAppVersion(): String? = appVersion

    private fun encodeBase64(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    private fun decodeBase64(data: String): ByteArray? =
        if (data.isBlank()) null
        else try { Base64.decode(data, Base64.NO_WRAP) } catch (e: Exception) { null }
}
