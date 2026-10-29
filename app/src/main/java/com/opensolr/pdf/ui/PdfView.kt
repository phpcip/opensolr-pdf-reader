package com.opensolr.pdf.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.os.Build
import android.view.View
import android.widget.Magnifier
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.opensolr.pdf.doc.Ocr
import com.opensolr.pdf.doc.PdfDoc
import com.opensolr.pdf.doc.Glyph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The pages, one under the other, the width of the screen. Pinch and double tap zoom, drag and fling move the
 * pages with inertia; a zoomed page is drawn again at screen resolution once it stops moving. A long press selects
 * a word; its two handles then move by character, across pages, with a magnifier under the finger. The text comes
 * from the page's text layer or, on a page that is a picture, from OCR run then on that page only.
 */
@SuppressLint("ViewConstructor")
class PdfView(context: Context) : View(context) {

    /** What the screen around the pages hears from them. */
    interface Listener {
        fun onTap()
        fun onPage(page: Int, count: Int)
        /** The selected text and where it is on screen (top, bottom), or null text when nothing is selected. */
        fun onSelection(text: String?, top: Float, bottom: Float)
        fun onBusy(busy: Boolean)
        fun onNoText()
        fun onLongPress()
    }

    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val density = resources.displayMetrics.density
    private val gap = 3f * density

    var doc: PdfDoc? = null
        set(value) {
            if (field === value) return
            field = value
            reset()
        }

    // layout, in document units: the width of the view at scale 1
    private var docW = 0f
    private var tops = FloatArray(0)
    private var heights = FloatArray(0)
    private var total = 0f

    private var scale = 1f
    private var ox = 0f
    private var oy = 0f

    private val maxScale = 8f

    private val base = object : LruCache<Int, Bitmap>(baseBudget()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<Int>()

    private class Detail(val docRect: RectF, val bitmap: Bitmap)

    private val details = HashMap<Int, Detail>()
    private var detailGen = 0

    private val glyphs = HashMap<Int, List<Glyph>>()
    private val loading = HashSet<Int>()

    /** A place in the text: a page and a character on it. */
    private data class Pos(val page: Int, val idx: Int) : Comparable<Pos> {
        override fun compareTo(other: Pos) = if (page != other.page) page.compareTo(other.page) else idx.compareTo(other.idx)
    }

    private var selStart: Pos? = null
    private var selEnd: Pos? = null
    private val hasSelection get() = selStart != null && selEnd != null

    // 0 = nothing, 1 = the start handle, 2 = the end handle (the long press drags the end one)
    private var dragging = 0
    private var dragDX = 0f
    private var dragDY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var currentPage = -1

    private val handleR = 11f * density
    private var magnifier: Magnifier? = null

    private val bg = Paint().apply { color = Color.parseColor("#E9E6E1") }
    private val white = Paint().apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint().apply { color = Color.parseColor("#55C05520") }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C05520") }

    private val scroller = OverScroller(context)
    private var animator: ValueAnimator? = null

    private fun baseBudget(): Int = (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(128L * 1024 * 1024).toInt()

    private fun reset() {
        base.evictAll()
        pending.clear()
        details.clear()
        glyphs.clear()
        loading.clear()
        selStart = null
        selEnd = null
        scale = 1f
        ox = 0f
        oy = 0f
        currentPage = -1
        relayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // keep the same place in the document when the screen turns
        val anchor = if (total > 0) (oy + oldh / 2f) / (total * scale) else 0f
        base.evictAll()
        details.clear()
        relayout()
        if (total > 0) oy = anchor * total * scale - h / 2f
        clamp()
        invalidate()
    }

    private fun relayout() {
        val d = doc
        docW = width.toFloat()
        if (d == null || docW <= 0f) {
            tops = FloatArray(0); heights = FloatArray(0); total = 0f
            return
        }
        val n = d.pageCount
        tops = FloatArray(n)
        heights = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            tops[i] = y
            heights[i] = docW * d.ratios[i]
            y += heights[i] + gap
        }
        total = max(0f, y - gap)
        clamp()
    }

    private fun clamp() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        ox = ox.coerceIn(0f, max(0f, docW * scale - vw))
        val contentH = total * scale
        oy = if (contentH <= vh) (contentH - vh) / 2f else oy.coerceIn(0f, contentH - vh)
    }

    // ---------------- drawing ----------------

