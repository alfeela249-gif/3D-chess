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

    fun sendFriendRequest(username: String): Result {
        return try {
            val userId = currentUserId() ?: return Result(false, "انتهت الجلسة. سجّل الدخول مجدداً.")
            val safeName = URLEncoder.encode(username.trim(), "UTF-8")
            val found = authorizedRequest("GET", "/rest/v1/player_profiles?select=user_id&username=eq.$safeName", null)
            val players = org.json.JSONArray(found.first)
            if (found.second !in 200..299 || players.length() == 0) return Result(false, "لم يتم العثور على لاعب بهذا الاسم.")
            val targetId = players.getJSONObject(0).optString("user_id")
            if (targetId == userId) return Result(false, "لا يمكنك إضافة نفسك.")
            val payload = JSONObject().put("user_low", minOf(userId, targetId)).put("user_high", maxOf(userId, targetId)).put("requested_by", userId).put("status", "pending")
            val sent = authorizedRequest("POST", "/rest/v1/friendships", payload.toString(), "return=minimal")
            if (sent.second in 200..299) Result(true, "تم إرسال طلب الصداقة.") else Result(false, "تعذر الإرسال. ربما يوجد طلب سابق أو أنكما صديقان بالفعل.")
        } catch (e: Exception) { Result(false, e.message ?: "تعذر إرسال طلب الصداقة") }
    }

    fun savePlayerProfile(username: String, nickname: String): Result {
        return try {
            val userId = currentUserId() ?: return Result(false, "سجّل الدخول أولاً.")
            val body = JSONObject().put("user_id", userId).put("username", username.trim()).put("nickname", nickname.trim())
            val response = authorizedRequest("POST", "/rest/v1/player_profiles?on_conflict=user_id", body.toString(), "resolution=merge-duplicates,return=minimal")
            if (response.second in 200..299) Result(true, "تم حفظ الملف الشخصي.") else Result(false, "تعذر حفظ الاسم. يجب أن يكون فريداً ومن 3 إلى 20 حرفاً.")
        } catch (e: Exception) { Result(false, e.message ?: "تعذر حفظ الملف الشخصي") }
    }

    private fun currentUserId(): String? {
        val response = authorizedRequest("GET", "/auth/v1/user", null)
        if (response.second !in 200..299) return null
        return JSONObject(response.first).optString("id").takeIf { it.isNotBlank() }
    }

    private fun authorizedRequest(method: String, path: String, body: String?, prefer: String? = null): Pair<String, Int> {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Authorization", "Bearer " + (accessToken() ?: apiKey))
            setRequestProperty("Content-Type", "application/json")
            if (prefer != null) setRequestProperty("Prefer", prefer)
            body?.let { doOutput = true }
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        connection.disconnect()
        return text to code
    }
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
