package org.rokano.nasremote.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rokano.nasremote.PendingAttachment
import org.rokano.nasremote.RemoteState
import org.rokano.nasremote.RemoteViewModel

data class AttachmentActions(val photos: () -> Unit, val camera: () -> Unit, val files: () -> Unit)

/** Register at the application root: disconnecting must not unregister pending results. */
@Composable
fun rememberAttachmentActions(model: RemoteViewModel): AttachmentActions {
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::attachmentsSelected)
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4), model::attachmentsSelected)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), model::cameraFinished)
    fun launch(action: () -> Unit) {
        if (!model.beginAttachmentPicker()) return
        try { action() } catch (_: Exception) { model.pickerFailed() }
    }
    return AttachmentActions(
        photos = { launch { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
        camera = { launch { camera.launch(model.prepareCamera()) } },
        files = { launch { files.launch(arrayOf("*/*")) } })
}

@Composable
fun AttachmentComposer(state: RemoteState, model: RemoteViewModel, actions: AttachmentActions) {
    val enabled = state.connected && !state.busy && !state.reconnecting && !state.uncertain && !state.preparingAttachment && state.binding != null && state.attachments.size < 4
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.photos, enabled = enabled) { Text("截图 / 相册") }
            TextButton(onClick = actions.camera, enabled = enabled) { Text("拍照") }
            TextButton(onClick = actions.files, enabled = enabled) { Text("文件…") }
        }
        if (state.preparingAttachment) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.attachments.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.attachments.forEach { attachment ->
                    key(attachment.id) {
                        ElevatedCard(Modifier.width(210.dp)) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (attachment.image) AttachmentThumbnail(attachment)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    Text("${(attachment.size + 1023) / 1024} KiB", style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = { model.removeAttachment(attachment.id) }, enabled = !state.busy && !state.preparingAttachment) { Text("移除") }
                                }
                            }
                        }
                    }
                }
            }
            Text("最多 4 个 · 共 20 MiB · 发送前可以移除", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (state.attachmentProgress.isNotBlank()) Text(state.attachmentProgress, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AttachmentThumbnail(attachment: PendingAttachment) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, attachment.id) {
        value = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(attachment.file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 192) sample *= 2
                BitmapFactory.decodeFile(attachment.file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }
    bitmap?.let { Image(it, contentDescription = "待发送图片预览", modifier = Modifier.size(64.dp)) }
}
