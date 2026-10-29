package com.efe.ekrancevir

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqProjection = 1001
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(Color.parseColor("#F4F6FB"))
        }

        val title = TextView(this).apply {
            text = "Ekran Çevirici"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#14213D"))
            gravity = Gravity.CENTER
        }
        val info = TextView(this).apply {
            text = "Başlat'a bas, ekranda yuvarlak bir buton belirecek.\n" +
                "Butona dokun → dilleri seç → \"Alan Seç ve Çevir\" → çevirmek istediğin yeri parmağınla çiz.\n" +
                "Çeviriyi kapatmak için üstüne dokun."
            textSize = 15f
            setTextColor(Color.parseColor("#4A5568"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        }

        val startButton = Button(this).apply {
            text = "BAŞLAT"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(Color.parseColor("#1E5EFF"))
            }
            setOnClickListener { onStartClicked() }
        }
        stopButton = Button(this).apply {
            text = "Durdur"
            textSize = 15f
            setTextColor(Color.parseColor("#C53030"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingService::class.java))
                refresh()
            }
        }

        val prefs = Prefs(this)
        val keyLabel = TextView(this).apply {
            text = "DeepL API anahtarı (boş bırakırsan Google kullanılır)"
            textSize = 13f
            setTextColor(Color.parseColor("#4A5568"))
            setPadding(0, dp(28), 0, dp(6))
        }
        val keyInput = EditText(this).apply {
            hint = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx:fx"
            setText(prefs.deeplKey)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CBD5E0"))
            }
        }
        val keyStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        }
        fun showKeyStatus() {
            if (prefs.deeplKey.isBlank()) {
                keyStatus.text = "Şu an: Google Çeviri"
                keyStatus.setTextColor(Color.parseColor("#718096"))
            } else {
                keyStatus.text = "Şu an: DeepL ✓"
                keyStatus.setTextColor(Color.parseColor("#2F855A"))
            }
        }
        showKeyStatus()
        val saveKey = Button(this).apply {
            text = "Anahtarı kaydet"
            setOnClickListener {
                prefs.deeplKey = keyInput.text.toString()
                showKeyStatus()
                Toast.makeText(this@MainActivity, "Kaydedildi", Toast.LENGTH_SHORT).show()
            }
        }
        val getKey = TextView(this).apply {
            text = "Ücretsiz anahtar al (deepl.com) →"
            textSize = 13f
            setTextColor(Color.parseColor("#1E5EFF"))
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.deepl.com/pro-api")))
                } catch (_: Exception) {
                }
            }
        }

        root.addView(title)
        root.addView(info)
        root.addView(startButton, LinearLayout.LayoutParams(dp(240), dp(64)))
        root.addView(stopButton, LinearLayout.LayoutParams(dp(240), dp(52)).apply { topMargin = dp(8) })
        root.addView(keyLabel, LinearLayout.LayoutParams(dp(360), -2))
        root.addView(keyInput, LinearLayout.LayoutParams(dp(360), -2))
        root.addView(keyStatus, LinearLayout.LayoutParams(dp(360), -2))
        root.addView(saveKey, LinearLayout.LayoutParams(dp(360), -2).apply { topMargin = dp(6) })
        root.addView(getKey, LinearLayout.LayoutParams(dp(360), -2))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#F4F6FB"))
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        stopButton.visibility = if (FloatingService.running) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "\"Diğer uygulamaların üzerinde göster\" iznini aç, sonra geri dön", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (FloatingService.running) {
            finish()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
        } catch (e: Exception) {
            Toast.makeText(this, "Ekran yakalama başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettings() {
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (i in tries) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqProjection) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Ekran görüntüsü izni verilmedi", Toast.LENGTH_LONG).show()
            return
        }
        val svc = Intent(this, FloatingService::class.java).apply {
            putExtra(FloatingService.EXTRA_CODE, resultCode)
            putExtra(FloatingService.EXTRA_DATA, data)
        }
        startForegroundService(svc)
        // Uygulamadan çık, ekranda sadece yuvarlak buton kalsın
        finish()
    }
}
