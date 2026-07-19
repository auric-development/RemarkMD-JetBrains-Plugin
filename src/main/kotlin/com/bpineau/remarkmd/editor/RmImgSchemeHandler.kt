package com.bpineau.remarkmd.editor

import com.bpineau.remarkmd.core.EditorScript
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.File

/**
 * Serves a document's local images to the JCEF page over the `rmimg:` scheme.
 *
 * The page cannot fetch them itself: JCEF, like WKWebView, will not load `file:` subresources from a
 * page put up with `loadHTML`. So [EditorScript.imageUrl] rewrites every local `<img>` src to
 * `rmimg://local/<absolute-path>` and this reads the bytes in Kotlin.
 *
 * Unlike the Mac app, a JetBrains plugin is **not sandboxed** — the IDE can read any file the user
 * can — so there is no security-scoped-bookmark grant. The only safety here is path hygiene:
 * [EditorScript.fileForImageUrl] normalizes the path (collapsing any `..`), and the handler serves
 * only a regular, readable file, returning an empty 404 for anything else rather than crashing. The
 * security stage can tighten this to a directory allow-list; see NOTES.
 */
class RmImgSchemeHandlerFactory : CefSchemeHandlerFactory {
    override fun create(
        browser: CefBrowser?,
        frame: CefFrame?,
        schemeName: String?,
        request: CefRequest?,
    ): CefResourceHandler = RmImgResourceHandler()
}

private class RmImgResourceHandler : CefResourceHandler {
    private var data: ByteArray = ByteArray(0)
    private var offset = 0
    private var status = 404
    private var mimeType = "application/octet-stream"

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        val filePath = request?.url?.let { EditorScript.fileForImageUrl(it) }
        if (filePath != null) {
            try {
                val f = File(filePath)
                if (f.isFile && f.canRead()) {
                    data = f.readBytes()
                    mimeType = mimeTypeFor(f)
                    status = 200
                }
            } catch (e: Exception) {
                // Unreadable for any reason -> stay a 404 with an empty body. Never propagate.
                data = ByteArray(0)
                status = 404
            }
        }
        // We answer synchronously; tell CEF the response is ready.
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(
        response: CefResponse?,
        responseLength: IntRef?,
        redirectUrl: StringRef?,
    ) {
        response?.mimeType = mimeType
        response?.status = status
        responseLength?.set(data.size)
    }

    override fun readResponse(
        dataOut: ByteArray?,
        bytesToRead: Int,
        bytesRead: IntRef?,
        callback: CefCallback?,
    ): Boolean {
        if (dataOut == null || offset >= data.size) {
            bytesRead?.set(0)
            return false
        }
        val n = minOf(bytesToRead, data.size - offset)
        System.arraycopy(data, offset, dataOut, 0, n)
        offset += n
        bytesRead?.set(n)
        return true
    }

    override fun cancel() {
        // Nothing to release: the bytes are already in memory.
    }

    private fun mimeTypeFor(file: File): String =
        when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "tif", "tiff" -> "image/tiff"
            "avif" -> "image/avif"
            "apng" -> "image/apng"
            else -> runCatching { java.nio.file.Files.probeContentType(file.toPath()) }.getOrNull()
                ?: "application/octet-stream"
        }
}
