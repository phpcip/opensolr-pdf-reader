package com.opensolr.pdf.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** On-device OCR of one page, only when a long press lands on a page that has no text layer. Nothing leaves the phone. */
object Ocr {

    private const val LANGS = "eng+ron"
    private val lock = Mutex()
    private var api: TessBaseAPI? = null

    /** The models ship in the APK and are copied once next to the app's files, where Tesseract reads them. */
    private fun dataDir(context: Context): File {
        val root = File(context.filesDir, "ocr")
        val dir = File(root, "tessdata").apply { mkdirs() }
        for (name in listOf("eng.traineddata", "ron.traineddata")) {
            val out = File(dir, name)
            val size = context.assets.openFd("tessdata/$name").use { it.length }
            if (out.length() == size) continue
            val tmp = File(dir, "$name.tmp")
            context.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(out)
        }
        return root
    }

    /**
     * A photo of paper reads badly as it is: dark, lit unevenly. The page goes grey, its lighting is evened out
     * against a blurred copy of itself with the text taken out, and the contrast is stretched.
     */
    private fun prepare(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        // the paper's light: the page shrunk 24 times, the text taken out with a max filter, then blown up smooth
        val sw = (w / 24).coerceAtLeast(1)
        val sh = (h / 24).coerceAtLeast(1)
        var small = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0
            var n = 0
            val y0 = y * h / sh
            val y1 = ((y + 1) * h / sh).coerceAtMost(h)
            val x0 = x * w / sw
            val x1 = ((x + 1) * w / sw).coerceAtMost(w)
            var yy = y0
            while (yy < y1) { var xx = x0; while (xx < x1) { sum += gray[yy * w + xx]; n++; xx += 2 }; yy += 2 }
            small[y * sw + x] = if (n > 0) sum / n else 255
        }
        repeat(2) {
            val next = IntArray(sw * sh)
            for (y in 0 until sh) for (x in 0 until sw) {
                var m = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = (y + dy).coerceIn(0, sh - 1)
                    val xx = (x + dx).coerceIn(0, sw - 1)
                    m = maxOf(m, small[yy * sw + xx])
                }
                next[y * sw + x] = m
            }
            small = next
        }
        val bgSmall = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        bgSmall.setPixels(IntArray(sw * sh) { (0xFF shl 24) or (small[it] * 0x010101) }, 0, sw, 0, 0, sw, sh)
        val bg = Bitmap.createScaledBitmap(bgSmall, w, h, true)
        val bgPx = IntArray(w * h)
        bg.getPixels(bgPx, 0, w, 0, 0, w, h)
        bgSmall.recycle()
        bg.recycle()
        val hist = IntArray(256)
        for (i in gray.indices) {
            val b = (bgPx[i] and 0xFF).coerceAtLeast(1)
            val v = (gray[i] * 255 / b).coerceIn(0, 255)
            gray[i] = v
            hist[v]++
        }
        // the darkest 1% becomes black, the paper white
        var lo = 0
        var acc = 0
        val cut = gray.size / 100
        while (lo < 254 && acc + hist[lo] < cut) { acc += hist[lo]; lo++ }
        val range = (255 - lo).coerceAtLeast(1)
        for (i in gray.indices) {
            val v = ((gray[i] - lo) * 255 / range).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (v * 0x010101)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** The characters of [page] with their boxes as fractions of the picture, y down, grouped into words. */
    suspend fun glyphs(context: Context, page: Bitmap): List<Glyph> = withContext(Dispatchers.Default) {
        lock.withLock {
            val tess = api ?: TessBaseAPI().also {
                if (!it.init(dataDir(context.applicationContext).absolutePath, LANGS, TessBaseAPI.OEM_LSTM_ONLY)) {
                    it.recycle()
                    return@withLock emptyList()
                }
                api = it
            }
            val image = prepare(page)
            // the page laid out on its own, and the page read as one block: whichever finds more text wins
            val auto = read(tess, image, TessBaseAPI.PageSegMode.PSM_AUTO)
            val block = read(tess, image, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            image.recycle()
            if (block.size > auto.size) block else auto
        }
    }

    private fun read(tess: TessBaseAPI, image: Bitmap, mode: Int): List<Glyph> {
        tess.setPageSegMode(mode)
        tess.setImage(image)
        tess.getUTF8Text()
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        val out = ArrayList<Glyph>()
        val it = tess.resultIterator ?: return out
        val symbol = TessBaseAPI.PageIteratorLevel.RIL_SYMBOL
        val word = TessBaseAPI.PageIteratorLevel.RIL_WORD
        val line = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
        var wordId = 0
        try {
            it.begin()
            do {
                val text = it.getUTF8Text(symbol)?.trim().orEmpty()
                val r = it.getBoundingRect(symbol)
                if (text.isNotEmpty() && r != null) {
                    val after = when {
                        it.isAtFinalElement(line, symbol) -> "\n"
                        it.isAtFinalElement(word, symbol) -> " "
                        else -> ""
                    }
                    out += Glyph(text, after, RectF(r.left / w, r.top / h, r.right / w, r.bottom / h), wordId)
                    if (after.isNotEmpty()) wordId++
                }
            } while (it.next(symbol))
        } finally {
            it.delete()
            tess.clear()
        }
        return out
    }
}
