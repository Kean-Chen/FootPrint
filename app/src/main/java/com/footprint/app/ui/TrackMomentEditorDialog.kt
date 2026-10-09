package com.footprint.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.footprint.app.R
import com.footprint.app.data.TrackRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_MOMENT_IMAGES = 8
private const val MAX_MOMENT_TEXT_LENGTH = 10_000
private val MomentGreen = Color(0xFF2F6B55)
private val MomentMuted = Color(0xFF68746D)

private data class PendingMomentImage(val uri: Uri, val temporaryFile: File? = null)

@Composable
internal fun TrackMomentEditorDialog(
    trackId: String,
    pointSequence: Long,
    repository: TrackRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = remember(trackId) { mutableStateListOf<PendingMomentImage>() }
    val tempDirectory = remember(trackId) {
        File(context.cacheDir, "moment-camera/${UUID.randomUUID()}").apply { mkdirs() }
    }
    var pendingCameraFile by remember(trackId) { mutableStateOf<File?>(null) }
    var note by remember(trackId) { mutableStateOf("") }
    var saving by remember(trackId) { mutableStateOf(false) }
    var errorMessage by remember(trackId) { mutableStateOf<String?>(null) }
    var fullScreenImage by remember(trackId) { mutableStateOf<PendingMomentImage?>(null) }

    DisposableEffect(tempDirectory) {
        onDispose { tempDirectory.deleteRecursively() }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_MOMENT_IMAGES),
    ) { selected ->
        val remaining = (MAX_MOMENT_IMAGES - images.size).coerceAtLeast(0)
        images.addAll(selected.take(remaining).map { PendingMomentImage(it) })
        if (selected.size > remaining) errorMessage = "一次最多添加 $MAX_MOMENT_IMAGES 张图片"
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) {
            images += PendingMomentImage(
                uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
                temporaryFile = file,
            )
        } else {
            file?.delete()
        }
    }

    val addFromCamera: () -> Unit = {
        if (images.size >= MAX_MOMENT_IMAGES) {
            errorMessage = "一次最多添加 $MAX_MOMENT_IMAGES 张图片"
        } else {
            runCatching {
                val file = File(tempDirectory, "${UUID.randomUUID()}.jpg")
                file.parentFile?.mkdirs()
                pendingCameraFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraLauncher.launch(uri)
            }.onFailure {
                pendingCameraFile?.delete()
                pendingCameraFile = null
                errorMessage = "无法打开相机：${it.message ?: "请稍后重试"}"
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F4EF)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("记录此刻", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("取消", color = MomentMuted) }
                }
                Text(
                    "已关联到第 ${pointSequence + 1} 个轨迹点的位置",
                    color = MomentMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= MAX_MOMENT_TEXT_LENGTH) note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("写下此刻的想法") },
                    placeholder = { Text("例如：在湖边休息，风景很好……") },
                    minLines = 4,
                    maxLines = 8,
                    shape = RoundedCornerShape(16.dp),
                )
                Text(
                    "${note.length}/$MAX_MOMENT_TEXT_LENGTH",
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp),
                    color = MomentMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = addFromCamera,
                        enabled = !saving && images.size < MAX_MOMENT_IMAGES,
                    ) {
                        Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("拍照")
                    }
                    OutlinedButton(
                        onClick = {
                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = !saving && images.size < MAX_MOMENT_IMAGES,
                    ) {
                        Icon(painterResource(R.drawable.ic_image), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选择图片")
                    }
                }
                if (images.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("图片 ${images.size}/$MAX_MOMENT_IMAGES", color = MomentMuted, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(7.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(images, key = { index, image -> image.uri.toString() + index }) { index, image ->
                            Box(Modifier.size(112.dp)) {
                                MomentImage(
                                    uri = image.uri,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.White, RoundedCornerShape(14.dp)),
                                    onClick = { fullScreenImage = image },
                                )
                                Surface(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.55f),
                                ) {
                                    IconButton(
                                        onClick = {
                                            images.removeAt(index)
                                            image.temporaryFile?.delete()
                                        },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "移除图片", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                errorMessage?.let { message ->
                    Text(message, color = Color(0xFFB33C32), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = {
                        saving = true
                        errorMessage = null
                        scope.launch {
                            runCatching {
                                repository.addMoment(trackId, pointSequence, note, images.map { it.uri })
                            }.onSuccess {
                                saving = false
                                onSaved()
                            }.onFailure { error ->
                                saving = false
                                errorMessage = error.message ?: "保存失败，请重试"
                            }
                        }
                    },
                    enabled = !saving && (note.isNotBlank() || images.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MomentGreen),
                ) {
                    Text(if (saving) "保存中…" else "保存到轨迹")
                }
            }
        }
    }

    fullScreenImage?.let { image ->
        MomentImageViewerDialog(
            uri = image.uri,
            onDismiss = { fullScreenImage = null },
        )
    }
}

@Composable
internal fun MomentImage(
    modifier: Modifier = Modifier,
    uri: Uri? = null,
    file: File? = null,
    onClick: (() -> Unit)? = null,
    fullSize: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri, file, fullSize) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                decodeThumbnail(context, file, uri, if (fullSize) 2048 else 640)
            }.getOrNull()
        }
    }
    val imageModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "图文记录图片",
            modifier = imageModifier,
            contentScale = contentScale,
        )
    } else {
        Box(imageModifier.background(Color(0xFFE8EAE6), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text("图片", color = MomentMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun MomentImageViewerDialog(
    uri: Uri? = null,
    file: File? = null,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            MomentImage(
                uri = uri,
                file = file,
                fullSize = true,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Text("关闭", color = Color.White)
            }
        }
    }
}

private fun decodeThumbnail(
    context: android.content.Context,
    file: File?,
    uri: Uri?,
    maxDimension: Int,
): Bitmap? {
    fun openStream() = if (file != null) file.inputStream() else uri?.let { context.contentResolver.openInputStream(it) }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / maxDimension).coerceAtLeast(1)
    }
    val decoded = openStream()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val orientation = runCatching { openStream()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
        .getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val rotation = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (rotation == 0f) return decoded
    val matrix = Matrix().apply { postRotate(rotation) }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
        if (it !== decoded) decoded.recycle()
    }
}
