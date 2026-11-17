package com.gratilo.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.gratilo.app.data.*

@Composable fun AuthScreen(vm: GratiloViewModel, state: UiState, page: String, token: String, navigate: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    var legal by rememberSaveable { mutableStateOf(false) }
    var newsletter by rememberSaveable { mutableStateOf(false) }
    var image by remember { mutableStateOf<String?>(null) }
    val titles = mapOf("login" to "Schön, dass du da bist.", "register" to "Dein Danke beginnt hier.", "forgot" to "Passwort vergessen?", "resend" to "Bestätigung erneut senden", "verify" to "E-Mail bestätigen", "reset" to "Ein neuer Anfang.")
    Panel(titles[page] ?: "Anmelden") {
        if (page in listOf("login", "register", "forgot", "resend")) Field("E-Mail-Adresse", email, { email = it }, enabled = !state.busy)
        if (page != "forgot" && page != "verify") Field("Passwort", password, { password = it }, password = true, enabled = !state.busy)
        if (page == "register" || page == "reset") {
            Text("Mindestens 8 Zeichen, Groß- und Kleinbuchstaben sowie ein Sonderzeichen, ohne Leerzeichen.")
            Field("Passwort wiederholen", confirm, { confirm = it }, password = true, enabled = !state.busy)
        }
        if (page == "register") {
            ProfilePhoto(image, !state.busy, { image = it }, vm::report)
            Field("Vorname (optional)", first, { first = it }); Field("Nachname (optional)", last, { last = it })
            Text("Lokale Test-AGB und Datenschutz: Profildaten werden im User-Service gespeichert. Testtexte müssen vor öffentlichem Betrieb ersetzt werden. Bestätigungs-E-Mails findest du lokal in MailHog.")
            Choice("Ich akzeptiere die Test-AGB und habe den Datenschutzhinweis gelesen.", legal, !state.busy) { legal = it }
            Choice("Ich möchte Neuigkeiten von Gratilo erhalten (optional).", newsletter, !state.busy) { newsletter = it }
        }
        val emailValid = email.trim().matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) && email.trim().length <= 254
        Action(if (page == "login") "Anmelden" else "Bestätigen", !state.busy && (page in listOf("reset", "verify") || emailValid) && (page != "register" || legal)) {
            when (page) {
                "login" -> vm.login(email, password)
                "register" -> vm.register(json("email" to email.trim(), "password" to password, "confirmPassword" to confirm,
                    "profileImage" to image, "firstName" to first, "lastName" to last, "newsletter" to newsletter, "agbFileName" to "agb-demo-v1", "privacyPolicy" to "privacy-demo-v1"))
                "forgot" -> vm.submit("/user-service/auth/password-reset/request", json("email" to email.trim()))
                "resend" -> vm.submit("/user-service/auth/verification/resend", json("email" to email.trim(), "password" to password))
                "verify" -> if (token.isBlank()) vm.report("Im Link fehlt der Token.") else vm.load("verification", "/user-service/auth/verify?token=${segment(token)}")
                "reset" -> if (token.isBlank()) vm.report("Im Link fehlt der Token.") else if (!validPassword(password) || password != confirm) vm.report("Bitte prüfe die Passwortrichtlinie und die Wiederholung.") else vm.submit("/user-service/auth/password-reset/confirm", json("token" to token, "newPassword" to password))
            }
            password = ""; confirm = ""
        }
        state.data["verification"]?.obj()?.string("message")?.let { Text(it) }
        listOf("login" to "Anmelden", "register" to "Konto erstellen", "forgot" to "Passwort vergessen?", "resend" to "Bestätigungs-E-Mail erneut senden").filter { it.first != page }.forEach { (route, label) ->
            TextButton(onClick = { password = ""; confirm = ""; navigate(route) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }
}
@Composable fun ProfileScreen(vm: GratiloViewModel, state: UiState, profile: JsonObject, closeAccount: () -> Unit) {
    val values = remember(profile) { mutableStateMapOf<String, String>().apply { listOf("firstName", "lastName", "organisation", "street", "city", "phone", "country").forEach { put(it, profile.string(it)) } } }
    var image by remember(profile) { mutableStateOf(profile.string("profileImage").ifBlank { null }) }
    var newsletter by remember(profile) { mutableStateOf(profile.bool("newsletter")) }
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Panel("Dein Profil") {
        Text(profile.string("email"))
        ProfilePhoto(image, !state.busy, { image = it }, vm::report)
        mapOf("firstName" to "Vorname", "lastName" to "Nachname", "organisation" to "Organisation", "street" to "Straße", "city" to "Stadt", "phone" to "Telefon", "country" to "Land").forEach { (key, label) -> Field(label, values[key].orEmpty(), { values[key] = it }) }
        Choice("Newsletter", newsletter, !state.busy) { newsletter = it }
        Field("Aktuelles Passwort (nur bei Änderung)", current, { current = it }, true)
        Field("Neues Passwort", password, { password = it }, true)
        Field("Neues Passwort wiederholen", confirm, { confirm = it }, true)
        Action("Profil speichern", !state.busy) {
            if ((password.isNotBlank() || current.isNotBlank()) && (current.isBlank() || !validPassword(password) || password != confirm)) vm.report("Bitte prüfe aktuelles Passwort, Passwortrichtlinie und Wiederholung.")
            else {
                val body = profile.deepCopy().apply { values.forEach { (key, value) -> addProperty(key, value) }; addProperty("newsletter", newsletter); add("profileImage", com.google.gson.Gson().toJsonTree(image)) }
                if (current.isNotBlank()) { body.addProperty("currentPassword", current); body.addProperty("newPassword", password); body.addProperty("confirmPassword", confirm) }
                vm.submit("/user-service/users/profile", body, "PUT")
                current = ""; password = ""; confirm = ""
            }
        }
        OutlinedButton(onClick = closeAccount, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Konto schließen") }
    }
}
@Composable fun StripeScreen(vm: GratiloViewModel, state: UiState, open: (String, Boolean) -> Unit) {
    Panel("Stripe Connect") {
        val account = state.data["connect"]?.obj()
        if (account != null) {
            Text("Status: ${account.string("onboardingStatus")}")
            Text("Zahlungen: ${if(account.bool("chargesEnabled")) "freigegeben" else "nicht freigegeben"}")
            Text("Auszahlungen: ${if(account.bool("payoutsEnabled")) "freigegeben" else "nicht freigegeben"}")
            Text("Transfers: ${if(account.bool("transfersEnabled")) "freigegeben" else "nicht freigegeben"}")
        } else Text("Noch kein bestätigter Stripe-Kontostatus.")
        Action("Stripe einrichten / fortsetzen", !state.busy) {
            vm.submit("/payment-service/connect-account/onboarding/start", after = { open(it.obj().string("onboardingUrl"), true) }, message = "Nach der Rückkehr den Stripe-Status prüfen.")
        }
        Action("Stripe-Status beim Backend prüfen", !state.busy) { vm.submit("/payment-service/connect-account/sync", key = "connect") }
        Action("Guthaben aktualisieren", !state.busy) { vm.load("balance", "/payment-service/connect-account/balance") }
        state.data["balance"]?.obj()?.let { Text("Verfügbar: ${money(it.long("availableMinor"))}\nAusstehend: ${money(it.long("pendingMinor"))}\nGeprüft: ${it.string("checkedAt")}") }
        Action("Testzugang prüfen", !state.busy) { vm.load("testAccess", "/payment-service/test-access") }
        state.data["testAccess"]?.obj()?.let { access ->
            Text(if (access.bool("enabled")) "Testzugang aktiv" else "Testzugang nicht aktiv")
            var code by remember { mutableStateOf("") }
            if (access.bool("canActivate")) { Field("Test-Aktivierungscode", code, { code = it }); Action("Testzugang aktivieren", !state.busy && code.isNotBlank()) { vm.submit("/payment-service/test-access", json("code" to code), key = "testAccess"); code = "" } }
            if (access.bool("enabled")) Confirm("Testzugang deaktivieren", "Testzugang wirklich deaktivieren?", !state.busy) { vm.submit("/payment-service/test-access", method = "DELETE", key = "testAccess") }
        }
    }
}
@Composable fun PayoutScreen(vm: GratiloViewModel, state: UiState) {
    val savedPayout = state.profile?.string("userId")?.let { vm.repository.saved("payout:$it") }?.let { runCatching { com.google.gson.JsonParser.parseString(it).obj() }.getOrNull() }
    var amount by rememberSaveable(state.profile?.string("userId")) { mutableStateOf(savedPayout?.long("amountMinor")?.let { (it / 100.0).toString() }.orEmpty()) }
    val eligibility = state.data["eligibility"]?.obj()
    val rows = state.data["payouts"].rows()
    LaunchedEffect(eligibility) { if (eligibility != null) vm.load("payouts", "/payment-service/payouts") }
    Panel("Auszahlungen") {
        Action("Voraussetzungen aktualisieren", !state.busy) { vm.load("eligibility", "/payment-service/payouts/eligibility") }
        Action("Auszahlungseinrichtung prüfen", !state.busy) { vm.submit("/payment-service/payouts/setup") }
        if (savedPayout != null) Text("Eine frühere Anfrage ist noch gespeichert. Wiederholungen verwenden denselben Betrag und dieselbe Auszahlungs-ID.")
        eligibility?.let { item ->
            val available = item.long(if (item.bool("simulation")) "simulationAvailableMinor" else "availableMinor")
            Text(if (item.bool("simulation")) "Lokale Simulation · keine Bankauszahlung" else "Stripe-Auszahlung · Status wird vom Backend geprüft")
            Text("Verfügbar: ${money(available)} · Bank: ${item.string("bankName")} •••• ${item.string("bankLast4")}")
            Text(item.string("message"))
            Field("Auszahlungsbetrag in EUR", amount, { amount = it })
            val minor = amountMinor(amount)
            val pending = rows.any { it.string("status") in listOf("PENDING", "AUTHORIZED") }
            Confirm("Auszahlung anfordern", "${money(minor ?: 0)} an die hinterlegte Bankverbindung auszahlen? Die Anforderung bestätigt noch keinen Geldeingang.",
                !state.busy && state.error.isBlank() && item.bool("eligible") && item.string("currency") == "EUR" && state.data.containsKey("payouts") && !pending && minor != null && minor in 100..available) { vm.payout(minor!!) }
        }
        Action("Historie aktualisieren", !state.busy) { vm.load("payouts", "/payment-service/payouts") }
        if (rows.isEmpty()) Text("Keine Auszahlungen geladen.")
        rows.forEach { payout ->
            Text("${money(payout.long("amountMinor"))} · ${payout.string("status")} · ${payout.string("provider")}\n${payout.string("createdAt")}")
            if (payout.string("status") in listOf("PENDING", "AUTHORIZED", "BOOKED")) Action("Status abgleichen", !state.busy) {
                vm.submit("/payment-service/payouts/${segment(payout.string("id"))}/reconcile", key = "payouts", refreshPath = "/payment-service/payouts")
            }
        }
    }
}
