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

    var deeplKey: String
        get() = sp.getString("deepl", "") ?: ""
        set(v) = sp.edit().putString("deepl", v.trim()).apply()

    var bubbleX: Int
        get() = sp.getInt("bx", -1)
        set(v) = sp.edit().putInt("bx", v).apply()

    var bubbleY: Int
        get() = sp.getInt("by", -1)
        set(v) = sp.edit().putInt("by", v).apply()
}

class HttpException(val code: Int, body: String) : Exception("HTTP $code $body")

/** Bir çeviri isteğinin sonucu: metinler (başarısız olanlar null), kullanılan motor, varsa uyarı. */
class TranslateResult(val texts: List<String?>, val engine: String, val warning: String?)

/**
 * Çevrimiçi çeviri. DeepL anahtarı varsa önce DeepL, olmazsa Google, o da olmazsa MyMemory.
 * Ağ işlemi olduğu için arka plan iş parçacığında çağrılmalıdır.
 */
object Translator {

    fun translateAll(texts: List<String>, source: String, target: String, deeplKey: String): TranslateResult {
        var warning: String? = null
        if (deeplKey.isNotBlank()) {
            try {
                return TranslateResult(deepl(texts, source, target, deeplKey), "DeepL", null)
            } catch (e: HttpException) {
                warning = when (e.code) {
                    403 -> "DeepL anahtarı geçersiz, Google kullanıldı"
                    456 -> "DeepL aylık kotası doldu, Google kullanıldı"
                    429 -> "DeepL çok yoğun, Google kullanıldı"
                    else -> "DeepL hatası (${e.code}), Google kullanıldı"
                }
            } catch (e: Exception) {
                warning = "DeepL'e bağlanılamadı, Google kullanıldı"
            }
        }
        var engine = "Google"
        val out = texts.map { t ->
            try {
                google(t, source, target)
            } catch (_: Exception) {
                try {
                    myMemory(t, source, target).also { engine = "MyMemory" }
                } catch (_: Exception) {
                    null
                }
            }
        }
        return TranslateResult(out, engine, warning)
    }

    // ------------------------------------------------------------ DeepL

    private fun deeplTarget(code: String) = when (code) {
        "en" -> "EN-US"
        "pt" -> "PT-BR"
        else -> code.uppercase()
    }

    private fun deepl(texts: List<String>, sl: String, tl: String, key: String): List<String?> {
        // Ücretsiz anahtarlar ":fx" ile biter ve farklı sunucuyu kullanır
        val host = if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val body = StringBuilder()
        for (t in texts) {
            if (body.isNotEmpty()) body.append('&')
            body.append("text=").append(URLEncoder.encode(t, "UTF-8"))
        }
        body.append("&target_lang=").append(deeplTarget(tl))
        if (sl != "auto") body.append("&source_lang=").append(sl.uppercase())

        val conn = URL("$host/v2/translate").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "DeepL-Auth-Key $key")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.setRequestProperty("User-Agent", "EkranCevirici/1.2")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }
                throw HttpException(code, err.take(200))
            }
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val arr = json.getJSONArray("translations")
            return texts.indices.map { i ->
                arr.optJSONObject(i)?.optString("text", "")?.takeIf { it.isNotBlank() }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ Google (anahtarsız uç nokta)

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

    // ------------------------------------------------------------ MyMemory

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
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) EkranCevirici/1.2")
            val code = conn.responseCode
            if (code != 200) throw HttpException(code, "")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
