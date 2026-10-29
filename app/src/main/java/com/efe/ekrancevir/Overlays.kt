package com.efe.ekrancevir

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Tam ekran, parmakla dikdörtgen alan seçtiren katman. Koordinatlar ekran (raw) koordinatıdır. */
@SuppressLint("ViewConstructor")
class SelectionView(context: Context, private val onDone: (Rect?) -> Unit) : View(context) {

    private val density = resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD54A") }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    private val loc = IntArray(2)
    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false
    private var finished = false

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationOnScreen(loc)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (finished) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.rawX; startY = e.rawY
                curX = startX; curY = startY
                dragging = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                curX = e.rawX; curY = e.rawY
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                curX = e.rawX; curY = e.rawY
                dragging = false
                finished = true
                val minSize = 16 * density
                if (abs(curX - startX) < minSize || abs(curY - startY) < minSize) {
                    onDone(null) // sadece dokunuldu: iptal
                } else {
                    onDone(
                        Rect(
                            min(startX, curX).toInt(), min(startY, curY).toInt(),
                            max(startX, curX).toInt(), max(startY, curY).toInt()
                        )
                    )
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!dragging) {
            canvas.drawRect(0f, 0f, w, h, dim)
            canvas.drawText("Çevirmek istediğin alanı parmağınla çiz", w / 2, h * 0.12f, hint)
            canvas.drawText("(iptal için sadece dokun)", w / 2, h * 0.12f + hint.textSize * 1.4f, hint)
            return
        }
        val l = min(startX, curX) - loc[0]
        val t = min(startY, curY) - loc[1]
        val r = max(startX, curX) - loc[0]
        val b = max(startY, curY) - loc[1]
        // Seçimin dışını karart
        canvas.drawRect(0f, 0f, w, t, dim)
        canvas.drawRect(0f, b, w, h, dim)
        canvas.drawRect(0f, t, l, b, dim)
        canvas.drawRect(r, t, w, b, dim)
        canvas.drawRect(l, t, r, b, border)
        val c = 5f * density
        canvas.drawCircle(l, t, c, corner)
        canvas.drawCircle(r, t, c, corner)
        canvas.drawCircle(l, b, c, corner)
        canvas.drawCircle(r, b, c, corner)
    }
}

/** Çevrilmiş bir metin bloğu: kutu (katmana göre), metin, arka plan ve yazı rengi. */
data class TranslatedBlock(val rect: Rect, val text: String, val bg: Int, val fg: Int)

/** Çevirileri orijinal yazıların tam üstüne çizen katman. */
@SuppressLint("ViewConstructor")
class ResultView(context: Context, blocks: List<TranslatedBlock>) : View(context) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    private class Item(val box: RectF, val layout: StaticLayout, val bg: Paint, val pad: Float)

    private val items: List<Item>

    init {
        val pad = 2f * density
        items = blocks.map { b ->
            val box = RectF(b.rect)
            box.inset(-pad, -pad)
            val avail = max(1, (box.width() - 2 * pad).toInt())
            val availH = box.height() - 2 * pad
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = b.fg }

            // En büyük sığan yazı boyutunu bul
            var size = min(availH * 0.85f, 48f * scaled)
            val minSize = 8f * scaled
            var layout: StaticLayout
            do {
                tp.textSize = max(size, minSize)
                layout = StaticLayout.Builder.obtain(b.text, 0, b.text.length, tp, avail)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build()
                val fits = layout.height <= availH
                val atMin = size <= minSize
                if (!fits && !atMin) size -= max(0.5f, size * 0.06f)
            } while (!fits && !atMin)
            // Hâlâ sığmıyorsa kutuyu aşağı doğru uzat
            if (layout.height > availH) box.bottom = box.top + layout.height + 2 * pad
            Item(box, layout, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = b.bg }, pad)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = 3f * density
        for (item in items) {
            canvas.drawRoundRect(item.box, r, r, item.bg)
            canvas.save()
            val textTop = item.box.top + item.pad +
                max(0f, (item.box.height() - 2 * item.pad - item.layout.height) / 2f)
            canvas.translate(item.box.left + item.pad, textTop)
            item.layout.draw(canvas)
            canvas.restore()
        }
    }
}
