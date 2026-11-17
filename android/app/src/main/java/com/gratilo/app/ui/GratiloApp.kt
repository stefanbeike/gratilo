package com.gratilo.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.JsonObject
import com.gratilo.app.BuildConfig
import com.gratilo.app.data.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.StateFlow
import java.text.NumberFormat
import java.util.Locale

fun transactionLabel(value: String): String = mapOf("TIP" to "Trinkgeld", "PAYOUT" to "Auszahlung", "REFUND" to "Erstattung",
    "BOOKED" to "Gebucht", "COMPLETED" to "Abgeschlossen", "PENDING" to "Ausstehend", "AUTHORIZED" to "Wird geprüft",
    "FAILED" to "Fehlgeschlagen", "CANCELLED" to "Storniert", "REFUNDED" to "Erstattet", "TRANSFERRED" to "Übertragen")[value] ?: value
fun money(minor: Long) = NumberFormat.getCurrencyInstance(Locale.GERMANY).format(minor / 100.0)
@Composable fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, enabled: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        singleLine = true, visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else if (label.contains("E-Mail")) KeyboardType.Email else KeyboardType.Text),
        trailingIcon = if (password) ({ IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.Check else Icons.Default.Lock, "Passwort ${if (visible) "verbergen" else "anzeigen"}") } }) else null)
}
@Composable fun Choice(label: String, checked: Boolean, enabled: Boolean = true, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = changed), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
    }
}
@Composable fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}
@Composable fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge); content()
    } }
}
@Composable fun Confirm(label: String, message: String, enabled: Boolean = true, action: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { open = false; action() }) { Text("Bestätigen") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } })
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GratiloApp(vm: GratiloViewModel, incoming: StateFlow<String?>, consume: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val authenticated by vm.authenticated.collectAsStateWithLifecycle()
    val link by incoming.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("Trinkgelder") }
    var authPage by rememberSaveable { mutableStateOf("login") }
    var token by remember { mutableStateOf("") }
    var publicPath by rememberSaveable { mutableStateOf("") }
    var invitation by rememberSaveable { mutableStateOf("") }
    var accountClose by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun open(url: String, stripe: Boolean = false) {
        val uri = Uri.parse(url)
        val valid = if (stripe) uri.scheme == "https" && uri.host in setOf("connect.stripe.com", "checkout.stripe.com")
            else uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http" && uri.host in setOf("10.0.2.2", "localhost", "127.0.0.1"))
        if (!valid) { vm.report("Ungültiger Link."); return }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { vm.report("Kein Browser verfügbar.") }
    }
    LaunchedEffect(link) {
        link?.let {
            val uri = Uri.parse(it)
            when {
                uri.path.orEmpty().startsWith("/pool/invitations/") -> { invitation = uri.lastPathSegment.orEmpty(); screen = "Einladung" }
                uri.path == "/auth/verify" -> { token = uri.getQueryParameter("token").orEmpty(); authPage = "verify"; screen = "Authentifizierung" }
                uri.path == "/reset-password" -> { token = uri.getQueryParameter("token").orEmpty(); authPage = "reset"; screen = "Authentifizierung" }
                uri.path.orEmpty().startsWith("/pay/") -> { publicPath = it; screen = "Bezahlen" }
            }
            consume()
        }
    }
    LaunchedEffect(authenticated) { if (authenticated) vm.refreshProfile() }
    LaunchedEffect(screen, authenticated) {
        if (authenticated) when (screen) {
            "Trinkgelder" -> vm.load("transactions", "/payment-service/transactions")
            "Pools", "Einladung" -> vm.loadPools()
            "Stripe" -> vm.load("connect", "/payment-service/connect-account")
            "Auszahlungen" -> vm.load("eligibility", "/payment-service/payouts/eligibility")
        }
    }
    BackHandler(screen != "Trinkgelder" || authPage != "login") { screen = "Trinkgelder"; authPage = "login"; vm.clearMessage() }
    Scaffold(topBar = { TopAppBar(title = { Column { Text("Gratilo", fontWeight = FontWeight.Bold); Text("Good people deserve more", style = MaterialTheme.typography.labelSmall) } },
        navigationIcon = { if (screen != "Trinkgelder") IconButton(onClick = { screen = "Trinkgelder" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        actions = { if (authenticated) IconButton(onClick = { vm.logout(); screen = "Trinkgelder" }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Abmelden") } }) },
        bottomBar = { if (authenticated && screen != "Bezahlen") NavigationBar {
            listOf("Trinkgelder" to Icons.Default.Home, "Pools" to Icons.Default.AccountBox, "Profil" to Icons.Default.Person).forEach { (label, icon) ->
                NavigationBarItem(selected = screen == label, onClick = { screen = label; vm.clearMessage() }, icon = { Icon(icon, label) }, label = { Text(label) })
            }
        } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Wird geladen" })
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            if (state.notice.isNotBlank()) Text(state.notice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (screen == "Bezahlen") PublicPayment(vm, state, publicPath, ::open)
            else if (!authenticated || screen == "Authentifizierung") AuthScreen(vm, state, authPage, token) { authPage = it; vm.clearMessage() }
            else when (screen) {
                "Profil" -> state.profile?.let { ProfileScreen(vm, state, it) { accountClose = true } }
                "Pools" -> PoolsScreen(vm, state)
                "Einladung" -> {
                    Panel("Pool-Einladung") {
                        Text("Du bist als ${state.profile?.string("email").orEmpty()} angemeldet. Nur die eingeladene Person kann antworten.")
                        if (state.poolsEnabled == true) {
                            Action("Einladung annehmen", !state.busy && invitation.isNotBlank()) { vm.submit("/user-service/pools/invitations/${segment(invitation)}/accept") }
                            Confirm("Einladung ablehnen", "Diese Einladung wirklich ablehnen?", !state.busy) { vm.submit("/user-service/pools/invitations/${segment(invitation)}/decline") }
                        } else Text("Die Pool-Konfiguration wird geprüft oder Pools sind deaktiviert.")
                    }
                }
                "Stripe" -> StripeScreen(vm, state, ::open)
                "Auszahlungen" -> PayoutScreen(vm, state)
                else -> {
                    Text("Hallo ${state.profile?.string("firstName").orEmpty()}", style = MaterialTheme.typography.headlineMedium)
                    Panel("Meine Trinkgelder") {
                        Text("Jedes Danke auf einen Blick. Geschätzte Nettobeträge sind kein verfügbares Guthaben.")
                        state.data["summary"]?.obj()?.let { Text("Eingänge: ${money(it.long("receivedMinor"))}\nAusstehend: ${money(it.long("pendingMinor"))}\nTransaktionen: ${it.long("transactionCount")}") }
                        Action("Transaktionen aktualisieren", !state.busy) { vm.load("transactions", "/payment-service/transactions") }
                        Action("Stripe & Guthaben", !state.busy) { screen = "Stripe" }
                        Action("Auszahlungen", !state.busy) { screen = "Auszahlungen" }
                    }
                    state.profile?.let { profile ->
                        val enabled = profile.bool("paymentEnabled")
                        Panel("Dein Trinkgeldlink") {
                            Text(if (enabled) "Persönlicher Link aktiviert" else "Persönlicher Link pausiert")
                            val path = profile.string("paymentUrl")
                            val readyKey = "personalReady:${profile.string("userId")}"
                            val ready = state.data[readyKey]?.obj()?.bool("canReceiveTips") == true && System.currentTimeMillis() - (state.checkedAt[readyKey] ?: 0L) <= 120000
                            if (enabled && ready && path.isNotBlank()) QrCard(paymentLink(path))
                            else Text("Für den QR-Code muss die aktuelle Zahlungsbereitschaft bestätigt sein.")
                            Action("Zahlungsbereitschaft prüfen", !state.busy) { vm.load(readyKey, "/payment-service/tips/${segment(profile.string("userId"))}/eligibility") }
                            Action(if (enabled) "Link pausieren" else "Link aktivieren", !state.busy) {
                                vm.submit("/user-service/users/profile", profile.deepCopy().apply { addProperty("paymentEnabled", !enabled) }, "PUT")
                            }
                        }
                    }
                    Transactions(state.data["transactions"].rows(), vm, state)
                }
            }
            if (authenticated && state.profile == null && !state.busy) Action("Profil erneut laden") { vm.refreshProfile() }
        }
    }
    if (accountClose) AlertDialog(onDismissRequest = { accountClose = false }, title = { Text("Konto schließen?") },
        text = { Text("Dein Konto wird geschlossen und dein Trinkgeldlink deaktiviert. Bestehende Zahlungen bleiben erhalten. Pools können das Schließen blockieren.") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { accountClose = false; vm.submit("/user-service/users/account", method = "DELETE", after = { vm.logout() }) }) { Text("Konto schließen") } },
        dismissButton = { TextButton(onClick = { accountClose = false }) { Text("Abbrechen") } })
}
fun paymentLink(path: String): String = if (path.startsWith("https://") || path.startsWith("http://")) path else BuildConfig.WEB_URL.trimEnd('/') + "/" + path.trimStart('/')
@Composable fun QrCard(url: String) {
    val context = LocalContext.current
    val bitmap = remember(url) {
        runCatching {
            val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512)
            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { setPixels(IntArray(512 * 512) { if (bits[it % 512, it / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 512, 0, 0, 512, 512) }
        }.getOrNull()
    }
    var exportError by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null && bitmap != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } ?: error("Die Datei konnte nicht geöffnet werden.")
        }.onFailure { exportError = it.message.orEmpty() }
    }
    OutlinedButton(onClick = { export.launch("gratilo-qr.png") }, enabled = bitmap != null, modifier = Modifier.fillMaxWidth()) { Text("QR-Code speichern") }
    if (exportError.isNotBlank()) Text(exportError)
    bitmap?.let { Image(it.asImageBitmap(), "Trinkgeld QR-Code. Link: $url", Modifier.fillMaxWidth().heightIn(max = 240.dp).aspectRatio(1f)) }
    Text(url, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }, "Trinkgeldlink teilen")) }, modifier = Modifier.fillMaxWidth()) { Text("Link teilen") }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun Transactions(rows: List<JsonObject>, model: GratiloViewModel? = null, state: UiState = UiState()) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Alle") }
    var days by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    Panel("Transaktionen") {
        Field("Referenz oder Beschreibung suchen", query, { query = it; page = 0 })
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Alle", "TIP", "REFUND").forEach { value -> FilterChip(selected = filter == value, onClick = { filter = value; page = 0 }, label = { Text(when(value) { "TIP" -> "Eingänge"; "REFUND" -> "Erstattungen"; else -> value }) }) }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0 to "Alle", 1 to "Heute", 7 to "7 Tage", 30 to "30 Tage").forEach { (value, label) ->
                FilterChip(selected = days == value, onClick = { days = value; page = 0 }, label = { Text(label) })
            }
        }
        val start = java.time.LocalDate.now().minusDays((days - 1).coerceAtLeast(0).toLong()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
        val visible = rows.filter { (filter == "Alle" || it.string("type") == filter) && it.toString().contains(query, true) &&
            (days == 0 || runCatching { !java.time.OffsetDateTime.parse(it.string("createdAt")).toInstant().isBefore(start) }.getOrDefault(false)) }
        val maxPage = ((visible.size - 1).coerceAtLeast(0) / 50)
        val currentPage = page.coerceAtMost(maxPage)
        ExportTransactions(visible)
        if (visible.isEmpty() && !state.busy) Text("Keine Transaktionen vorhanden.")
        visible.drop(currentPage * 50).take(50).forEach { item ->
            OutlinedCard(onClick = { selected = item }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${transactionLabel(item.string("type"))} · ${money(item.long("amountMinor"))}", fontWeight = FontWeight.Bold)
                Text(transactionLabel(item.string("status"))); Text(item.string("createdAt")); Text(item.string("description"))
            } }
        }
        if (visible.size > 50) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page = (currentPage - 1).coerceAtLeast(0) }, enabled = currentPage > 0) { Text("Zurück") }
            Text("${currentPage + 1} / ${maxPage + 1}")
            TextButton(onClick = { page = currentPage + 1 }, enabled = currentPage < maxPage) { Text("Weitere") }
        }
    }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Transaktionsdetails") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            mapOf("id" to "Transaktions-ID", "type" to "Art", "status" to "Status", "createdAt" to "Datum", "reference" to "Referenz", "description" to "Nachricht", "paymentMethod" to "Zahlungsmethode").forEach { (key, label) -> Text("$label: ${transactionLabel(item.string(key))}") }
            Text("Betrag: ${money(item.long("amountMinor"))}")
            if (model != null && item.string("type") == "TIP") {
                Action("Gebührendetails beim Backend prüfen", !state.busy) { model.load("fees:${item.string("id")}", "/payment-service/transactions/${segment(item.string("id"))}/fees") }
                if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
                state.data["fees:${item.string("id")}"]?.obj()?.let { report ->
                    Text("Gebührenabgleich: ${report.string("status")} · ${report.string("checkedAt")}")
                    mapOf("selectedAmountMinor" to "Gewähltes Trinkgeld", "chargedMinor" to "Vom Gast bezahlt", "platformFeeMinor" to "Gratilo-Provision", "estimatedFeeMinor" to "Geschätzte Stripe-Kosten", "actualFeeMinor" to "Bestätigte Stripe-Kosten", "actualRecipientAmountMinor" to "Bestätigter Empfängerbetrag", "refundedMinor" to "Erstatteter Betrag").forEach { (key, label) ->
                        report.get(key)?.takeUnless { it.isJsonNull }?.let { Text("$label: ${money(it.asLong)}") }
                    }
                    Text("Die Auszahlung auf das Bankkonto erfolgt separat. Fehlende Istwerte sind noch nicht bestätigt.")
                }
            }
            item.get("recipientAmountMinor")?.takeUnless { it.isJsonNull }?.let { Text("Empfängerbetrag: ${money(it.asLong)}${if (item.bool("actualNetKnown")) "" else " (geschätzt)"}") }
        } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Schließen") } }) }
}
