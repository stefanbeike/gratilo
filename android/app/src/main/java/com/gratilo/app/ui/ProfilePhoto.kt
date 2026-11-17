package com.gratilo.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable fun ProfilePhoto(value: String?, enabled: Boolean, changed: (String?) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val mime = context.contentResolver.getType(uri)
                    require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Bitte JPG, PNG oder WebP wählen." }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(5 * 1024 * 1024) } ?: error("Das Bild konnte nicht gelesen werden.")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Ungültiges Bild." }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Ungültiges Bild.")
                    val edge = minOf(source.width, source.height)
                    val square = Bitmap.createBitmap(source, (source.width-edge)/2, (source.height-edge)/2, edge, edge)
                    val resized = Bitmap.createScaledBitmap(square, 256, 256, true)
                    val output = ByteArrayOutputStream().apply { resized.compress(Bitmap.CompressFormat.JPEG, 85, this) }.toByteArray()
                    val result = "data:image/jpeg;base64," + Base64.encodeToString(output, Base64.NO_WRAP)
                    require(result.length <= 140000) { "Das vorbereitete Bild ist zu groß." }
                    result
                }
                changed(prepared)
            } catch (failure: Exception) { onError(failure.message ?: "Das Bild konnte nicht geladen werden.") }
            finally { busy = false }
        }
    }
    val bitmap = remember(value) { runCatching { val bytes = Base64.decode(value?.substringAfter(',').orEmpty(), Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
    bitmap?.let { Image(it.asImageBitmap(), "Profilbildvorschau", Modifier.size(96.dp)) }
    OutlinedButton(onClick = { picker.launch("image/*") }, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) { Text(if(busy) "Bild wird vorbereitet …" else "Bild auswählen (JPG, PNG, WebP · bis 5 MB)") }
    if (value != null) TextButton(onClick = { changed(null) }, enabled = enabled && !busy) { Text("Bild entfernen") }
}
private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if(count < 0) break; require(output.size() + count <= limit) { "Das Bild darf höchstens 5 MB groß sein." }; output.write(buffer, 0, count) }
    return output.toByteArray()
}
