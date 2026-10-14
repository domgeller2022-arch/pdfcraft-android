package com.domgeller.pdfcraft

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * PdfCraftChromeClient — handles file picker requests from the embedded web app.
 *
 * The existing PdfCraft app uses `rfd` browser file pickers. In the browser, these
 * appear as HTML <input type="file"> elements, which the WebView routes through
 * onShowFileChooser. We launch the Android Storage Access Framework document picker
 * and return the selected content:// URIs to the original WebView callback.
 *
 * Key rules (per plan §6):
 * - Preserve original chooser accept types (normalizing extensions like .pdf to MIME types)
 *   and single/multiple mode
 * - Validate returned content:// URIs
 * - Return actual URIs, not filesystem paths
 * - Complete each callback exactly once (including cancelling any superseded callback)
 * - Cancellation returns null
 */
class PdfCraftChromeClient(
    private val activity: ComponentActivity,
    private val onFilePicked: (uris: Array<Uri>?, isGraceful: Boolean) -> Unit
) : WebChromeClient() {

    private var uploadCallback: ValueCallback<Array<Uri>>? = null

    // Activity result launcher for single file selection
    private val singlePicker: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ActivityResultCallback { result ->
            handlePickerResult(result.resultCode, result.data)
        }
    )

    // Activity result launcher for multiple file selection
    private val multiplePicker: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ActivityResultCallback { result ->
            handlePickerResult(result.resultCode, result.data)
        }
    )

    /**
     * Check whether a document picker is currently awaiting a user selection.
     */
    fun isPickerActive(): Boolean = uploadCallback != null

    /**
     * Intercept the file input request from the WebView.
     * Called when the embedded app triggers a file picker (Open, Combine, Insert,
     * Replace, image import, etc.).
     */
    override fun onShowFileChooser(
        webView: WebView,
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams
    ): Boolean {
        // Complete any previous uncompleted callback with null before replacing it
        // (WebChromeClient contract requires every callback to be completed exactly once).
        uploadCallback?.onReceiveValue(null)
        uploadCallback = callback

        val acceptTypes = params.acceptTypes ?: emptyArray()
        val isMultiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE

        try {
            val intent = createFileChooserIntent(acceptTypes, isMultiple)
            if (isMultiple) {
                multiplePicker.launch(intent)
            } else {
                singlePicker.launch(intent)
            }
        } catch (e: ActivityNotFoundException) {
            uploadCallback = null
            callback.onReceiveValue(null)
            onFilePicked(null, false)
        }

        return true
    }

    internal fun createFileChooserIntent(acceptTypes: Array<String>, isMultiple: Boolean): Intent {
        val normalizedMimeTypes = normalizeAcceptTypes(acceptTypes)
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            when {
                normalizedMimeTypes.isEmpty() -> {
                    type = "application/pdf"
                }
                normalizedMimeTypes.size == 1 -> {
                    type = normalizedMimeTypes[0]
                }
                else -> {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, normalizedMimeTypes)
                }
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultiple)
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
        }
    }

    /**
     * Convert HTML <input accept="..."> tokens (which in upstream rfd are often file
     * extensions like ".pdf" or comma-separated lists like ".png,.jpg,image/webp")
     * into valid Android MIME types suitable for ACTION_OPEN_DOCUMENT.
     */
    internal fun normalizeAcceptTypes(acceptTypes: Array<String>): Array<String> {
        val resolved = linkedSetOf<String>()
        for (rawGroup in acceptTypes) {
            for (token in rawGroup.split(',')) {
                val trimmed = token.trim().lowercase()
                if (trimmed.isEmpty()) continue
                when {
                    trimmed == "*/*" || trimmed == "*" || trimmed == ".*" -> {
                        return arrayOf("*/*")
                    }
                    trimmed.contains('/') -> {
                        resolved.add(trimmed)
                    }
                    trimmed.startsWith('.') -> {
                        val mime = LocalContentServer.getMimeType("file$trimmed")
                            .takeIf { it != "application/octet-stream" }
                            ?: MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(trimmed.removePrefix("."))
                        if (mime != null) {
                            resolved.add(mime)
                        } else {
                            // Unknown extension requested: widen to */* so Android's
                            // DocumentsUI does not grey out the target file.
                            return arrayOf("*/*")
                        }
                    }
                    else -> {
                        val mime = LocalContentServer.getMimeType("file.$trimmed")
                            .takeIf { it != "application/octet-stream" }
                        if (mime != null) resolved.add(mime)
                    }
                }
            }
        }
        return resolved.toTypedArray()
    }

    private fun handlePickerResult(resultCode: Int, data: Intent?) {
        val callback = uploadCallback ?: return
        uploadCallback = null

        if (resultCode == Activity.RESULT_OK && data != null) {
            val uris: Array<Uri> = when {
                data.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i)?.uri }
                        .filterNotNull()
                        .filter { it.scheme == "content" }
                        .toTypedArray()
                }
                data.data != null -> {
                    val uri = data.data!!
                    if (uri.scheme == "content") arrayOf(uri) else emptyArray()
                }
                else -> emptyArray()
            }

            if (uris.isEmpty()) {
                callback.onReceiveValue(null)
                onFilePicked(null, false)
            } else {
                for (uri in uris) {
                    DocumentIo.takeReadPermission(activity, uri)
                }
                callback.onReceiveValue(uris)
                onFilePicked(uris, true)
            }
        } else {
            callback.onReceiveValue(null)
            onFilePicked(null, true)
        }
    }

    /**
     * Clean up the callback when the activity is destroyed.
     * Ensures the callback is completed (with null) if the picker was dismissed
     * by activity destruction rather than user action.
     */
    fun cleanup() {
        uploadCallback?.onReceiveValue(null)
        uploadCallback = null
    }
}
