package org.rokano.nasremote.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
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

@Composable
fun AttachmentComposer(state: RemoteState, model: RemoteViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::attachmentsSelected)
    val enabled = state.connected && !state.busy && !state.reconnecting && !state.preparingAttachment && state.binding != null && state.attachments.size < 4
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (model.beginAttachmentPicker()) picker.launch(arrayOf("image/*")) }, enabled = enabled) { Text("＋ 图片") }
            TextButton(onClick = { if (model.beginAttachmentPicker()) picker.launch(arrayOf("*/*")) }, enabled = enabled) { Text("＋ 文件") }
            Text("最多 4 个 · 共 20 MiB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("上传到 NAS 私有目录，交给当前 Codex 读取；图片由图片工具查看。", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
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
