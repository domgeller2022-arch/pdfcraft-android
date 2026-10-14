package com.domgeller.pdfcraft

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
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
         * MIME type map for web assets. Critical for WASM to load as
         * application/wasm and JS as text/javascript.
         */
        private val MIME_TYPES = mapOf(
            ".html" to "text/html; charset=utf-8",
            ".css" to "text/css; charset=utf-8",
            ".js" to "text/javascript; charset=utf-8",
            ".wasm" to "application/wasm",
            ".json" to "application/json; charset=utf-8",
            ".svg" to "image/svg+xml; charset=utf-8",
            ".png" to "image/png",
            ".jpg" to "image/jpeg",
            ".jpeg" to "image/jpeg",
            ".gif" to "image/gif",
            ".woff" to "font/woff",
            ".woff2" to "font/woff2",
            ".ttf" to "font/ttf",
            ".ico" to "image/x-icon",
            ".map" to "application/json; charset=utf-8"
        )

        /**
         * Get the MIME type for a file path based on extension.
         */
        fun getMimeType(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return MIME_TYPES[".$ext"] ?: "application/octet-stream"
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
        val assetsHandler = WebViewAssetLoader.AssetsPathHandler(context)
        return WebViewAssetLoader.Builder()
            .addPathHandler("/shell/", assetsHandler)
            .addPathHandler("/pdfcraft/", assetsHandler)
            .addPathHandler("/", assetsHandler)
            .build()
    }

    /**
     * Intercept a URL and serve the local asset response.
     * Returns a WebResourceResponse or null if the URL is not a local asset.
     * This prevents falling through to network access for unknown paths.
     */
    fun shouldInterceptUrl(url: String): String? {
        if (!url.startsWith(ASSET_ORIGIN)) return null
        // If the URL is not under /assets/shell/ or /assets/pdfcraft/,
        // return a local error to prevent network access.
        if (!url.startsWith("$ASSET_ORIGIN/assets/shell/") &&
            !url.startsWith("$ASSET_ORIGIN/assets/pdfcraft/")
        ) {
            return "$ASSET_ORIGIN/assets/shell/error.html"
        }
        return null
    }

    /**
     * Check if a web build exists in the APK assets.
     * Used at startup to detect missing/stale builds.
     */
    fun hasWebBuild(): Boolean {
        return try {
            val list = context.assets.list("pdfcraft")
            list?.isNotEmpty() ?: false
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
