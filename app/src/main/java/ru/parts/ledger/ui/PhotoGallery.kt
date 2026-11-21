package ru.parts.ledger.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import ru.parts.ledger.domain.PhotoRef
import ru.parts.ledger.domain.Photos
import ru.parts.ledger.domain.Target

typealias PhotoPicker = (Target, String, Boolean) -> Unit

@Composable
fun CardPhotos(photos: List<PhotoRef>, target: Target, id: String, vm: LedgerViewModel, pick: PhotoPicker) {
    var selected by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(id) { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()
    Text("Фото", style = MaterialTheme.typography.titleMedium)
    if (photos.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(photos, key = { it.id }) { photo ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedCard(onClick = { selected = photo.id }) {
                        PhotoPreview(photo, vm, Modifier.size(82.dp), thumbnail = true, description = "Фото ${photo.ordinal + 1}")
                    }
                    if (photo.ordinal == 0) Text("Обложка", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
    }
    if (photos.size < Photos.MAX_PER_CARD) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick(target, id, true) }, enabled = !busy) { Text("Камера") }
            OutlinedButton(onClick = { pick(target, id, false) }, enabled = !busy) { Text("Галерея") }
        }
    }
    val photo = photos.firstOrNull { it.id == selected }
    if (photo != null) {
        Dialog(onDismissRequest = { selected = null }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PhotoPreview(photo, vm, Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 360.dp), thumbnail = false, description = "Снимок")
                    if (photo.ordinal != 0) TextButton(onClick = { vm.makeCover(photo.id) }, enabled = !busy) { Text("Сделать обложкой") }
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Удалить фото", color = Negative) }
                    TextButton(onClick = { selected = null }) { Text("Закрыть") }
                }
            }
        }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить этот снимок?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; selected = null; vm.deletePhoto(photo.id) }, enabled = !busy) { Text("Удалить", color = Negative) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Оставить") } })
    }
}

@Composable
fun PhotoPreview(photo: PhotoRef, vm: LedgerViewModel, modifier: Modifier, thumbnail: Boolean = true, description: String? = null) {
    var loading by remember(photo.checksum,thumbnail) { mutableStateOf(true) }
    val bitmap by produceState<Bitmap?>(null, photo.id, photo.checksum, thumbnail) {
        value = null
        value = try { vm.photo(photo.id, thumbnail) } catch (e: Exception) { if (e is CancellationException) throw e else null }
        loading = false
    }
    Surface(modifier.clip(MaterialTheme.shapes.small), color = MaterialTheme.colorScheme.surfaceVariant) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = description, contentScale = if (thumbnail) ContentScale.Crop else ContentScale.Fit)
        } ?: Box(contentAlignment = Alignment.Center) { if (!thumbnail) Text(if(loading)"Снимок загружается" else "Снимок не открылся", color = Muted) }
    }
}
