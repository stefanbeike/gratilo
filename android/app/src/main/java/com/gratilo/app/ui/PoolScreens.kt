package com.gratilo.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.gratilo.app.data.*

@Composable fun PoolsScreen(vm: GratiloViewModel, state: UiState) {
    var name by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("") }
    val pools = state.data["pools"].rows()
    Panel("Meine Pools") {
        Action("Pools aktualisieren", !state.busy) { vm.loadPools() }
        when (state.poolsEnabled) {
            null -> Text("Die Pool-Konfiguration ist noch nicht bestätigt.")
            false -> Text("Team-Trinkgelder sind derzeit deaktiviert.")
            true -> {
                if (pools.isEmpty() && !state.busy) Text("Du bist noch keinem Pool zugeordnet.")
                pools.forEach { pool -> OutlinedButton(onClick = { selected = pool.string("id"); vm.load("pool", "/user-service/pools/${segment(selected)}") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("${pool.string("name")} · ${if (pool.string("ownerId") == state.profile?.string("userId")) "Administrator" else "Mitglied"}") } }
                Field("Neuer Poolname (max. 120 Zeichen)", name, { name = it })
                Action("Pool erstellen", !state.busy && name.isNotBlank() && name.trim().length <= 120) { vm.submit("/user-service/pools", json("name" to name.trim()), key = "pools", refreshPath = "/user-service/pools/all"); name = "" }
            }
        }
    }
    val pool = state.data["pool"]?.obj()?.takeIf { it.string("id") == selected }
    if (state.poolsEnabled == true && pool != null) PoolDetails(vm, state, pool)
    if (state.poolsEnabled == true) Panel("Historische Poolanteile") {
        Action("Pool-Zahlungen laden", !state.busy) { vm.load("transfers", "/payment-service/pool-transfers") }
        state.data["transfers"].rows().forEach { item -> Text("${item.string("poolName")} · ${money(item.long("amountMinor"))}\n${item.string("status")} · ${item.string("createdAt")}\nAnteil zum Zahlungszeitpunkt: ${item.string("historicalSharePercent")}%\n${item.string("settlementReason")}") }
        if (state.data["transfers"].rows().isEmpty()) Text("Keine Poolzahlungen geladen.")
    }
}
@Composable private fun PoolDetails(vm: GratiloViewModel, state: UiState, pool: JsonObject) {
    val id = segment(pool.string("id"))
    val path = "/user-service/pools/$id"
    val owner = pool.string("ownerId") == state.profile?.string("userId")
    val closing = pool.string("status") == "CLOSING"
    val members = pool.get("members").rows()
    var name by remember(pool) { mutableStateOf(pool.string("name")) }
    var email by rememberSaveable(id) { mutableStateOf("") }
    var dissolve by rememberSaveable(id) { mutableStateOf("") }
    val shares = remember(pool) { mutableStateMapOf<String, String>().apply { members.forEach { put(it.string("id"), it.string("sharePercent")) } } }
    fun mutate(suffix: String, body: JsonObject = JsonObject(), method: String = "POST") { vm.submit(path + suffix, body, method, "pool", path, after = { vm.loadPools() }) }
    Panel(pool.string("name")) {
        Text("Status: ${pool.string("status")} · ${if (owner) "Administrator" else "Mitglied"}")
        Action("Zahlungsbereitschaft prüfen", !state.busy) { vm.load("poolReady:$id", "/payment-service/tips/pools/$id/eligibility") }
        val ready = state.data["poolReady:$id"]?.obj()?.bool("canReceiveTips") == true && System.currentTimeMillis() - (state.checkedAt["poolReady:$id"] ?: 0L) <= 120000
        Text(if (ready) "Zahlungsbereitschaft vom Backend bestätigt" else "Keine bestätigte Zahlungsbereitschaft")
        if (ready && !closing && pool.string("paymentUrl").isNotBlank()) QrCard(paymentLink(pool.string("paymentUrl")))
        if (owner && !closing) {
            ProfilePhoto(pool.string("profileImage").ifBlank { null }, !state.busy, { mutate("/image", json("profileImage" to it), "PUT") }, vm::report)
            Field("Poolname", name, { name = it })
            Action("Pool umbenennen", !state.busy && name.isNotBlank() && name.trim().length <= 120) { mutate("", json("name" to name.trim()), "PUT") }
            Field("E-Mail des neuen Mitglieds", email, { email = it })
            Action("Mitglied einladen", !state.busy && email.isNotBlank()) {
                if (email.trim().equals(state.profile?.string("email"), true)) vm.report("Du kannst dich nicht selbst einladen.") else mutate("/members", json("email" to email.trim()))
            }
            Action("Stripe-Status der Mitglieder prüfen", !state.busy) { vm.load("memberStripe:$id", "/payment-service/connect-account/pools/$id/members") }
        }
        state.data["memberStripe:$id"].rows().forEach { Text("${it.string("userId")}: ${it.string("stripeStatus")}") }
        members.forEach { member ->
            val memberId = segment(member.string("id"))
            val own = member.string("userId") == state.profile?.string("userId")
            HorizontalDivider()
            Text(member.string("name").ifBlank { member.string("email") })
            Text("${member.string("status")} · ${if (member.bool("active")) "Aktiv" else "Inaktiv"}\nEffektiver Anteil: ${member.string("effectiveSharePercent")}%\n${member.string("deactivationReason")}")
            if (owner && !closing) {
                Field("Relatives Gewicht (0–100)", shares[member.string("id")].orEmpty(), { shares[member.string("id")] = it })
                if (member.string("userId") != pool.string("ownerId")) {
                    if (member.string("status") == "INVITED") Action("Einladung erneut senden", !state.busy) { mutate("/members/$memberId/resend") }
                    else Action(if (member.bool("active")) "Mitglied deaktivieren" else "Mitglied aktivieren", !state.busy) { mutate("/members/$memberId?active=${!member.bool("active")}", method = "PUT") }
                    Confirm("Mitglied entfernen", "${member.string("name")} aus dem Pool entfernen? Bestehende Zahlungen bleiben erhalten.", !state.busy) { mutate("/members/$memberId", method = "DELETE") }
                }
            } else if (own && !closing) {
                if (member.string("status") == "INVITED" && member.string("invitationToken").isNotBlank()) {
                    listOf("accept" to "Einladung annehmen", "decline" to "Einladung ablehnen").forEach { (action, label) -> Action(label, !state.busy) { vm.submit("/user-service/pools/invitations/${segment(member.string("invitationToken"))}/$action", key = "pool", refreshPath = path) } }
                } else {
                    Action(if (member.bool("active")) "Mitgliedschaft pausieren" else "Mitgliedschaft reaktivieren", !state.busy && member.string("deactivationReason") != "OWNER") { vm.submit("/user-service/pools/memberships/$memberId?active=${!member.bool("active")}", method = "PUT", key = "pool", refreshPath = path) }
                    Confirm("Pool verlassen", "Diesen Pool verlassen? Bestehende Zahlungsanteile bleiben erhalten.", !state.busy) { vm.submit("/user-service/pools/memberships/$memberId", method = "DELETE", key = "pools", refreshPath = "/user-service/pools/all", after = { vm.forget("pool") }) }
                }
            }
        }
        if (owner && !closing) {
            Action("Alle Gewichte speichern", !state.busy) {
                val parsed = members.map { member -> member to amountMinor(shares[member.string("id")].orEmpty()) }
                if (parsed.any { it.second == null || it.second!! !in 0..10000 }) vm.report("Gewichte müssen zwischen 0 und 100 mit höchstens zwei Nachkommastellen liegen.")
                else mutate("/shares", json("values" to JsonArray().apply { parsed.forEach { (member, basis) -> add(json("memberId" to member.string("id"), "sharePercent" to basis!! / 100.0)) } }), "PUT")
            }
            Text("Die Gewichte sind relativ. Effektive Anteile berechnet ausschließlich das Backend.")
            Field("Zur Auflösung den Poolnamen eingeben", dissolve, { dissolve = it })
            Confirm("Pool auflösen", "Pool dauerhaft auflösen? Das Backend prüft offene Zahlungen und Verteilungen.", !state.busy && dissolve == pool.string("name")) {
                vm.submit("$path/dissolve", json("poolName" to dissolve), key = "pools", refreshPath = "/user-service/pools/all", after = { vm.forget("pool") })
            }
        }
    }
}
