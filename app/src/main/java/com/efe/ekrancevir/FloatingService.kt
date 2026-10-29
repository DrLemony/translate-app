package com.efe.ekrancevir

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.efe.ekrancevir.STOP"
        private const val CHANNEL = "cevirici"
        private const val NOTIF_ID = 7

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    // Ekran yakalama
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val imageLock = Any()
    private var latestImage: Image? = null
    private var capW = 0
    private var capH = 0

    // Ekrandaki katmanlar
    private var bubble: FrameLayout? = null
    private var bubbleSpinner: ProgressBar? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var menu: View? = null
    private var menuClosedAt = 0L
    private var selection: View? = null
    private var result: View? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()

        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
            @Suppress("DEPRECATION")
            val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
            if (data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            try {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val p = mpm.getMediaProjection(code, data)
                if (p == null) {
                    toast("Ekran yakalama izni alınamadı")
                    stopSelf()
                    return START_NOT_STICKY
                }
                projection = p
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        handler.post { stopSelf() }
                    }
                }, handler)
                setupCapture()
            } catch (e: Exception) {
                toast("Ekran yakalama başlatılamadı: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (bubble == null) showBubble()
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        removeView(menu); menu = null
        removeView(selection); selection = null
        removeView(result); result = null
        removeView(bubble); bubble = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        synchronized(imageLock) {
            try { latestImage?.close() } catch (_: Exception) {}
            latestImage = null
        }
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        captureThread?.quitSafely()
        try { recognizer.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Ekran döndü: yakalama boyutunu ve butonun yerini güncelle
        handler.postDelayed({
            resizeCapture()
            hideMenu()
            removeResult()
            clampBubble(snap = true)
        }, 300)
    }

    // ---------------------------------------------------------------- bildirim

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ekran Çevirici", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Ekran Çevirici çalışıyor")
            .setContentText("Yuvarlak butona dokunarak çeviri yap")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Kapat", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------- ekran yakalama

    private fun realSize(): IntArray {
        val dm = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    @SuppressLint("WrongConstant")
    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ reader ->
            synchronized(imageLock) {
                try { latestImage?.close() } catch (_: Exception) {}
                latestImage = null
                latestImage = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            }
        }, captureHandler)
        return r
    }

    private fun setupCapture() {
        val t = HandlerThread("yakalama").also { it.start() }
        captureThread = t
        captureHandler = Handler(t.looper)
        val (w, h, dpi) = realSize()
        capW = w; capH = h
        val reader = newReader(w, h)
        imageReader = reader
        virtualDisplay = projection!!.createVirtualDisplay(
            "EkranCevirici", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, captureHandler
        )
    }

    private fun resizeCapture() {
        val ch = captureHandler ?: return
        ch.post {
            val vd = virtualDisplay ?: return@post
            val (w, h, dpi) = realSize()
            if (w == capW && h == capH) return@post
            val old = imageReader
            try { old?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
            synchronized(imageLock) {
                try { latestImage?.close() } catch (_: Exception) {}
                latestImage = null
            }
            val nr = newReader(w, h)
            try {
                vd.resize(w, h, dpi)
                vd.surface = nr.surface
                imageReader = nr
                capW = w; capH = h
                try { old?.close() } catch (_: Exception) {}
            } catch (_: Exception) {
            }
        }
    }

    /** Son ekran karesini Bitmap olarak döndürür. */
    private fun grabBitmap(): Bitmap? = synchronized(imageLock) {
        val img = latestImage ?: return null
        try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * img.width
            val full = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            full.copyPixelsFromBuffer(buffer)
            if (full.width == img.width) full
            else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- yuvarlak buton

    private fun overlayParams(w: Int, h: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or extraFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = dp(56)
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E5EFF"))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_fg)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        frame.addView(icon, FrameLayout.LayoutParams(size, size))
        frame.addView(spinner, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))

        val (sw, sh) = realSize()
        bubbleParams = overlayParams(size, size).apply {
            x = if (prefs.bubbleX >= 0) prefs.bubbleX else sw - size - dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else sh / 3
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        frame.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        hideMenu()
                    }
                    if (moved) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        clampBubble(snap = false)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) clampBubble(snap = true) else onBubbleClick()
                }
            }
            true
        }
        bubble = frame
        bubbleSpinner = spinner
        wm.addView(frame, bubbleParams)
        clampBubble(snap = true)
    }

    private fun clampBubble(snap: Boolean) {
        val b = bubble ?: return
        val (sw, sh) = realSize()
        val size = bubbleParams.width
        if (snap) {
            bubbleParams.x = if (bubbleParams.x + size / 2 < sw / 2) dp(4) else sw - size - dp(4)
        }
        bubbleParams.x = bubbleParams.x.coerceIn(0, max(0, sw - size))
        bubbleParams.y = bubbleParams.y.coerceIn(0, max(0, sh - size))
        try { wm.updateViewLayout(b, bubbleParams) } catch (_: Exception) {}
        if (snap) {
            prefs.bubbleX = bubbleParams.x
            prefs.bubbleY = bubbleParams.y
        }
    }

    private fun setBusy(on: Boolean) {
        busy = on
        bubbleSpinner?.visibility = if (on) View.VISIBLE else View.GONE
        bubble?.getChildAt(0)?.visibility = if (on) View.INVISIBLE else View.VISIBLE
    }

    private fun onBubbleClick() {
        if (busy) return
        if (menu != null) {
            hideMenu()
            return
        }
        if (SystemClock.uptimeMillis() - menuClosedAt < 400) return
        removeResult()
        showMenu()
    }

    // ---------------------------------------------------------------- menü

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setTextColor(if (selected) Color.WHITE else Color.parseColor("#2D3748"))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (selected) Color.parseColor("#1E5EFF") else Color.parseColor("#EDF2F7"))
        }
        setOnClickListener { onClick() }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#718096"))
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun buildMenu(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(1, Color.parseColor("#E2E8F0"))
            }
            elevation = dp(10).toFloat()
        }

        val title = TextView(this).apply {
            text = "Ekran Çevirici"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#14213D"))
        }
        box.addView(title)

        val summary = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1E5EFF"))
            setPadding(0, dp(2), 0, 0)
        }
        box.addView(summary)

        val srcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tgtRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        lateinit var refresh: () -> Unit
        refresh = {
            srcRow.removeAllViews()
            tgtRow.removeAllViews()
            val src = prefs.source
            val tgt = prefs.target
            for (l in Langs.SOURCES) {
                srcRow.addView(chip(l.short, l.code == src) { prefs.source = l.code; refresh() },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            for (l in Langs.ALL) {
                tgtRow.addView(chip(l.short, l.code == tgt) { prefs.target = l.code; refresh() },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            val engine = if (prefs.deeplKey.isNotBlank()) "DeepL" else "Google"
            summary.text = "${Langs.byCode(src).name}  →  ${Langs.byCode(tgt).name}   ·  $engine"
        }

        box.addView(label("Hangi dilden"))
        box.addView(srcRow)
        box.addView(label("Hangi dile"))
        box.addView(tgtRow)
        refresh()

        val swap = TextView(this).apply {
            text = "⇄  Dilleri yer değiştir"
            textSize = 13f
            setTextColor(Color.parseColor("#2D3748"))
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener {
                val s = prefs.source
                val t = prefs.target
                if (s == "auto") {
                    prefs.source = t
                    prefs.target = if (t == "tr") "en" else "tr"
                } else {
                    prefs.source = t
                    prefs.target = s
                }
                refresh()
            }
        }
        box.addView(swap)

        val go = TextView(this).apply {
            text = "✂  Alan Seç ve Çevir"
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1E5EFF"))
            }
            setOnClickListener { startSelection() }
        }
        box.addView(go, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val close = TextView(this).apply {
            text = "Uygulamayı kapat"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#C53030"))
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { stopSelf() }
        }
        box.addView(close, LinearLayout.LayoutParams(-1, -2))

        // Menünün dışına dokunulunca kapan
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hideMenu()
                menuClosedAt = SystemClock.uptimeMillis()
                true
            } else false
        }
        return box
    }

    private fun showMenu() {
        val v = buildMenu()
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val mw = v.measuredWidth
        val mh = v.measuredHeight
        val (sw, sh) = realSize()
        val size = bubbleParams.width
        val gap = dp(8)
        val x = if (bubbleParams.x + size / 2 < sw / 2) bubbleParams.x + size + gap
        else bubbleParams.x - mw - gap
        val y = bubbleParams.y + size / 2 - mh / 2
        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        ).apply {
            this.x = x.coerceIn(0, max(0, sw - mw))
            this.y = y.coerceIn(0, max(0, sh - mh))
        }
        try {
            wm.addView(v, p)
            menu = v
        } catch (e: Exception) {
            toast("Menü açılamadı: ${e.message}")
        }
    }

    private fun hideMenu() {
        removeView(menu)
        menu = null
    }

    // ---------------------------------------------------------------- alan seçimi

    private fun startSelection() {
        hideMenu()
        removeResult()
        // Butonu gizle ki ekran görüntüsüne girmesin
        bubble?.visibility = View.INVISIBLE
        handler.postDelayed({
            val shot = grabBitmap()
            if (shot == null) {
                bubble?.visibility = View.VISIBLE
                toast("Ekran görüntüsü alınamadı, tekrar dene")
                return@postDelayed
            }
            showSelection(shot)
        }, 350)
    }

    private fun showSelection(shot: Bitmap) {
        val v = SelectionView(this) { rect ->
            handler.post {
                removeView(selection)
                selection = null
                bubble?.visibility = View.VISIBLE
                if (rect == null) shot.recycle() else process(shot, rect)
            }
        }
        val p = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        try {
            wm.addView(v, p)
            selection = v
        } catch (e: Exception) {
            bubble?.visibility = View.VISIBLE
            shot.recycle()
            toast("Seçim ekranı açılamadı: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- yazı okuma + çeviri

    private class Prepared(val input: Bitmap, val scale: Float, val clip: Rect)

    private class Found(val rect: Rect, val text: String)

    private fun process(shot: Bitmap, sel: Rect) {
        val src = prefs.source
        val tgt = prefs.target
        setBusy(true)
        scope.launch {
            try {
                val prep = withContext(Dispatchers.Default) {
                    val clip = Rect(sel)
                    if (!clip.intersect(0, 0, shot.width, shot.height) || clip.width() < 4 || clip.height() < 4) {
                        null
                    } else {
                        val crop = Bitmap.createBitmap(shot, clip.left, clip.top, clip.width(), clip.height())
                        // Küçük alanları büyüt: yazı okuma daha iyi çalışır
                        val scale = if (max(crop.width, crop.height) < 1400) 2f else 1f
                        val input = if (scale == 1f) crop else {
                            Bitmap.createScaledBitmap(
                                crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true
                            ).also { crop.recycle() }
                        }
                        Prepared(input, scale, clip)
                    }
                }
                if (prep == null) {
                    toast("Seçilen alan geçersiz")
                    return@launch
                }

                val text = recognize(prep.input)
                prep.input.recycle()

                val found = text.textBlocks.mapNotNull { block ->
                    val bb = block.boundingBox ?: return@mapNotNull null
                    val joined = joinLines(block.lines.map { it.text })
                    if (joined.none { it.isLetter() }) return@mapNotNull null
                    val r = Rect(
                        (bb.left / prep.scale).toInt() + prep.clip.left,
                        (bb.top / prep.scale).toInt() + prep.clip.top,
                        (bb.right / prep.scale).toInt() + prep.clip.left,
                        (bb.bottom / prep.scale).toInt() + prep.clip.top
                    )
                    Found(r, joined)
                }
                if (found.isEmpty()) {
                    toast("Seçilen alanda yazı bulunamadı")
                    return@launch
                }

                val key = prefs.deeplKey
                val res = withContext(Dispatchers.IO) {
                    if (src == tgt) TranslateResult(found.map { it.text }, "-", null)
                    else Translator.translateAll(found.map { it.text }, src, tgt, key)
                }
                val translations = res.texts
                res.warning?.let { toast(it) }

                if (translations.all { it == null }) {
                    toast("Çeviri yapılamadı. İnternet bağlantını kontrol et.")
                    return@launch
                }

                val blocks = withContext(Dispatchers.Default) {
                    found.mapIndexedNotNull { i, f ->
                        val tr = translations[i] ?: return@mapIndexedNotNull null
                        val (bg, fg) = colorsFor(shot, f.rect)
                        TranslatedBlock(f.rect, tr, bg, fg)
                    }
                }
                showResult(sel, blocks)
            } catch (e: Exception) {
                toast("Hata: ${e.message}")
            } finally {
                shot.recycle()
                setBusy(false)
            }
        }
    }

    private suspend fun recognize(bmp: Bitmap): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            when {
                sb.isEmpty() -> sb.append(t)
                sb.endsWith("-") -> {
                    sb.setLength(sb.length - 1)
                    sb.append(t)
                }
                else -> sb.append(' ').append(t)
            }
        }
        return sb.toString()
    }

    /** Yazının arkasındaki zemin rengini ve yazı rengini ekran görüntüsünden tahmin eder. */
    private fun colorsFor(bmp: Bitmap, r: Rect): Pair<Int, Int> {
        val w = bmp.width
        val h = bmp.height
        fun px(x: Int, y: Int) = bmp.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
        fun dist(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
        fun lum(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

        // 1) Zemin: kutunun hemen çevresindeki en yaygın renk
        val m = max(2, dp(3))
        val l = r.left - m
        val t = r.top - m
        val rr = r.right + m
        val b = r.bottom + m
        val samples = ArrayList<Int>(100)
        for (i in 0..24) {
            val x = l + (rr - l) * i / 24
            val y = t + (b - t) * i / 24
            samples.add(px(x, t)); samples.add(px(x, b))
            samples.add(px(l, y)); samples.add(px(rr, y))
        }
        val buckets = HashMap<Int, MutableList<Int>>()
        for (c in samples) {
            val key = ((Color.red(c) shr 4) shl 8) or ((Color.green(c) shr 4) shl 4) or (Color.blue(c) shr 4)
            buckets.getOrPut(key) { mutableListOf() }.add(c)
        }
        val best = buckets.values.maxByOrNull { it.size } ?: samples
        val bg = Color.rgb(
            best.sumOf { Color.red(it) } / best.size,
            best.sumOf { Color.green(it) } / best.size,
            best.sumOf { Color.blue(it) } / best.size
        )

        // 2) Yazı rengi: kutu içinde zeminden en farklı pikseller
        val inside = ArrayList<Int>()
        val stepX = max(1, r.width() / 60)
        val stepY = max(1, r.height() / 30)
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                inside.add(px(x, y)); x += stepX
            }
            y += stepY
        }
        val maxD = inside.maxOfOrNull { dist(it, bg) } ?: 0
        val contrast = if (lum(bg) > 0.55) Color.rgb(20, 20, 20) else Color.WHITE
        var fg = contrast
        if (maxD > 150) {
            val far = inside.filter { dist(it, bg) >= maxD * 0.7 }
            if (far.isNotEmpty()) {
                val c = Color.rgb(
                    far.sumOf { Color.red(it) } / far.size,
                    far.sumOf { Color.green(it) } / far.size,
                    far.sumOf { Color.blue(it) } / far.size
                )
                if (dist(c, bg) > 180) fg = c
            }
        }
        return Pair(bg, fg)
    }

    // ---------------------------------------------------------------- sonuç katmanı

    private fun showResult(sel: Rect, blocks: List<TranslatedBlock>) {
        if (blocks.isEmpty()) return
        removeResult()
        val (sw, sh) = realSize()
        val m = dp(24)
        val wx = max(0, sel.left - m)
        val wy = max(0, sel.top - m)
        val ww = min(sw, sel.right + m) - wx
        val wh = min(sh, sel.bottom + m + sel.height() / 2) - wy
        val local = blocks.map { it.copy(rect = Rect(it.rect).apply { offset(-wx, -wy) }) }

        val v = ResultView(this, local)
        v.setOnClickListener { removeResult() }
        val p = overlayParams(
            ww, wh,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply {
            x = wx
            y = wy
        }
        try {
            wm.addView(v, p)
            result = v
            // Pencere ekranda tam istenen yere oturmadıysa kaydır
            v.post {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                val dx = wx - loc[0]
                val dy = wy - loc[1]
                if (dx != 0 || dy != 0) {
                    p.x += dx
                    p.y += dy
                    try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            toast("Çeviri gösterilemedi: ${e.message}")
        }
    }

    private fun removeResult() {
        removeView(result)
        result = null
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun removeView(v: View?) {
        if (v == null) return
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
