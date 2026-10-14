package com.domgeller.pdfcraft

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.IOException

/**
 * LocalContentServer — serves bundled PdfCraft web assets over an HTTPS origin
 * via WebViewAssetLoader.
 *
 * This gives the embedded web app a stable, same-origin HTTPS URL so that:
 * 1. The iframe's ?host=parent bridge works (same-origin requirement)
 * 2. eframe/Glow WebGL and Canvas APIs function correctly
 * 3. Service workers, localStorage, and cookies work normally
 * 4. Preferences remain associated with the same origin across updates
 *
 * Assets are copied from build/web/ into the APK's assets/pdfcraft/ directory
 * by a Gradle build task. This class never accesses the upstream repository
 * directly.
 */
class LocalContentServer(private val context: Context) {

    companion object {
        const val TAG = "LocalContentServer"

        /**
         * The HTTPS origin that WebViewAssetLoader assigns to local assets.
         * Matches https://appassets.androidplatform.net (Android's built-in domain).
         */
        const val ASSET_ORIGIN = "https://appassets.androidplatform.net"

        /**
         * The host page URL served at the asset origin.
         */
        const val HOST_URL = "$ASSET_ORIGIN/assets/shell/host.html"

        /**
         * MIME type map for web assets and exported document types.
         * Critical for WASM to load as application/wasm and JS as text/javascript,
         * and for non-PDF exports (.png, .jpg, .csv, .xfdf, .txt, .json) to carry
         * accurate MIME types into the Android document picker and Share sheet.
         */
        private val MIME_TYPES = mapOf(
            ".html" to "text/html",
            ".css" to "text/css",
            ".js" to "text/javascript",
            ".wasm" to "application/wasm",
            ".json" to "application/json",
            ".svg" to "image/svg+xml",
            ".png" to "image/png",
            ".jpg" to "image/jpeg",
            ".jpeg" to "image/jpeg",
            ".gif" to "image/gif",
            ".webp" to "image/webp",
            ".bmp" to "image/bmp",
            ".tif" to "image/tiff",
            ".tiff" to "image/tiff",
            ".woff" to "font/woff",
            ".woff2" to "font/woff2",
            ".ttf" to "font/ttf",
            ".ico" to "image/x-icon",
            ".map" to "application/json",
            ".pdf" to "application/pdf",
            ".pdfa" to "application/pdf",
            ".pdfx" to "application/pdf",
            ".pdfua" to "application/pdf",
            ".txt" to "text/plain",
            ".text" to "text/plain",
            ".csv" to "text/csv",
            ".xfdf" to "application/vnd.adobe.xfdf",
            ".xml" to "application/xml",
            ".cer" to "application/x-x509-ca-cert",
            ".crt" to "application/x-x509-ca-cert",
            ".p12" to "application/x-pkcs12",
            ".pfx" to "application/x-pkcs12",
            ".rtf" to "application/rtf",
            ".docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ".pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        )

        /**
         * Get the MIME type for a file path or filename based on extension.
         */
        fun getMimeType(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return "application/octet-stream"
            return MIME_TYPES[".$ext"]
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }

        /**
         * Resolve the best MIME type for an exported file, preferring extension-based
         * resolution when a generic or default application/pdf label was attached to
         * a non-PDF filename (such as .png, .csv, .xfdf, or .txt).
         */
        fun resolveExportMimeType(filename: String, reportedMimeType: String?): String {
            val byName = getMimeType(filename)
            val cleanedReported = reportedMimeType?.trim()?.lowercase().orEmpty()
            return when {
                byName != "application/octet-stream" && byName != "application/pdf" -> byName
                cleanedReported.isNotEmpty() && cleanedReported != "application/octet-stream" -> cleanedReported
                byName != "application/octet-stream" -> byName
                else -> "application/pdf"
            }
        }
    }

    /**
     * Create the WebViewAssetLoader that serves local assets over HTTPS.
     * Paths are mapped to the APK assets directory:
     * - /assets/shell/ → host page, host.js, download-adapter.js
     * - /assets/pdfcraft/ → Trunk-generated web build (index.html, .wasm, .js, etc.)
     *
     * Unknown paths under the asset origin return a local error response,
     * preventing fallback to network access.
     */
    fun createAssetLoader(): WebViewAssetLoader {
        val assetsHandler = WebViewAssetLoader.PathHandler { path ->
            if (!(path.startsWith("shell/") || path.startsWith("pdfcraft/")) ||
                path.split('/').any { it == "." || it == ".." }) {
                notFound()
            } else {
                try {
                    val stream = context.assets.open(path)
                    val mime = getMimeType(path)
                    val encoding = if (mime.startsWith("text/") ||
                        mime == "application/json" || mime == "image/svg+xml") "UTF-8" else null
                    WebResourceResponse(
                        mime,
                        encoding,
                        200,
                        "OK",
                        mapOf("Cache-Control" to "no-cache"),
                        stream
                    )
                } catch (error: IOException) {
                    Log.e(TAG, "Missing bundled asset: $path", error)
                    notFound()
                }
            }
        }
        return WebViewAssetLoader.Builder()
            // Only /assets/ is removed. The remaining shell/... or pdfcraft/...
            // is the actual path relative to the APK's assets directory.
            .addPathHandler("/assets/", assetsHandler)
            .build()
    }

    fun interceptRequest(loader: WebViewAssetLoader, uri: Uri): WebResourceResponse? {
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" ||
            (uri.port != -1 && uri.port != 443)) return null
        // Unknown local paths must not fall back to network access.
        return loader.shouldInterceptRequest(uri) ?: notFound()
    }

    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        emptyMap(),
        "Bundled resource not found".byteInputStream(Charsets.UTF_8)
    )

    /**
     * Check if a web build exists in the APK assets.
     * Used at startup to detect missing/stale builds.
     */
    fun hasWebBuild(): Boolean {
        return try {
            val list = context.assets.list("pdfcraft")
            list != null && list.contains("index.html") && list.any { it.endsWith(".wasm") }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the list of files in the bundled web build for verification.
     */
    fun listWebBuildFiles(): Array<String> {
        return try {
            context.assets.list("pdfcraft") ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
    }
}
