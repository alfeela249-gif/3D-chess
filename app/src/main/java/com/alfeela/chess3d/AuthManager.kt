package com.alfeela.chess3d

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small REST-based Supabase Auth client. The publishable key is intended for mobile clients. */
class AuthManager(context: Context) {
    private val prefs = context.getSharedPreferences("chess_auth", Context.MODE_PRIVATE)
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    data class Result(val ok: Boolean, val message: String)

    fun signIn(email: String, password: String) = authRequest(
        "/auth/v1/token?grant_type=password",
        JSONObject().put("email", email).put("password", password)
    )

    fun signUp(email: String, password: String) = authRequest(
        "/auth/v1/signup",
        JSONObject().put("email", email).put("password", password)
    )

    private fun authRequest(path: String, body: JSONObject): Result {
        return try {
            val response = request("POST", path, body.toString())
            val json = JSONObject(response.first)
            if (response.second in 200..299) {
                saveSession(json)
                val needsConfirm = !json.has("access_token")
                Result(true, if (needsConfirm) "تم إنشاء الحساب. تحقق من بريدك الإلكتروني ثم سجّل الدخول." else "تم تسجيل الدخول بنجاح.")
            } else Result(false, json.optString("msg", json.optString("message", json.optString("error_description", "تعذر إتمام العملية"))))
        } catch (e: Exception) { Result(false, e.message ?: "تعذر الاتصال بالخادم") }
    }

    fun startGoogleOAuth(): String {
        val redirect = "com.alfeela.chess3d://auth-callback"
        return "$base/auth/v1/authorize?provider=google&redirect_to=" +
            URLEncoder.encode(redirect, "UTF-8")
    }

    fun acceptOAuthCallback(uri: Uri): Result {
        val fragment = uri.fragment ?: return Result(false, "لم يصل رمز تسجيل الدخول من Google")
        val values = fragment.split("&").mapNotNull {
            val pair = it.split("=", limit = 2)
            if (pair.size == 2) pair[0] to Uri.decode(pair[1]) else null
        }.toMap()
        val token = values["access_token"] ?: return Result(false, values["error_description"] ?: "فشل تسجيل الدخول عبر Google")
        val refresh = values["refresh_token"] ?: ""
        prefs.edit().putString("access_token", token).putString("refresh_token", refresh).apply()
        return Result(true, "تم تسجيل الدخول عبر Google.")
    }

    fun hasSession() = !prefs.getString("access_token", null).isNullOrBlank()

    fun signOut() { prefs.edit().clear().apply() }

    fun accessToken() = prefs.getString("access_token", null)

    private fun saveSession(json: JSONObject) {
        val token = json.optString("access_token")
        if (token.isNotBlank()) {
            prefs.edit()
                .putString("access_token", token)
                .putString("refresh_token", json.optString("refresh_token"))
                .putLong("expires_at", System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600))
                .apply()
        }
    }

    private fun request(method: String, path: String, body: String?): Pair<String, Int> {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Content-Type", "application/json")
            body?.let { doOutput = true }
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        connection.disconnect()
        return text to code
    }
}
