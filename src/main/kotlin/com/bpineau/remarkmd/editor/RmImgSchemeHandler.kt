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
 * Unlike the Mac app, a JetBrains plugin is **not sandboxed** — the IDE process can read any file
 * the user can — so a crafted document naming `rmimg://local/etc/passwd` would otherwise stream an
 * arbitrary file straight out of the reader's disk. A `.md` is untrusted input (it is the shared
 * handoff artifact, and Claude writes into it), so this handler confines every read to an allow-list
 * of roots registered by the open previews ([RmImgAccess]): the document's own folder and its
 * project. The check is done on the file's **canonical** path (`File.canonicalFile`), which resolves
 * `..` AND symlinks, so neither a `../../../etc/passwd` src nor a symlink planted inside the folder
 * can redirect the read outside an allowed root. Reads are also capped ([MAX_IMAGE_BYTES]) so a
 * document cannot point at a huge file and force an OOM on the CEF thread. Anything that fails a
 * check stays an empty 404 rather than crashing.
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
                    // Canonicalize (resolves .. AND symlinks) and confine to an allowed root BEFORE
                    // reading a byte. A path that escapes the allow-list, or a file over the size cap,
                    // stays a 404 — a malicious src cannot read /etc/passwd or OOM the CEF thread.
                    val real = f.canonicalFile
                    val len = real.length()
                    if (RmImgAccess.isAllowed(real.path) && len in 1..MAX_IMAGE_BYTES) {
                        data = real.readBytes()
                        mimeType = mimeTypeFor(real)
                        status = 200
                    }
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

/** No single image a document embeds has any business being larger than this (64 MB). */
private const val MAX_IMAGE_BYTES: Long = 64L * 1024 * 1024

/**
 * The set of directory roots the `rmimg:` handler is allowed to read from. A `.md` is untrusted, so
 * without this a crafted image src reads any file the IDE can. Each open preview registers its
 * document's folder and its project via [allow]; the global handler ([RmImgResourceHandler]) then
 * serves a file only if its canonical path sits under one of these roots.
 *
 * Images legitimately live in a folder that is a **sibling** of the document's (the Mac app's own
 * `../images/web/photo.jpg` convention), so the boundary cannot be the document's own folder alone —
 * it is the project. When a file is opened outside any project, only its own folder is trusted, which
 * is the safe default (a `../images` reference then declines rather than reading an arbitrary path).
 */
object RmImgAccess {
    // Canonical root paths. CopyOnWriteArraySet: registered on the EDT, read on the CEF IO thread.
    private val roots = java.util.concurrent.CopyOnWriteArraySet<String>()

    /** Trust [path] and everything beneath it. No-op for null/blank or an uncanonicalizable path. */
    fun allow(path: String?) {
        val p = path?.takeIf { it.isNotBlank() } ?: return
        val canon = try { File(p).canonicalPath } catch (e: Exception) { return }
        roots.add(canon)
    }

    /** True if [canonicalPath] (already canonical) sits under some allowed root. */
    fun isAllowed(canonicalPath: String): Boolean = pathIsUnderRoot(canonicalPath, roots)

    // Test seam: reset between cases.
    internal fun clearForTest() = roots.clear()
}

/**
 * Pure containment check: is [canonicalPath] equal to, or nested under, one of [roots]? Both sides
 * must already be canonical (symlinks and `..` resolved) — this is a lexical prefix test, guarded by
 * a separator so `/a/bcd` does not count as being under `/a/b`.
 */
fun pathIsUnderRoot(canonicalPath: String, roots: Collection<String>): Boolean {
    if (canonicalPath.isEmpty()) return false
    val sep = File.separator
    for (root in roots) {
        if (root.isEmpty()) continue
        if (canonicalPath == root) return true
        val prefix = if (root.endsWith(sep)) root else root + sep
        if (canonicalPath.startsWith(prefix)) return true
    }
    return false
}
