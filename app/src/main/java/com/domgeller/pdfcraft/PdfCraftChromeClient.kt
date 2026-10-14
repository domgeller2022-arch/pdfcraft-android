package com.domgeller.pdfcraft

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * PdfCraftChromeClient — handles file picker requests from the embedded web app.
 *
 * The existing PdfCraft app uses `rfd` browser file pickers. In the browser, these
 * appear as HTML <input type="file"> elements, which the WebView routes through
 * onShowFileChooser. We launch the Android Storage Access Framework document picker
 * and return the selected content:// URIs to the original WebView callback.
 *
 * Key rules (per plan §6):
 * - Preserve original chooser accept types and single/multiple mode
 * - Validate returned content:// URIs
 * - Return actual URIs, not filesystem paths
 * - Complete each callback exactly once
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
     * Intercept the file input request from the WebView.
     * Called when the embedded app triggers a file picker (Open, Combine, Insert,
     * Replace, image import, etc.).
     */
    override fun onShowFileChooser(
        webView: WebView,
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams
    ): Boolean {
        // Store the callback so we can complete it when the picker returns
        uploadCallback = callback

        // Read the accept types and multiple flag from the original request
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
            // No activity can handle the intent — complete the callback with null
            callback.onReceiveValue(null)
            uploadCallback = null
        }

        return true
    }

    private fun createFileChooserIntent(acceptTypes: Array<String>, isMultiple: Boolean): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Support PDFs and other formats the existing app requests
            if (acceptTypes.isNotEmpty()) {
                // Convert accept types to MIME types
                val mimeTypes = acceptTypes.filter { it.isNotBlank() }.toTypedArray()
                if (mimeTypes.isNotEmpty()) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    setType("*/*")
                } else {
                    setType("application/pdf")
                }
            } else {
                setType("application/pdf")
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultiple)
            putExtra(Intent.EXTRA_LOCAL_ONLY, false) // Allow remote providers too
        }
        return intent
    }

    private fun handlePickerResult(resultCode: Int, data: Intent?) {
        val callback = uploadCallback
        uploadCallback = null

        if (callback == null) {
            // Callback was already completed or cleared — don't deliver a duplicate result
            return
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            val uris: Array<Uri> = when {
                data.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i ->
                        data.clipData!!.getItemAt(i).uri
                    }.filterNotNull().filter { it.scheme == "content" }.toTypedArray()
                }
                data.data != null -> {
                    val uri = data.data!!
                    if (uri.scheme == "content") arrayOf(uri) else emptyArray()
                }
                else -> emptyArray()
            }

            if (uris.isEmpty()) {
                callback.onReceiveValue(null)
            } else {
                // Take persistent read permissions for the selected URIs
                for (uri in uris) {
                    try {
                        activity.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        // Not a document provider URI — ignore, the URI is still usable
                    }
                }
                callback.onReceiveValue(uris)
            }
        } else {
            // User cancelled: return null per the contract
            callback.onReceiveValue(null)
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
