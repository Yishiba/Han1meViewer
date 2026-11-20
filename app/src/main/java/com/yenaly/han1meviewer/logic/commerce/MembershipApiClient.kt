package com.yenaly.han1meviewer.logic.commerce

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Dedicated membership transport. It never sends video-site cookies or browser credentials. */
class MembershipApiClient(
    baseUrl: String,
    private val deviceId: String,
    private val verifier: EntitlementVerifier,
    private val call: (Request) -> Response = { defaultClient.newCall(it).execute() },
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    class MembershipHttpException(val statusCode: Int) : IOException("Membership HTTP $statusCode")

    data class LoginStart(val authorizationUrl: String, val loginTicket: String)
    data class MembershipStatus(val active: Boolean, val entitlement: EntitlementVerifier.Entitlement?)

    private val root: HttpUrl = baseUrl.toHttpUrl().also {
        require(it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty())
        require(it.query == null && it.fragment == null && it.encodedPath == "/")
    }

    init {
        require(deviceId.length in 16..128 && deviceId.all { it.code in 33..126 })
    }

    suspend fun startLogin(): LoginStart = withContext(Dispatchers.IO) {
        val data = request("v1/auth/start", buildJsonObject { put("device_id", deviceId) }.toString())
        LoginStart(data["authorization_url"]?.jsonPrimitive?.content.orEmpty().also {
            require(it.startsWith("https://afdian.com/oauth2/authorize?"))
        }, data["login_ticket"]?.jsonPrimitive?.content.orEmpty().also {
            require(it.length in 32..128)
        })
    }

    suspend fun completeLogin(ticket: String): String? = withContext(Dispatchers.IO) {
        require(ticket.length in 32..128)
        val data = request("v1/auth/complete", buildJsonObject { put("login_ticket", ticket) }.toString())
        when (data["status"]?.jsonPrimitive?.content) {
            "pending" -> null
            "authenticated" -> data["session_token"]?.jsonPrimitive?.content?.also {
                require(it.length in 32..128)
            } ?: throw IOException("Missing session token")
            else -> throw IOException("Invalid login response")
        }
    }

    suspend fun status(sessionToken: String): MembershipStatus = withContext(Dispatchers.IO) {
        decodeStatus(request("v1/membership", sessionToken = sessionToken))
    }

    suspend fun refresh(sessionToken: String, orderNo: String? = null): MembershipStatus =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { if (orderNo != null) put("order_no", orderNo) }.toString()
            decodeStatus(request("v1/membership/refresh", body, sessionToken))
        }

    suspend fun logout(sessionToken: String) = withContext(Dispatchers.IO) {
        request("v1/auth/logout", "{}", sessionToken)
    }

    private fun decodeStatus(data: kotlinx.serialization.json.JsonObject): MembershipStatus {
        val state = data["status"]?.jsonPrimitive?.content
        if (state == "inactive") return MembershipStatus(false, null)
        if (state != "active") throw IOException("Invalid membership status")
        val proof = data["entitlement"]?.jsonPrimitive?.content
            ?: throw IOException("Missing entitlement")
        val verified = verifier.verify(proof, deviceId, clockSeconds())
            ?: throw IOException("Invalid membership entitlement")
        return MembershipStatus(true, verified)
    }

    private fun request(path: String, jsonBody: String? = null,
                        sessionToken: String? = null): kotlinx.serialization.json.JsonObject {
        require(sessionToken == null || sessionToken.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        val url = root.newBuilder().addPathSegments(path).build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (sessionToken != null) builder.header("Authorization", "Bearer $sessionToken")
        if (jsonBody == null) builder.get()
        else builder.post(jsonBody.toRequestBody("application/json".toMediaType()))
        return call(builder.build()).use { response ->
            if (!response.isSuccessful) throw MembershipHttpException(response.code)
            val bytes = requireNotNull(response.body).byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 65_536) throw IOException("Membership response too large")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }
}
