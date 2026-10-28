package com.opensolr.pdf.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.util.AlreadyClosedBehavior
import io.legere.pdfiumandroid.util.Config
import io.legere.pdfiumandroid.DefaultLogger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * One character of a page: its text, what follows it (a space, a line break or nothing), its box as fractions of the
 * page with y down, and the word it belongs to (long press selects the whole word, the handles move by character).
 */
class Glyph(val text: String, val after: String, val box: RectF, val word: Int)

/** One open PDF. PDFium is used from a single thread, so every call goes through [thread]. */
class PdfDoc private constructor(
    private val doc: PdfDocument,
    private val fd: ParcelFileDescriptor,
    /** height / width of every page */
    val ratios: FloatArray,
) : Closeable {

    val pageCount: Int get() = ratios.size

    class PasswordProtected : Exception()

    companion object {
        val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "pdfium").apply { priority = Thread.NORM_PRIORITY } }.asCoroutineDispatcher()

        private var core: PdfiumCore? = null

        suspend fun open(context: Context, uri: Uri): PdfDoc = withContext(thread) {
            val pdfium = core ?: PdfiumCore(context.applicationContext, Config(DefaultLogger(), AlreadyClosedBehavior.IGNORE)).also { core = it }
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("no file")
            val doc = try {
                pdfium.newDocument(fd)
            } catch (e: PdfPasswordException) {
                fd.close()
                throw PasswordProtected()
            } catch (e: Exception) {
                fd.close()
                throw e
            }
            val n = doc.getPageCount()
            val ratios = FloatArray(n) { i ->
                doc.openPage(i).use { p ->
                    val w = p.getPageWidthPoint().coerceAtLeast(1)
                    p.getPageHeightPoint().coerceAtLeast(1).toFloat() / w
                }
            }
            PdfDoc(doc, fd, ratios)
        }
    }

    /**
     * Draws page [index] into [bitmap]: the whole page is [drawW] x [drawH] pixels and the bitmap shows the part of it
     * that starts at ([startX], [startY]) (zero or negative), so a zoomed page is drawn only where it is on screen.
     */
    suspend fun render(index: Int, bitmap: Bitmap, startX: Int, startY: Int, drawW: Int, drawH: Int) = withContext(thread) {
        if (doc.isClosed) return@withContext
        bitmap.eraseColor(Color.WHITE)
        doc.openPage(index).use { it.renderPageBitmap(bitmap, startX, startY, drawW, drawH, true, false, Color.WHITE, Color.WHITE) }
    }

    /** The characters of page [index] from its own text layer; empty for a page that is a picture. */
    suspend fun glyphs(index: Int): List<Glyph> = withContext(thread) {
        if (doc.isClosed) return@withContext emptyList()
        doc.openPage(index).use { page ->
            val w = page.getPageWidthPoint().toFloat().coerceAtLeast(1f)
            val h = page.getPageHeightPoint().toFloat().coerceAtLeast(1f)
            page.openTextPage().use { text ->
                val n = text.textPageCountChars()
                if (n <= 0) return@use emptyList<Glyph>()
                val all = text.textPageGetText(0, n) ?: return@use emptyList<Glyph>()
                val out = ArrayList<Glyph>()
                var word = 0
                var gap = false
                for (i in 0 until minOf(n, all.length)) {
                    val c = all[i]
                    if (c == '\r' || c == '\u0000') continue
                    if (c.isWhitespace() || c == '\u0002') {
                        // what separates this character from the next one: a line break wins over a space
                        if (out.isNotEmpty()) {
                            val last = out.last()
                            val after = if (c == '\n' || last.after == "\n") "\n" else " "
                            if (last.after != after) out[out.size - 1] = Glyph(last.text, after, last.box, last.word)
                        }
                        gap = true
                        continue
                    }
                    val r = text.textPageGetCharBox(i) ?: continue
                    if (gap) { word++; gap = false }
                    val top = maxOf(r.top, r.bottom)
                    val bottom = minOf(r.top, r.bottom)
                    out += Glyph(c.toString(), "", RectF(minOf(r.left, r.right) / w, 1f - top / h, maxOf(r.left, r.right) / w, 1f - bottom / h), word)
                }
                out
            }
        }
    }

    override fun close() {
        try {
            doc.close()
        } catch (_: Exception) {
        }
        try {
            fd.close()
        } catch (_: Exception) {
        }
    }
}
