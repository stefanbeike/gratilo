package com.gratilo.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.gratilo.app.BuildConfig
import com.gratilo.app.data.*

@Composable fun PublicPayment(vm: GratiloViewModel, state: UiState, path: String, open: (String, Boolean) -> Unit) {
    val uri = remember(path) { Uri.parse(path) }
    val parts = uri.pathSegments
    val recipientPath = parts.lastOrNull().orEmpty()
    val routePool = parts.getOrNull(1) == "team"
    val sessionId = uri.getQueryParameter("session_id")
    val resultPage = recipientPath in listOf("success", "cancelled")
    val key = "recipient:$path"
    val recipient = state.data[key]?.obj()
    val pool = routePool || recipient?.string("paymentTarget") == "POOL" || !recipient?.string("poolId").isNullOrBlank()
    val recipientId = if (pool) recipient?.string("poolId").orEmpty().ifBlank { recipient?.string("ownerId").orEmpty() } else recipient?.string("userId").orEmpty()
    val base = "/payment-service/tips/${if(pool) "pools/" else ""}${segment(recipientId)}"
    val attemptKey = "checkout:$path"
    val saved = vm.repository.saved(attemptKey)?.let { runCatching { JsonParser.parseString(it).obj() }.getOrNull() }
    var amount by remember(path) { mutableStateOf(saved?.long("tipAmountMinor")?.let { (it / 100.0).toString() } ?: "5,00") }
    var message by remember(path) { mutableStateOf(saved?.string("message").orEmpty()) }
    var covered by remember(path) { mutableStateOf(saved?.bool("feeCovered") ?: false) }
    val minor = amountMinor(amount)
    val quoteKey = "quote:$path:$minor:$covered"
    val quote = state.data[quoteKey]?.obj()
    LaunchedEffect(state.data["checkoutStatus"]) {
        val status = state.data["checkoutStatus"]?.obj()
        if (resultPage && status?.bool("confirmed") == true && status.string("status") in listOf("PAID", "FAILED")) {
            vm.repository.saved("activeCheckout")?.let { vm.repository.save(it, null) }; vm.repository.save("activeCheckout", null)
        }
    }
    LaunchedEffect(path) {
        vm.clearCheckoutState()
        if (resultPage && !sessionId.isNullOrBlank()) vm.load("checkoutStatus", "/payment-service/tips/checkout/confirm?sessionId=${segment(sessionId)}")
        else if (!resultPage) vm.load(key, if(routePool) "/user-service/pools/public/${segment(recipientPath)}" else "/user-service/users/public/${if (Regex("[0-9a-fA-F-]{36}").matches(recipientPath)) segment(recipientPath) else "payment/${segment(recipientPath)}"}")
    }
    LaunchedEffect(state.data["pendingCheckoutStatus"]) {
        val status = state.data["pendingCheckoutStatus"]?.obj()
        if (status?.bool("confirmed") == true && status.string("status") in listOf("PAID", "FAILED")) vm.repository.save(attemptKey, null)
    }
    Panel("Ein kleines Danke. Ein großes Gefühl.") {
        if (resultPage) {
            val status = state.data["checkoutStatus"]?.obj()
            Text(if (status?.string("status") == "PAID" && status.bool("confirmed")) "Danke! Deine Zahlung wurde vom Backend bestätigt." else "Zahlung noch nicht bestätigt. Bitte nicht erneut bezahlen.")
            status?.let { Text("Status: ${it.string("status")}\nReferenz: ${it.string("transactionId")}") }
            if (!sessionId.isNullOrBlank()) Action("Zahlungsstatus aktualisieren", !state.busy) { vm.load("checkoutStatus", "/payment-service/tips/checkout/confirm?sessionId=${segment(sessionId)}") }
        } else {
            Text(recipient?.string("name").orEmpty().ifBlank { listOf(recipient?.string("firstName"), recipient?.string("lastName")).filterNotNull().joinToString(" ") })
            val conflict = state.data["checkoutConflict"]?.obj()
            if (conflict != null && conflict.string("sessionId").isNotBlank()) {
                Text("Dieser Zahlungsversuch existiert bereits. Keine neue Zahlung anlegen.")
                Action("Bestehenden Zahlungsstatus prüfen", !state.busy) { vm.load("pendingCheckoutStatus", "/payment-service/tips/checkout/confirm?sessionId=${segment(conflict.string("sessionId"))}") }
                state.data["pendingCheckoutStatus"]?.obj()?.let { pending ->
                    Text("Backend-Status: ${pending.string("status")}${if (pending.bool("confirmed")) " · bestätigt" else " · noch nicht bestätigt"}")
                    if (pending.string("checkoutUrl").isNotBlank()) Action("Bestehenden Checkout fortsetzen", !state.busy) { open(pending.string("checkoutUrl"), true) }
                    if (pending.bool("confirmed") && pending.string("status") in listOf("PAID", "FAILED")) {
                        Text("Der alte Versuch ist abgeschlossen. Zurück zur Übersicht wechseln.")
                    }
                }
            }
            if (recipient == null) Action("Empfänger erneut laden", !state.busy) { vm.load(key, if(routePool) "/user-service/pools/public/${segment(recipientPath)}" else "/user-service/users/public/payment/${segment(recipientPath)}") }
            else if (recipient.get("paymentEnabled")?.let { !it.isJsonNull && !it.asBoolean } == true) Text("Dieser Trinkgeldlink ist pausiert.")
            else {
                Field("Trinkgeld in EUR", amount, { amount = it })
                Field("Nachricht (max. 500 Zeichen)", message, { if (it.length <= 500) message = it })
                Choice("Gebühren übernehmen", covered, !state.busy) { covered = it }
                Text("Die verbindlichen Gebühren und Beträge werden vom Backend berechnet.")
                Action("Betrag und Gebühren prüfen", !state.busy && minor != null && minor in 100..1000000 && recipientId.isNotBlank()) {
                    vm.quote(quoteKey, base, minor!!, covered)
                }
                quote?.let {
                    Text("Trinkgeld: ${money(it.long("tipAmountMinor"))}\nGesamt: ${money(it.long("totalAmountMinor"))}\nEmpfänger erhält${if(it.bool("recipientAmountEstimated")) " voraussichtlich" else ""}: ${money(it.long("recipientAmountMinor"))}")
                    Confirm("Stripe Checkout öffnen", "${money(it.long("totalAmountMinor"))} über die sichere Stripe-Seite bezahlen?", !state.busy && state.error.isBlank() && conflict == null && BuildConfig.PAYMENTS_ENABLED && it.long("tipAmountMinor") == minor && it.long("totalAmountMinor") >= 100 && it.long("recipientAmountMinor") > 0 && it.string("currency").equals("eur", true)) {
                        val body = saved?.deepCopy()?.apply {
                            // A new quote requires explicit confirmation; retain the same payment attempt ID.
                            addProperty("expectedTotalAmountMinor", it.long("totalAmountMinor"))
                            addProperty("expectedRecipientAmountMinor", it.long("recipientAmountMinor"))
                        } ?: json("tipAmountMinor" to minor, "message" to message, "feeCovered" to covered,
                            "idempotencyKey" to java.util.UUID.randomUUID().toString(), "expectedTotalAmountMinor" to it.long("totalAmountMinor"), "expectedRecipientAmountMinor" to it.long("recipientAmountMinor"), "expectedPoolId" to if(pool) recipient?.string("poolId") else null)
                        if (saved != null && (saved.long("tipAmountMinor") != minor || saved.bool("feeCovered") != covered || saved.string("message") != message)) vm.report("Ein Zahlungsversuch ist noch ungeklärt. Prüfe zuerst dessen Status oder wiederhole dieselben Angaben.")
                        else { vm.repository.save(attemptKey, body.toString()); vm.repository.save("activeCheckout", attemptKey); vm.submit("$base/checkout", body, after = { result ->
                            val checkout = result.obj()
                            vm.repository.save("lastTransaction", checkout.string("transactionId"))
                            open(checkout.string("checkoutUrl"), true)
                        }, message = "Nach der Rückkehr den Zahlungsstatus beim Backend prüfen.") }
                    }
                }
                if (!BuildConfig.PAYMENTS_ENABLED) Text("Checkout ist in dieser App-Konfiguration deaktiviert. Lokale Stripe-Testkonfiguration erforderlich.")
                if (saved != null) Text("Ein Zahlungsversuch ist gespeichert. Sichere Wiederholung verwendet dieselbe Zahlungs-ID.")
            }
        }
    }
}
