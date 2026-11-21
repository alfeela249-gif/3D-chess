package com.alfeela.chess3d

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small REST-based Supabase Auth client. The publishable key is intended for mobile clients. */
class AuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("chess_auth", Context.MODE_PRIVATE)
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
        prefs.edit().putString("access_token", token).putString("refresh_token", refresh)
            .putLong("expires_at", System.currentTimeMillis() / 1000 + (values["expires_in"]?.toLongOrNull() ?: 3600L)).apply()
        return Result(true, "تم تسجيل الدخول عبر Google.")
    }

    fun hasSession() = !prefs.getString("access_token", null).isNullOrBlank()

    fun signOut() { prefs.edit().clear().apply() }

    @Synchronized
    fun accessToken(): String? {
        val current = prefs.getString("access_token", null)
        if (!current.isNullOrBlank() && prefs.getLong("expires_at", 0L) > System.currentTimeMillis() / 1000 + 60) return current
        val refresh = prefs.getString("refresh_token", null) ?: return current
        return try {
            val response = request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh).toString())
            if (response.second in 200..299) {
                saveSession(JSONObject(response.first))
                prefs.getString("access_token", current)
            } else current
        } catch (_: Exception) { current }
    }

    fun sendFriendRequest(username: String): Result {
        return try {
            val userId = currentUserId() ?: return Result(false, "انتهت الجلسة. سجّل الدخول مجدداً.")
            val safeName = URLEncoder.encode(username.trim(), "UTF-8")
            val found = authorizedRequest("GET", "/rest/v1/player_profiles?select=user_id&username=ilike.$safeName", null)
            val players = org.json.JSONArray(found.first)
            if (found.second !in 200..299 || players.length() == 0) return Result(false, "لم يتم العثور على لاعب بهذا الاسم.")
            val targetId = players.getJSONObject(0).optString("user_id")
            if (targetId == userId) return Result(false, "لا يمكنك إضافة نفسك.")
            val payload = JSONObject().put("user_low", minOf(userId, targetId)).put("user_high", maxOf(userId, targetId)).put("requested_by", userId).put("status", "pending")
            val sent = authorizedRequest("POST", "/rest/v1/friendships", payload.toString(), "return=minimal")
            if (sent.second in 200..299) Result(true, "تم إرسال طلب الصداقة.") else Result(false, "تعذر الإرسال. ربما يوجد طلب سابق أو أنكما صديقان بالفعل.")
        } catch (e: Exception) { Result(false, e.message ?: "تعذر إرسال طلب الصداقة") }
    }

    data class FriendEntry(val userId: String, val username: String, val nickname: String, val status: String, val incoming: Boolean)
    data class FriendsResult(val ok: Boolean, val message: String, val entries: List<FriendEntry> = emptyList())

    fun loadFriends(): FriendsResult {
        return try {
            val me = currentUserId() ?: return FriendsResult(false, "سجّل الدخول أولاً.")
            val path = "/rest/v1/friendships?select=user_low,user_high,requested_by,status&or=(user_low.eq.$me,user_high.eq.$me)&order=updated_at.desc"
            val response = authorizedRequest("GET", path, null)
            if (response.second !in 200..299) return FriendsResult(false, "تعذر تحميل قائمة الأصدقاء.")
            val rows = org.json.JSONArray(response.first)
            val entries = mutableListOf<FriendEntry>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val low = row.optString("user_low")
                val high = row.optString("user_high")
                val other = if (low == me) high else low
                val profileResponse = authorizedRequest("GET", "/rest/v1/player_profiles?select=username,nickname&user_id=eq.$other", null)
                val profiles = org.json.JSONArray(profileResponse.first)
                val profile = if (profiles.length() > 0) profiles.getJSONObject(0) else JSONObject()
                val status = row.optString("status")
                entries.add(FriendEntry(other, profile.optString("username", "لا يوجد اسم"), profile.optString("nickname", profile.optString("username", "لا يوجد اسم")), status, status == "pending" && row.optString("requested_by") != me))
            }
            FriendsResult(true, "تم تحميل الأصدقاء والطلبات.", entries)
        } catch (e: Exception) { FriendsResult(false, e.message ?: "تعذر تحميل الأصدقاء") }
    }

    fun respondFriendRequest(friendId: String, accept: Boolean): Result {
        return try {
            val me = currentUserId() ?: return Result(false, "سجّل الدخول أولاً.")
            val low = minOf(me, friendId)
            val high = maxOf(me, friendId)
            val body = JSONObject().put("status", if (accept) "accepted" else "rejected")
            val response = authorizedRequest("PATCH", "/rest/v1/friendships?user_low=eq.$low&user_high=eq.$high&status=eq.pending&requested_by=neq.$me", body.toString(), "return=minimal")
            if (response.second in 200..299) Result(true, if (accept) "تم قبول طلب الصداقة." else "تم رفض طلب الصداقة.") else Result(false, "تعذر تحديث طلب الصداقة.")
        } catch (e: Exception) { Result(false, e.message ?: "تعذر الرد على الطلب") }
    }
    data class PlayerProfile(val username: String, val nickname: String, val avatarUrl: String?)

    fun loadPlayerProfile(): PlayerProfile? {
        return try {
            val userId = currentUserId() ?: return null
            val response = authorizedRequest("GET", "/rest/v1/player_profiles?select=username,nickname,avatar_url&user_id=eq.$userId", null)
            if (response.second !in 200..299) return null
            val rows = org.json.JSONArray(response.first)
            if (rows.length() == 0) null else {
                val row = rows.getJSONObject(0)
                PlayerProfile(row.optString("username"), row.optString("nickname"), row.optString("avatar_url").takeIf { it.isNotBlank() })
            }
        } catch (_: Exception) { null }
    }

    fun uploadPlayerAvatar(bytes: ByteArray): Pair<Result, String?> {
        return try {
            val userId = currentUserId() ?: return Result(false, "سجّل الدخول أولاً.") to null
            val token = accessToken() ?: return Result(false, "انتهت الجلسة. سجّل الدخول مجدداً.") to null
            val objectPath = "$userId/avatar.jpg"
            val connection = (URL("$base/storage/v1/object/player-avatars/$objectPath").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "image/jpeg")
                setRequestProperty("x-upsert", "true")
                setFixedLengthStreamingMode(bytes.size)
            }
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }
            connection.disconnect()
            if (code !in 200..299) return Result(false, "تعذر رفع الصورة إلى التخزين ($code).") to null
            val publicUrl = "$base/storage/v1/object/public/player-avatars/$objectPath"
            Result(true, "تم رفع الصورة.") to publicUrl
        } catch (e: Exception) { Result(false, e.message ?: "تعذر رفع صورة الملف الشخصي") to null }
    }

    fun savePlayerProfile(username: String, nickname: String, avatarUrl: String? = null): Result {
        return try {
            val userId = currentUserId() ?: return Result(false, "سجّل الدخول أولاً.")
            val body = JSONObject().put("user_id", userId).put("username", username.trim()).put("nickname", nickname.trim())
            if (avatarUrl != null) body.put("avatar_url", avatarUrl)
            val response = authorizedRequest("POST", "/rest/v1/player_profiles?on_conflict=user_id", body.toString(), "resolution=merge-duplicates,return=minimal")
            if (response.second in 200..299) Result(true, "تم حفظ الملف الشخصي.") else Result(false, "تعذر حفظ الاسم. يجب أن يكون فريداً ومن 3 إلى 20 حرفاً.")
        } catch (e: Exception) { Result(false, e.message ?: "تعذر حفظ الملف الشخصي") }
    }

    data class RoomResult(val ok: Boolean, val message: String, val id: String? = null, val code: String? = null, val gameState: JSONObject? = null, val currentTurn: String? = null, val status: String? = null)

    fun createOnlineRoom(): RoomResult {
        return try {
            val userId = currentUserId() ?: return RoomResult(false, "سجّل الدخول أولاً.")
            val code = java.util.UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
            val body = JSONObject().put("invite_code", code).put("host_id", userId).put("status", "waiting")
                .put("game_state", JSONObject().put("moves", org.json.JSONArray())).put("current_turn", "white")
            val response = authorizedRequest("POST", "/rest/v1/online_game_rooms?select=id,invite_code,game_state,current_turn,status", body.toString(), "return=representation")
            val rows = org.json.JSONArray(response.first)
            if (response.second !in 200..299 || rows.length() == 0) RoomResult(false, "تعذر إنشاء الغرفة عبر الإنترنت.") else {
                val row = rows.getJSONObject(0)
                RoomResult(true, "تم إنشاء الغرفة. أرسل الرمز إلى صديقك.", row.optString("id"), row.optString("invite_code"), row.optJSONObject("game_state"), row.optString("current_turn"), row.optString("status"))
            }
        } catch (e: Exception) { RoomResult(false, e.message ?: "تعذر إنشاء الغرفة") }
    }

    fun joinOnlineRoom(code: String): RoomResult {
        return try {
            val body = JSONObject().put("p_invite_code", code.trim().uppercase())
            val response = authorizedRequest("POST", "/rest/v1/rpc/join_online_game_room", body.toString())
            val rows = org.json.JSONArray(response.first)
            if (response.second !in 200..299 || rows.length() == 0) RoomResult(false, "رمز الغرفة غير صحيح أو أن الغرفة ممتلئة.") else {
                val row = rows.getJSONObject(0)
                RoomResult(true, "انضممت إلى المباراة عبر الإنترنت.", row.optString("id"), row.optString("invite_code"), row.optJSONObject("game_state"), row.optString("current_turn"), row.optString("status"))
            }
        } catch (e: Exception) { RoomResult(false, e.message ?: "تعذر الانضمام إلى الغرفة") }
    }

    fun fetchOnlineRoom(roomId: String): RoomResult {
        return try {
            val response = authorizedRequest("GET", "/rest/v1/online_game_rooms?select=id,invite_code,game_state,current_turn,status&id=eq.$roomId", null)
            val rows = org.json.JSONArray(response.first)
            if (response.second !in 200..299 || rows.length() == 0) RoomResult(false, "تعذر تحديث المباراة.") else {
                val row = rows.getJSONObject(0)
                RoomResult(true, "تم تحديث المباراة.", row.optString("id"), row.optString("invite_code"), row.optJSONObject("game_state"), row.optString("current_turn"), row.optString("status"))
            }
        } catch (e: Exception) { RoomResult(false, e.message ?: "تعذر تحديث المباراة") }
    }

    fun submitOnlineMoves(roomId: String, moves: org.json.JSONArray, nextTurn: String): Result {
        return try {
            val body = JSONObject().put("p_room_id", roomId).put("p_game_state", JSONObject().put("moves", moves)).put("p_next_turn", nextTurn)
            val response = authorizedRequest("POST", "/rest/v1/rpc/submit_online_game_state", body.toString())
            if (response.second in 200..299) Result(true, "تم إرسال الحركة.") else Result(false, "تعذر مزامنة الحركة. تحقق من دورك والاتصال.")
        } catch (e: Exception) { Result(false, e.message ?: "تعذر إرسال الحركة") }
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
