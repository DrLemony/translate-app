package com.efe.ekrancevir

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Lang(val code: String, val short: String, val name: String)

object Langs {
    val AUTO = Lang("auto", "OTO", "Otomatik")
    val ALL = listOf(
        Lang("en", "EN", "İngilizce"),
        Lang("de", "DE", "Almanca"),
        Lang("es", "ES", "İspanyolca"),
        Lang("pt", "PT", "Portekizce"),
        Lang("tr", "TR", "Türkçe"),
    )
    val SOURCES = listOf(AUTO) + ALL

    fun byCode(code: String): Lang = SOURCES.firstOrNull { it.code == code } ?: AUTO
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    var source: String
        get() = sp.getString("kaynak", "auto") ?: "auto"
        set(v) = sp.edit().putString("kaynak", v).apply()

    var target: String
        get() = sp.getString("hedef", "tr") ?: "tr"
        set(v) = sp.edit().putString("hedef", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bx", -1)
        set(v) = sp.edit().putInt("bx", v).apply()

    var bubbleY: Int
        get() = sp.getInt("by", -1)
        set(v) = sp.edit().putInt("by", v).apply()
}

/**
 * Çevrimiçi çeviri. Önce Google Translate'in herkese açık uç noktasını,
 * o olmazsa MyMemory'yi dener. API anahtarı gerekmez.
 * Ağ işlemi olduğu için arka plan iş parçacığında çağrılmalıdır.
 */
object Translator {

    fun translate(text: String, source: String, target: String): String {
        if (text.isBlank()) return text
        return try {
            google(text, source, target)
        } catch (e: Exception) {
            myMemory(text, source, target)
        }
    }

    private fun google(text: String, sl: String, tl: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=" + sl + "&tl=" + tl + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
        val arr = JSONArray(get(url))
        val parts = arr.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONArray(i) ?: continue
            sb.append(p.optString(0, ""))
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw IllegalStateException("boş çeviri")
        return out
    }

    private fun myMemory(text: String, sl: String, tl: String): String {
        val src = if (sl == "auto") (if (tl == "en") "tr" else "en") else sl
        val q = if (text.length > 480) text.substring(0, 480) else text
        val url = "https://api.mymemory.translated.net/get?q=" +
            URLEncoder.encode(q, "UTF-8") + "&langpair=" + src + "%7C" + tl
        val obj = JSONObject(get(url))
        val out = obj.getJSONObject("responseData").optString("translatedText", "").trim()
        if (out.isEmpty()) throw IllegalStateException("boş çeviri")
        return out
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) EkranCevirici/1.0")
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
