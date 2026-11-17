package com.gratilo.app.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit

interface Api {
    @GET suspend fun get(@Url path: String): Response<ResponseBody>
    @POST suspend fun post(@Url path: String, @Body body: JsonObject): Response<ResponseBody>
    @PUT suspend fun put(@Url path: String, @Body body: JsonObject): Response<ResponseBody>
    @DELETE suspend fun delete(@Url path: String): Response<ResponseBody>
}
fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
    pairs.forEach { (key, value) -> when (value) {
        null -> add(key, com.google.gson.JsonNull.INSTANCE)
        is JsonElement -> add(key, value)
        is Boolean -> addProperty(key, value)
        is Number -> addProperty(key, value)
        else -> addProperty(key, value.toString())
    } }
}
fun JsonElement?.obj(): JsonObject = this?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
fun JsonObject.string(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
fun JsonObject.bool(key: String): Boolean = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
fun JsonObject.long(key: String): Long = get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
fun JsonElement?.rows(): List<JsonObject> = this?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.obj() }.orEmpty()

class ApiFailure(val status: Int, val code: String, override val message: String, val payload: JsonObject = JsonObject()): IOException(message)
interface SessionStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}
interface Repository {
    val authenticated: StateFlow<Boolean>
    suspend fun request(path: String, body: JsonObject? = null, method: String = "GET"): JsonElement
    suspend fun login(email: String, password: String)
    fun logout()
    fun saved(key: String): String?
    fun save(key: String, value: String?)
}
class GratiloRepository(private val store: SessionStore, userUrl: String, paymentUrl: String): Repository {
    @Volatile private var token = store.read("token")
    private val loggedIn = MutableStateFlow(!token.isNullOrBlank())
    override val authenticated: StateFlow<Boolean> = loggedIn
    // Separate service origins. Never follow a cross-origin redirect with a credential.
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
            token?.let { request.header("Authorization", "Bearer $it") }
            chain.proceed(request.build())
        }.build()
    private fun service(url: String): Api = Retrofit.Builder().baseUrl(url)
        .client(client).addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java)
    private val users = service(userUrl)
    private val payments = service(paymentUrl)
    override suspend fun request(path: String, body: JsonObject?, method: String): JsonElement {
        require(path.startsWith("/user-service/") || path.startsWith("/payment-service/"))
        val api = if (path.startsWith("/payment-service/")) payments else users
        val response = try { when (method) {
            "POST" -> api.post(path, body ?: JsonObject())
            "PUT" -> api.put(path, body ?: JsonObject())
            "DELETE" -> api.delete(path)
            else -> api.get(path)
        } } catch (failure: IOException) { throw ApiFailure(0, "NETWORK", "Der Server ist nicht erreichbar. Bitte versuche es erneut.") }
        if (!response.isSuccessful) {
            if (response.code() == 401 && !path.endsWith("/auth/login")) logout()
            val error = runCatching { JsonParser.parseString(response.errorBody()?.string().orEmpty()).obj() }.getOrDefault(JsonObject())
            throw ApiFailure(response.code(), error.string("code"), when {
                response.code() == 401 -> if (path.endsWith("/auth/login")) "E-Mail oder Passwort ist nicht korrekt." else "Deine Sitzung ist abgelaufen. Bitte melde dich erneut an."
                error.string("message").isNotBlank() -> listOf(error.string("message"), error.string("detail")).filter { it.isNotBlank() }.joinToString(" ")
                else -> "Anfrage fehlgeschlagen (${response.code()}). Bitte versuche es erneut."
            }, error)
        }
        val payload = response.body()?.string().orEmpty()
        if (payload.isBlank()) return JsonObject()
        return try { JsonParser.parseString(payload) } catch (_: RuntimeException) {
            throw ApiFailure(response.code(), "INVALID_RESPONSE", "Der Server hat eine ungültige Antwort gesendet.")
        }
    }
    override suspend fun login(email: String, password: String) {
        val result = request("/user-service/auth/login", json("email" to email.trim(), "password" to password), "POST").obj()
        val access = result.string("accessToken")
        if (access.isBlank()) throw ApiFailure(0, "INVALID_RESPONSE", "Die Anmeldung lieferte keine gültige Sitzung.")
        token = access
        try { request("/user-service/users/me") } catch (failure: Exception) { token = null; throw failure }
        store.write("token", access)
        loggedIn.value = true
    }
    override fun logout() { token = null; store.write("token", null); loggedIn.value = false }
    override fun saved(key: String) = store.read(key)
    override fun save(key: String, value: String?) = store.write(key, value)
}
