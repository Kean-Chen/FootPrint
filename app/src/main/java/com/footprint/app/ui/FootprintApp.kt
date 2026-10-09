package com.footprint.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.footprint.app.BuildConfig
import com.footprint.app.FootprintApplication
import com.footprint.app.data.TrackEntity
import com.footprint.app.data.TrackMomentWithAttachments
import com.footprint.app.map.AMapTrackMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val Ink = Color(0xFF202B25)
private val Muted = Color(0xFF68746D)
private val Paper = Color(0xFFF5F4EF)
private val Green = Color(0xFF2F6B55)

@Composable
fun FootprintApp(
    initiallyConsented: Boolean,
    onAcceptPrivacy: () -> Unit,
    onDeclinePrivacy: () -> Unit,
) {
    var consented by remember { mutableStateOf(initiallyConsented) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Paper, contentColor = Ink) {
            if (!consented) {
                PrivacyScreen(
                    onAccept = {
                        onAcceptPrivacy()
                        consented = true
                    },
                    onDecline = onDeclinePrivacy,
                )
            } else {
                MapHomeRoot()
            }
        }
    }
}

@Composable
private fun PrivacyScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(WindowInsets.navigationBars.asPaddingValues()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "FootPrint",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text("把周末走过的路，安静地留在自己手里。", color = Muted)
        Spacer(Modifier.height(30.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp)) {
                Text("关于定位与隐私", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "允许定位后，首页地图会获取当前位置用于居中显示；此位置不会保存为轨迹。开始记录后，轨迹点保存在本机；你手动添加的照片和文字也会保存在本机。高德地图 SDK 用于地图显示和定位；记录时 App 会显示持续通知。",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text("只有你开始记录时才保存轨迹点。你可以随时结束或删除记录；加密备份需要你设置并保管密码。", color = Muted)
            }
        }
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) {
            Text("同意并继续")
        }
        TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
            Text(
                "暂不使用并退出",
                color = Muted
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun TrackDetailScreen(
    trackId: String,
    onBack: () -> Unit,
    onDelete: (TrackEntity) -> Unit,
) {
    val context = LocalContext.current
    val repository = (context.applicationContext as FootprintApplication).tracks
    var track by remember(trackId) { mutableStateOf<TrackEntity?>(null) }
    var title by remember(trackId) { mutableStateOf("") }
    var note by remember(trackId) { mutableStateOf("") }
    val points by repository.observePoints(trackId).collectAsState(initial = emptyList())
    val moments by repository.observeMoments(trackId).collectAsState(initial = emptyList())
    val pointsBySequence = remember(points) { points.associateBy { it.sequence } }
    val momentPoints = moments.mapNotNull { item ->
        item.moment.pointSequence?.let(pointsBySequence::get)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(trackId) {
        val loaded = repository.getTrack(trackId)
        track = loaded
        title = loaded?.title.orEmpty()
        note = loaded?.note.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(WindowInsets.navigationBars.asPaddingValues()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = Green) }
            Text("足迹详情", fontWeight = FontWeight.SemiBold)
        }
        if (track == null) {
            Text("正在读取…", modifier = Modifier.padding(20.dp), color = Muted)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    AMapTrackMap(
                        points = points,
                        momentPoints = momentPoints,
                        hasApiKey = BuildConfig.AMAP_API_KEY != "YOUR_AMAP_API_KEY",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .padding(horizontal = 16.dp),
                    )
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(formatDate(track!!.startedAt), color = Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Metric("距离", formatDistance(track!!.distanceMeters))
                            Metric("时长", formatDuration(track!!.durationSeconds))
                            Metric("轨迹点", "${points.size}")
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("备注") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    scope.launch { repository.updateDescription(trackId, title, note) }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Green),
                            ) { Text("保存") }
                            OutlinedButton(onClick = { onDelete(track!!) }, modifier = Modifier.weight(1f)) {
                                Text("删除足迹")
                            }
                        }
                    }
                }
                item {
                    Text(
                        "图文记录（${moments.size}）",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (moments.isEmpty()) {
                    item {
                        Text(
                            "记录过程中添加的照片和文字会显示在这里。",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    items(moments, key = { it.moment.id }) { moment ->
                        TrackMomentCard(
                            moment = moment,
                            attachmentFile = repository::attachmentFile,
                            onDelete = { scope.launch { repository.deleteMoment(moment.moment.id) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMomentCard(
    moment: TrackMomentWithAttachments,
    attachmentFile: (String) -> java.io.File?,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember(moment.moment.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDate(moment.moment.createdAt), color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                moment.moment.pointSequence?.let { sequence ->
                    Text("轨迹点 #${sequence + 1}", color = Green, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { confirmDelete = true }) { Text("删除", color = Color(0xFFB33C32)) }
            }
            if (moment.moment.text.isNotBlank()) {
                Text(moment.moment.text, color = Ink, style = MaterialTheme.typography.bodyMedium)
            }
            if (moment.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(moment.attachments.sortedBy { it.createdAt }, key = { it.id }) { attachment ->
                        MomentImage(
                            file = attachmentFile(attachment.relativePath),
                            modifier = Modifier
                                .size(112.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFE8EAE6), RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除图文记录") },
            text = { Text("将同时删除这条记录关联的本机图片。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = Color(0xFFB33C32)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

private fun formatDistance(meters: Double): String = if (meters < 1_000) {
    "${meters.toInt()} 米"
} else {
    String.format(Locale.getDefault(), "%.2f 公里", meters / 1_000)
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainder = seconds % 60
    return if (hours > 0) String.format(
        Locale.getDefault(),
        "%d:%02d:%02d",
        hours,
        minutes,
        remainder
    )
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, remainder)
}

private fun formatDate(timestamp: Long) =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
