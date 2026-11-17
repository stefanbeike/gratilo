package com.gratilo.app.ui

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.gson.JsonObject
import com.gratilo.app.data.*
import java.io.OutputStream

@Composable fun ExportTransactions(rows: List<JsonObject>) {
    val context = LocalContext.current
    var exportError by remember { mutableStateOf("") }
    fun write(uri: Uri?, action: (OutputStream) -> Unit) {
        if(uri == null) return
        runCatching { context.contentResolver.openOutputStream(uri)?.use(action) ?: error("Die Datei konnte nicht geöffnet werden.") }.onFailure { exportError = it.message.orEmpty() }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> write(uri) { output ->
        fun escape(value: String) = "\"" + (if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")) "'" else "") + value.replace("\"", "\"\"") + "\""
        val headers = listOf("Referenz", "Datum", "Typ", "Status", "Betrag (Cent)", "Währung", "Beschreibung")
        val lines = listOf(headers) + rows.map { listOf(it.string("id"), it.string("createdAt"), it.string("type"), it.string("status"), it.string("amountMinor"), it.string("currency"), it.string("description")) }
        output.write(("\uFEFF" + lines.joinToString("\r\n") { it.joinToString(";", transform = ::escape) }).toByteArray(Charsets.UTF_8))
    } }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> write(uri) { output ->
        val document = PdfDocument()
        try {
            val pages = rows.chunked(18).ifEmpty { listOf(emptyList()) }
            val paint = Paint().apply { textSize = 10f; isAntiAlias = true }
            pages.forEachIndexed { index, pageRows ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index+1).create())
                page.canvas.drawText("Gratilo · Transaktionen · Seite ${index+1}", 30f, 35f, paint)
                pageRows.forEachIndexed { row, item ->
                    val y = 75f + row * 40f
                    page.canvas.drawText("${item.string("createdAt").take(10)} · ${item.string("type")} · ${item.string("status")} · ${money(item.long("amountMinor"))}", 30f, y, paint)
                    page.canvas.drawText(item.string("id").take(80), 30f, y+14, paint)
                }
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally { document.close() }
    } }
    OutlinedButton(onClick = { csv.launch("gratilo-transaktionen.csv") }, modifier = Modifier.fillMaxWidth()) { Text("Gefilterte Transaktionen als CSV speichern") }
    OutlinedButton(onClick = { pdf.launch("gratilo-transaktionen.pdf") }, modifier = Modifier.fillMaxWidth()) { Text("Gefilterte Transaktionen als PDF speichern") }
    if(exportError.isNotBlank()) Text(exportError)
}