    private fun pageScreen(i: Int, out: RectF): RectF {
        out.set(-ox, tops[i] * scale - oy, docW * scale - ox, (tops[i] + heights[i]) * scale - oy)
        return out
    }

    private fun visibleRange(): IntRange {
        if (tops.isEmpty()) return IntRange.EMPTY
        val top = (oy) / scale
        val bottom = (oy + height) / scale
        var first = tops.indexOfLast { it <= top }.coerceAtLeast(0)
        while (first < tops.size - 1 && tops[first] + heights[first] < top) first++
        var last = first
        while (last < tops.size - 1 && tops[last + 1] < bottom) last++
        return first..last
    }

    private val rect = RectF()
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        if (doc == null || tops.isEmpty()) return
        val range = visibleRange()
        for (i in range) {
            val r = pageScreen(i, rect)
            canvas.drawRect(r, white)
            val b = base.get(i)
            if (b != null) canvas.drawBitmap(b, null, r, bitmapPaint) else requestBase(i)
            if (scale > 1.01f) details[i]?.let { d ->
                dst.set(d.docRect.left * scale - ox, d.docRect.top * scale - oy, d.docRect.right * scale - ox, d.docRect.bottom * scale - oy)
                canvas.drawBitmap(d.bitmap, null, dst, bitmapPaint)
            }
            drawSelection(canvas, i, r)
        }
        // the neighbours are ready before they scroll in
        if (!range.isEmpty()) {
            if (range.first > 0) requestBase(range.first - 1)
            if (range.last < tops.size - 1) requestBase(range.last + 1)
        }
        drawHandles(canvas)
        val mid = pageAt(height / 2f)
        if (mid >= 0 && mid != currentPage) {
            currentPage = mid
            listener?.onPage(mid, tops.size)
        }
    }

    /** The range of characters of page [i] inside the selection, or null. */
    private fun rangeOn(i: Int): IntRange? {
        val a = selStart ?: return null
        val b = selEnd ?: return null
        if (i < a.page || i > b.page) return null
        val list = glyphs[i] ?: return null
        if (list.isEmpty()) return null
        val from = if (i == a.page) a.idx else 0
        val to = if (i == b.page) b.idx else list.size - 1
        return if (from <= to) from..to else null
    }

    private fun glyphScreen(g: Glyph, page: RectF, out: RectF): RectF {
        out.set(page.left + g.box.left * page.width(), page.top + g.box.top * page.height(), page.left + g.box.right * page.width(), page.top + g.box.bottom * page.height())
        return out
    }

    /** The selection drawn line by line: one even band per line, from its first selected character to its last. */
    private fun drawSelection(canvas: Canvas, i: Int, page: RectF) {
        val range = rangeOn(i) ?: return
        val list = glyphs[i] ?: return
        var k = range.first
        while (k <= range.last) {
            var top = list[k].box.top
            var bottom = list[k].box.bottom
            var left = list[k].box.left
            var right = list[k].box.right
            var j = k
            while (j < range.last) {
                val cur = list[j]
                val next = list[j + 1]
                if (cur.after == "\n") break
                // a character that does not overlap the line vertically starts a new line
                val overlap = minOf(bottom, next.box.bottom) - maxOf(top, next.box.top)
                if (overlap < 0.4f * minOf(bottom - top, next.box.bottom - next.box.top) || next.box.left < left) break
                j++
                top = minOf(top, next.box.top)
                bottom = maxOf(bottom, next.box.bottom)
                left = minOf(left, next.box.left)
                right = maxOf(right, next.box.right)
            }
            val pad = (bottom - top) * 0.15f
            dst.set(page.left + left * page.width(), page.top + (top - pad) * page.height(), page.left + right * page.width(), page.top + (bottom + pad) * page.height())
            canvas.drawRect(dst, highlight)
            k = j + 1
        }
    }

    /** Where the handles stand: the bottom left corner of the first character and the bottom right one of the last. */
    private fun handlePoint(pos: Pos, start: Boolean): Pair<Float, Float>? {
        val list = glyphs[pos.page] ?: return null
        val g = list.getOrNull(pos.idx) ?: return null
        val page = pageScreen(pos.page, RectF())
        glyphScreen(g, page, dst)
        return (if (start) dst.left else dst.right) to dst.bottom
    }

    private fun drawHandles(canvas: Canvas) {
        val a = selStart ?: return
        val b = selEnd ?: return
        handlePoint(a, true)?.let { (x, y) ->
            canvas.drawCircle(x - handleR, y + handleR, handleR, handlePaint)
            canvas.drawRect(x - handleR, y, x, y + handleR, handlePaint)
        }
        handlePoint(b, false)?.let { (x, y) ->
            canvas.drawCircle(x + handleR, y + handleR, handleR, handlePaint)
            canvas.drawRect(x, y, x + handleR, y + handleR, handlePaint)
        }
    }

    private fun near(i: Int): Boolean {
        val r = visibleRange()
        return !r.isEmpty() && i >= r.first - 2 && i <= r.last + 2
    }

    private fun requestBase(i: Int) {
        val d = doc ?: return
        if (i in pending || base.get(i) != null) return
        val w = docW.toInt()
        val h = heights[i].toInt()
        if (w <= 0 || h <= 0) return
        pending += i
        scope.launch {
            try {
                if (!near(i) || doc !== d) return@launch
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                d.render(i, bmp, 0, 0, w, h)
                if (doc === d && docW.toInt() == w) base.put(i, bmp)
                invalidate()
            } catch (_: Exception) {
            } finally {
                pending -= i
            }
        }
    }

    private val detailTask = Runnable { renderDetails() }

    private fun scheduleDetails() {
        // the pages stopped: the bar over the selection comes back where the selection now is
        publishSelection()
        removeCallbacks(detailTask)
        if (scale > 1.01f) postDelayed(detailTask, 120) else details.clear()
    }

    /** The visible part of each page at the resolution of the screen, once the pages stop moving. */
    private fun renderDetails() {
        val d = doc ?: return
        val gen = ++detailGen
        val s = scale
        for (i in visibleRange()) {
            val r = RectF(pageScreen(i, rect))
            val v = RectF(max(0f, r.left), max(0f, r.top), min(width.toFloat(), r.right), min(height.toFloat(), r.bottom))
            if (v.width() < 1f || v.height() < 1f) continue
            val docRect = RectF((v.left + ox) / s, (v.top + oy) / s, (v.right + ox) / s, (v.bottom + oy) / s)
            val bw = v.width().toInt()
            val bh = v.height().toInt()
            val startX = (r.left - v.left).toInt()
            val startY = (r.top - v.top).toInt()
            val drawW = r.width().toInt()
            val drawH = r.height().toInt()
            scope.launch {
                try {
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    d.render(i, bmp, startX, startY, drawW, drawH)
                    if (gen == detailGen && doc === d) {
                        details[i] = Detail(docRect, bmp)
                        invalidate()
                    }
                } catch (_: Exception) {
                }
            }
        }
        details.keys.retainAll(visibleRange().toSet())
    }

    // ---------------- gestures ----------------

    private fun pageAt(y: Float): Int {
        if (tops.isEmpty()) return -1
        val docY = (y + oy) / scale
        for (i in tops.indices) if (docY <= tops[i] + heights[i] + gap / 2f) return i
        return tops.size - 1
    }

    private fun moveBy(dx: Float, dy: Float) {
        ox += dx
        oy += dy
        clamp()
        invalidate()
    }

    private fun zoomTo(s: Float, fx: Float, fy: Float) {
        val ns = s.coerceIn(1f, maxScale)
        val docX = (fx + ox) / scale
        val docY = (fy + oy) / scale
        scale = ns
        ox = docX * ns - fx
        oy = docY * ns - fy
        clamp()
        invalidate()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) = scheduleDetails()
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            moveBy(distanceX, distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            val vh = height.toFloat()
            val contentH = total * scale
            val minY = if (contentH <= vh) oy.toInt() else 0
            val maxY = if (contentH <= vh) oy.toInt() else (contentH - vh).toInt()
            scroller.fling(ox.toInt(), oy.toInt(), -velocityX.toInt(), -velocityY.toInt(), 0, max(0f, docW * scale - width).toInt(), minY, maxY)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (hasSelection) clearSelection() else listener?.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scale < 1.5f) 2.5f else 1f
            animateZoom(target, e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) = startSelection(e.x, e.y)
    })

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        animator?.cancel()
        val from = scale
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomTo(from + (target - from) * (it.animatedValue as Float), fx, fy) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = scheduleDetails()
            })
            start()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            ox = scroller.currX.toFloat()
            oy = scroller.currY.toFloat()
            clamp()
            postInvalidateOnAnimation()
            if (scroller.isFinished) scheduleDetails()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            animator?.cancel()
            removeCallbacks(detailTask)
            pressed = false
            if (hasSelection) grabHandle(event.x, event.y)
        }
        if (dragging != 0) {
            lastX = event.x
            lastY = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragTo(event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag()
                    // the long press that started the drag is over for the gesture detector too
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    gestures.onTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            return true
        }
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressed = false
            if (scroller.isFinished) scheduleDetails()
            publishSelection()
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && hasSelection) {
            listener?.onSelection(null, 0f, 0f)
        }
        return true
    }

    // ---------------- selection ----------------

    private fun pointOnPage(page: Int, x: Float, y: Float): Pair<Float, Float> {
        val r = pageScreen(page, rect)
        return ((x - r.left) / r.width()) to ((y - r.top) / r.height())
    }

    private fun distance(g: Glyph, nx: Float, ny: Float): Float {
        val dx = if (nx < g.box.left) g.box.left - nx else if (nx > g.box.right) nx - g.box.right else 0f
        val dy = if (ny < g.box.top) g.box.top - ny else if (ny > g.box.bottom) ny - g.box.bottom else 0f
        return hypot(dx, dy)
    }

    private fun nearest(list: List<Glyph>, nx: Float, ny: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((k, g) in list.withIndex()) {
            val dx = if (nx < g.box.left) g.box.left - nx else if (nx > g.box.right) nx - g.box.right else 0f
            val dy = if (ny < g.box.top) g.box.top - ny else if (ny > g.box.bottom) ny - g.box.bottom else 0f
            // being on the right line matters more than being near the character
            val d = hypot(dx, dy * 3f)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** The characters of [page]: its text layer, or OCR when the page is a picture. Null while they are read. */
    private fun glyphsOf(page: Int, then: (() -> Unit)? = null): List<Glyph>? {
        glyphs[page]?.let { return it }
        val d = doc ?: return null
        if (!loading.add(page)) return null
        scope.launch {
            var list = try { d.glyphs(page) } catch (_: Exception) { emptyList() }
            if (list.isEmpty()) {
                listener?.onBusy(true)
                list = try {
                    val w = (docW * 2f).coerceIn(1600f, 2480f).toInt()
                    val h = (w * d.ratios[page]).toInt().coerceAtMost(w * 4)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    d.render(page, bmp, 0, 0, w, h)
                    Ocr.glyphs(context, bmp).also { bmp.recycle() }
                } catch (_: Throwable) {
                    emptyList()
                } finally {
                    listener?.onBusy(false)
                }
            }
            loading.remove(page)
            if (doc !== d) return@launch
            glyphs[page] = list
            invalidate()
            then?.invoke()
        }
        return null
    }

    private fun startSelection(x: Float, y: Float) {
        val page = pageAt(y)
        if (page < 0) return
        val (nx, ny) = pointOnPage(page, x, y)
        listener?.onLongPress()
        val pick = pick@{ list: List<Glyph> ->
            if (list.isEmpty()) { listener?.onNoText(); return@pick }
            val k = nearest(list, nx, ny)
            // nothing written where the finger pressed: no selection somewhere else on the page
            val g = list.getOrNull(k)
            if (g == null || distance(g, nx, ny) > 0.04f) { listener?.onNoText(); return@pick }
            val w = g.word
            var a = k
            var b = k
            while (a > 0 && list[a - 1].word == w) a--
            while (b < list.size - 1 && list[b + 1].word == w) b++
            selStart = Pos(page, a)
            selEnd = Pos(page, b)
            invalidate()
            // the finger that pressed now drags the end of the selection
            if (dragging == 0 && pressed) {
                dragging = 2
                dragDX = 0f
                dragDY = 0f
                showMagnifier(lastX, lastY)
            } else {
                publishSelection()
            }
        }
        pressed = true
        lastX = x
        lastY = y
        val list = glyphsOf(page) { glyphs[page]?.let { if (selStart == null) pick(it) } }
        if (list != null) pick(list)
    }

    private var pressed = false

    /** A finger that lands on a handle drags it instead of the pages. */
    private fun grabHandle(x: Float, y: Float) {
        val a = selStart ?: return
        val b = selEnd ?: return
        val reach = handleR * 2.6f
        handlePoint(b, false)?.let { (hx, hy) ->
            if (hypot(x - (hx + handleR), y - (hy + handleR)) < reach) { dragging = 2; dragDX = x - hx; dragDY = y - hy }
        }
        if (dragging == 0) handlePoint(a, true)?.let { (hx, hy) ->
            if (hypot(x - (hx - handleR), y - (hy + handleR)) < reach) { dragging = 1; dragDX = x - hx; dragDY = y - hy }
        }
        if (dragging != 0) {
            listener?.onSelection(null, 0f, 0f)
            showMagnifier(x, y)
        }
    }

    /** Moves the handle being dragged to the character under the finger, on whichever page it is. */
    private fun dragTo(x: Float, y: Float) {
        val tx = x - dragDX
        // the handle hangs under the text: aim at the line just above its corner
        val ty = y - dragDY - 6f * density
        val page = pageAt(ty.coerceIn(0f, height.toFloat()))
        val list = glyphsOf(page) ?: return
        if (list.isEmpty()) return
        val (nx, ny) = pointOnPage(page, tx, ty)
        val k = nearest(list, nx.coerceIn(-0.05f, 1.05f), ny.coerceIn(-0.05f, 1.05f))
        if (k < 0) return
        val p = Pos(page, k)
        if (dragging == 2) selEnd = p else selStart = p
        val a = selStart!!
        val b = selEnd!!
        if (a > b) {
            selStart = b
            selEnd = a
            dragging = if (dragging == 2) 1 else 2
        }
        showMagnifier(tx, ty)
        edgeScroll()
        invalidate()
    }

    private val edgeTask = object : Runnable {
        override fun run() {
            if (dragging == 0) return
            val zone = 56f * density
            val step = when {
                lastY < zone -> -(zone - lastY) / 3f
                lastY > height - zone -> (lastY - (height - zone)) / 3f
                else -> 0f
            }
            if (step != 0f) {
                val before = oy
                moveBy(0f, step)
                if (oy != before) dragTo(lastX, lastY)
                postOnAnimation(this)
            }
        }
    }

    /** Near the top or bottom edge the pages scroll on their own, so a selection can run across pages. */
    private fun edgeScroll() {
        removeCallbacks(edgeTask)
        postOnAnimation(edgeTask)
    }

    private fun endDrag() {
        dragging = 0
        pressed = false
        removeCallbacks(edgeTask)
        magnifier?.dismiss()
        publishSelection()
    }

    private fun showMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val m = magnifier ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Magnifier.Builder(this).setInitialZoom(1.6f).setSize((140 * density).toInt(), (48 * density).toInt()).setCornerRadius(2 * density).build()
        } else {
            @Suppress("DEPRECATION") Magnifier(this)
        }).also { magnifier = it }
        m.show(x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()))
    }

    private fun publishSelection() {
        if (dragging != 0) return
        val a = selStart ?: return
        val b = selEnd ?: return
        val pa = handlePoint(a, true)
        val pb = handlePoint(b, false)
        val top = pa?.let { (_, y) -> y - (glyphs[a.page]?.getOrNull(a.idx)?.let { g -> (g.box.bottom - g.box.top) * heights[a.page] * scale } ?: 0f) } ?: 0f
        val bottom = (pb?.second ?: height.toFloat()) + handleR * 2
        listener?.onSelection(selectedText(), top, bottom)
    }

    private fun selectedText(): String {
        val a = selStart ?: return ""
        val b = selEnd ?: return ""
        val sb = StringBuilder()
        for (p in a.page..b.page) {
            val list = glyphs[p] ?: continue
            if (list.isEmpty()) continue
            val from = if (p == a.page) a.idx else 0
            val to = if (p == b.page) b.idx else list.size - 1
            if (p > a.page && sb.isNotEmpty()) sb.append('\n')
            for (k in from..to) {
                sb.append(list[k].text)
                if (k < to) sb.append(list[k].after)
            }
        }
        return sb.toString()
    }

    /** Every character of the page the selection starts on. */
    fun selectPage() {
        val page = selStart?.page ?: return
        val list = glyphs[page] ?: return
        if (list.isEmpty()) return
        selStart = Pos(page, 0)
        selEnd = Pos(page, list.size - 1)
        invalidate()
        publishSelection()
    }

    /** Jumps to the top of page [i] (0-based), at the zoom it is at. */
    fun goToPage(i: Int) {
        if (tops.isEmpty()) return
        val p = i.coerceIn(0, tops.size - 1)
        scroller.forceFinished(true)
        animator?.cancel()
        oy = tops[p] * scale
        clamp()
        invalidate()
        scheduleDetails()
    }

    fun clearSelection() {
        selStart = null
        selEnd = null
        invalidate()
        listener?.onSelection(null, 0f, 0f)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}
