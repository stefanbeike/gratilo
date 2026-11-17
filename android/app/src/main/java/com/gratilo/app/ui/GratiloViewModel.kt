package com.gratilo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.gratilo.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.net.URLEncoder

fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
fun validPassword(value: String) = Regex("^(?=.*\\p{Ll})(?=.*\\p{Lu})(?=.*[^\\p{L}\\p{N}\\s])\\S{8,}$").matches(value)
fun amountMinor(value: String): Long? = if (Regex("^\\d{1,12}([,.]\\d{1,2})?$").matches(value.trim()))
    runCatching { BigDecimal(value.trim().replace(',', '.')).movePointRight(2).longValueExact() }.getOrNull() else null

data class UiState(val busy: Boolean = false, val error: String = "", val notice: String = "",
    val profile: JsonObject? = null, val checkedAt: Map<String, Long> = emptyMap(), val data: Map<String, JsonElement> = emptyMap(), val poolsEnabled: Boolean? = null)
class GratiloViewModel(val repository: Repository): ViewModel() {
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    val authenticated = repository.authenticated
    private val tasks = mutableSetOf<Job>()
    init { viewModelScope.launch { authenticated.collect { if (!it) { tasks.toList().forEach { job -> job.cancel() }; mutable.value = UiState() } } } }
    fun forget(key: String) { mutable.update { it.copy(data = it.data - key, checkedAt = it.checkedAt - key) } }
    fun clearCheckoutState() { mutable.update { it.copy(data = it.data - setOf("checkoutConflict", "pendingCheckoutStatus", "checkoutStatus")) } }
    fun clearMessage() { mutable.update { it.copy(error = "", notice = "") } }
    fun report(message: String) { mutable.update { it.copy(error = message) } }
    private val mutex = Mutex()
    private fun work(queue: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.busy && !queue) return
        mutable.update { it.copy(busy = true, error = "", notice = "") }
        val task = viewModelScope.launch { mutex.withLock {
            mutable.update { it.copy(busy = true) }
            try { block() } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { mutable.update { state -> state.copy(error = failure.message ?: "Bitte versuche es erneut.",
                data = if (failure is ApiFailure && failure.code.startsWith("CHECKOUT_") && failure.payload.string("sessionId").isNotBlank()) state.data + ("checkoutConflict" to failure.payload) else state.data) } }
            finally { mutable.update { it.copy(busy = false) } }
        } }
        tasks += task
        task.invokeOnCompletion { tasks.remove(task) }
    }
    fun login(email: String, password: String) = work {
        repository.login(email, password)
        profile()
    }
    private suspend fun profile() {
        val value = repository.request("/user-service/users/me").obj()
        mutable.update { it.copy(profile = value) }
    }
    fun refreshProfile() = work(queue = true) { profile(); fetch("transactions", "/payment-service/transactions"); fetch("summary", "/payment-service/transactions/summary") }
    fun logout() { tasks.toList().forEach { it.cancel() }; repository.logout(); mutable.value = UiState() }
    fun load(key: String, path: String) = work(queue = true) { fetch(key, path) }
    private suspend fun fetch(key: String, path: String) {
        val value = repository.request(path)
        mutable.update { it.copy(data = it.data + (key to value), checkedAt = it.checkedAt + (key to System.currentTimeMillis())) }
    }
    fun quote(key: String, base: String, amount: Long, covered: Boolean) = work(queue = true) {
        val eligibility = repository.request("$base/eligibility").obj()
        require(eligibility.bool("canReceiveTips")) { "Dieser Empfänger ist derzeit nicht zahlungsbereit." }
        fetch(key, "$base/quote?amountMinor=$amount&feeCovered=$covered")
    }
    fun loadPools() = work(queue = true) {
        val flags = repository.request("/user-service/features").obj()
        val enabled = flags.get("tipPoolsEnabled")?.asBoolean ?: error("Ungültige Pool-Konfiguration")
        mutable.update { it.copy(poolsEnabled = enabled) }
        if (enabled) fetch("pools", "/user-service/pools/all")
    }
    fun submit(path: String, body: JsonObject = JsonObject(), method: String = "POST", key: String? = null,
               refreshPath: String? = null, message: String = "Gespeichert.", after: (JsonElement) -> Unit = {}) = work {
        val result = repository.request(path, body, method)
        if (key != null) mutable.update { it.copy(data = it.data + (key to result)) }
        if (path == "/user-service/users/profile") mutable.update { it.copy(profile = result.obj()) }
        if (refreshPath != null && key != null) fetch(key, refreshPath)
        mutable.update { it.copy(notice = result.obj().string("message").ifBlank { message }) }
        after(result)
    }
    fun register(body: JsonObject) {
        if (!validPassword(body.string("password"))) { report("Mindestens 8 Zeichen, Groß- und Kleinbuchstaben und ein Sonderzeichen; keine Leerzeichen."); return }
        if (body.string("password") != body.string("confirmPassword")) { report("Die Passwörter stimmen nicht überein."); return }
        submit("/user-service/users/register", body, message = "Bitte bestätige deine E-Mail-Adresse über den Link in deiner E-Mail.")
    }
    fun payout(amount: Long) = work {
        val user = mutable.value.profile?.string("userId") ?: error("Bitte erneut anmelden.")
        val key = "payout:$user"
        val previous = repository.saved(key)
        val body = previous?.let { com.google.gson.JsonParser.parseString(it).obj() }
            ?: json("requestId" to java.util.UUID.randomUUID().toString(), "amountMinor" to amount)
        require(body.long("amountMinor") == amount) { "Eine frühere Anfrage ist ungeklärt. Wiederhole denselben Betrag oder gleiche den Status ab." }
        repository.save(key, body.toString())
        val result = repository.request("/payment-service/payouts", body, "POST").obj()
        require(result.string("id").isNotBlank()) { "Unklares Auszahlungsergebnis. Bitte dieselbe Anfrage wiederholen." }
        repository.save(key, null)
        fetch("payouts", "/payment-service/payouts")
        fetch("eligibility", "/payment-service/payouts/eligibility")
        mutable.update { it.copy(notice = "Auszahlungsstatus: ${result.string("status")}. Noch kein bestätigter Geldeingang bei deiner Bank.") }
    }
}
