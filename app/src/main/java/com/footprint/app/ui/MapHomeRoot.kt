package com.footprint.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.footprint.app.BuildConfig
import com.footprint.app.FootprintApplication
import com.footprint.app.R
import com.footprint.app.data.TrackEntity
import com.footprint.app.data.TrackPointEntity
import com.footprint.app.data.TrackStatus
import com.footprint.app.data.backup.TrackBackupManager
import com.footprint.app.data.backup.CreatedTrackBackup
import com.footprint.app.data.backup.EncryptedTrackBackup
import com.footprint.app.data.backup.PreparedTrackBackup
import com.footprint.app.data.backup.StagedTrackBackup
import com.footprint.app.location.LocationForegroundService
import com.footprint.app.map.AMapTrackMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HomeInk = Color(0xFF202B25)
private val HomeMuted = Color(0xFF68746D)
private val HomePaper = Color(0xFFF5F4EF)
private val HomeGreen = Color(0xFF2F6B55)
private val HomePaleGreen = Color(0xFFE4ECE5)
private const val LIVE_SPEED_FRESHNESS_MILLIS = 12_000L

@Composable
internal fun MapHomeRoot() {
    val context = LocalContext.current
    val application = context.applicationContext as FootprintApplication
    val tracks by application.tracks.observeTracks().collectAsState(initial = emptyList())
    val locationWarning by application.locationWarning.collectAsState()
    val trackFinishNotice by application.trackFinishNotice.collectAsState()
    val openTrack = tracks.firstOrNull {
        it.status == TrackStatus.RECORDING || it.status == TrackStatus.PAUSED || it.status == TrackStatus.INTERRUPTED
    }
    val activePointsFlow: Flow<List<TrackPointEntity>>? = remember(openTrack?.id) {
        openTrack?.let { application.tracks.observePoints(it.id) }
    }
    val activePoints = activePointsFlow?.collectAsState(initial = emptyList<TrackPointEntity>())?.value.orEmpty()
    val scope = rememberCoroutineScope()
    val backupManager = remember(context, application.database) {
        TrackBackupManager(context, application.database)
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var trackToDelete by remember { mutableStateOf<TrackEntity?>(null) }
    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    var momentEditorTrackId by remember { mutableStateOf<String?>(null) }
    var momentEditorPointSequence by remember { mutableStateOf<Long?>(null) }
    var momentMessage by remember { mutableStateOf<String?>(null) }
    var startupRecoveryTrackId by remember { mutableStateOf<String?>(null) }
    var showingHistory by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var stagedRestore by remember { mutableStateOf<StagedTrackBackup?>(null) }
    var encryptedRestore by remember { mutableStateOf<EncryptedTrackBackup?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var restorePasswordError by remember { mutableStateOf<String?>(null) }
    var exportTrackIds by remember { mutableStateOf<Set<String>?>(null) }
    var exportPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirmation by remember { mutableStateOf("") }
    var exportPasswordError by remember { mutableStateOf<String?>(null) }
    var pendingExportPassword by remember { mutableStateOf<CharArray?>(null) }
    var locationPermissionGranted by remember {
        mutableStateOf(hasLocationPermission(context))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationPermissionGranted = hasLocationPermission(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(openTrack?.id, openTrack?.status) {
        while (openTrack?.status == TrackStatus.RECORDING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LaunchedEffect(application) {
        if (application.hasCheckedUnfinishedTrackAtStartup) return@LaunchedEffect
        application.hasCheckedUnfinishedTrackAtStartup = true
        val candidate = withContext(Dispatchers.IO) { application.tracks.getOpenTrack() }
            ?: return@LaunchedEffect
        // Give Android's sticky foreground service time to restart after process recreation.
        delay(1_500)
        if (LocationForegroundService.isRecordingActive) return@LaunchedEffect
        val current = withContext(Dispatchers.IO) { application.tracks.getTrack(candidate.id) }
            ?: return@LaunchedEffect
        // A pause performed during this Activity session is a deliberate user action.
        if (candidate.status == TrackStatus.RECORDING && current.status == TrackStatus.PAUSED) {
            return@LaunchedEffect
        }
        when (current.status) {
            TrackStatus.RECORDING -> {
                withContext(Dispatchers.IO) {
                    application.tracks.markInterruptedAtLastPoint(current.id)
                }
                startupRecoveryTrackId = current.id
            }
            TrackStatus.PAUSED, TrackStatus.INTERRUPTED -> startupRecoveryTrackId = current.id
            else -> Unit
        }
    }

    val currentLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationPermissionGranted = hasLocationPermission(context)
        if (!locationPermissionGranted) {
            permissionMessage = "需要允许定位权限，首页地图才能显示当前位置。"
        }
    }
    val recordingPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val precise = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locationPermissionGranted = precise || coarse
        when {
            precise -> startLocationService(context)
            coarse -> permissionMessage = "轨迹记录需要精确定位。请在位置权限提示中选择“精确位置”，然后重新点击开始记录。"
            else -> permissionMessage = "需要定位权限才能开始记录。请在系统设置中允许后重试。"
        }
    }

    val requestEncryptedExport: (Set<String>?) -> Unit = { trackIds ->
        exportTrackIds = trackIds
        exportPassword = ""
        exportPasswordConfirmation = ""
        exportPasswordError = null
        exportPasswordDialog = true
    }

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri != null && password != null) {
            val requestedTrackIds = exportTrackIds
            exportTrackIds = null
            scope.launch {
                backupBusy = true
                var createdBackup: CreatedTrackBackup? = null
                try {
                    val generatedBackup = withContext(Dispatchers.IO) {
                        backupManager.createBackup(requestedTrackIds, password)
                    }
                    createdBackup = generatedBackup
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("无法写入所选位置")
                        output.use { destination ->
                            generatedBackup.file.inputStream().use { source -> source.copyTo(destination) }
                        }
                    }
                    backupMessage = "加密备份完成：${generatedBackup.trackCount} 条轨迹、${generatedBackup.pointCount} 个轨迹点、${generatedBackup.momentCount} 条图文记录和 ${generatedBackup.attachmentCount} 张图片。恢复时需要输入刚才设置的密码，请妥善保存。"
                } catch (error: Exception) {
                    backupMessage = "备份失败：${error.message ?: "无法写入备份文件"}"
                } finally {
                    password.fill(0.toChar())
                    createdBackup?.let { backup ->
                        withContext(Dispatchers.IO) { backupManager.cleanup(backup) }
                    }
                    backupBusy = false
                }
            }
        } else {
            password?.fill(0.toChar())
            exportTrackIds = null
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                backupBusy = true
                try {
                    val prepared = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("无法读取所选备份文件")
                        input.use(backupManager::prepareBackup)
                    }
                    when (prepared) {
                        is PreparedTrackBackup.Ready -> stagedRestore = prepared.backup
                        is PreparedTrackBackup.PasswordRequired -> encryptedRestore = prepared.backup
                    }
                } catch (error: Exception) {
                    backupMessage = "备份校验失败：${error.message ?: "文件无法读取或格式不受支持"}"
                } finally {
                    backupBusy = false
                }
            }
        }
    }

    val unlockEncryptedBackup: () -> Unit = {
        val backup = encryptedRestore
        val password = restorePassword.toCharArray()
        restorePassword = ""
        restorePasswordError = null
        if (backup != null && password.isNotEmpty()) {
            scope.launch {
                backupBusy = true
                try {
                    val staged = withContext(Dispatchers.IO) {
                        backupManager.stageEncryptedBackup(backup, password)
                    }
                    encryptedRestore = null
                    withContext(Dispatchers.IO) { backupManager.cleanup(backup) }
                    stagedRestore = staged
                } catch (error: Exception) {
                    restorePasswordError = error.message ?: "无法解密备份"
                } finally {
                    password.fill(0.toChar())
                    backupBusy = false
                }
            }
        } else {
            password.fill(0.toChar())
            if (backup != null) restorePasswordError = "请输入备份密码"
        }
    }

    val startRecording = {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val permissionsToRequest = buildList {
            if (!fineGranted) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsToRequest.isEmpty()) {
            startLocationService(context)
        } else {
            recordingPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    BackHandler(enabled = showingHistory || selectedTrackId != null || momentEditorTrackId != null) {
        if (momentEditorTrackId != null) {
            momentEditorTrackId = null
            momentEditorPointSequence = null
        } else if (selectedTrackId != null) {
            selectedTrackId = null
            showingHistory = true
        } else {
            showingHistory = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            selectedTrackId != null -> TrackDetailScreen(
                trackId = selectedTrackId!!,
                onBack = {
                    selectedTrackId = null
                    showingHistory = true
                },
                onDelete = {
                    selectedTrackId = null
                    showingHistory = true
                    trackToDelete = it
                },
            )

            showingHistory -> TrackHistoryPage(
                tracks = tracks,
                backupBusy = backupBusy,
                onBack = { showingHistory = false },
                onOpen = {
                    selectedTrackId = it
                    showingHistory = false
                },
                onDelete = { trackToDelete = it },
                onCreateBackup = { requestEncryptedExport(null) },
                onExportSelected = { selectedIds -> requestEncryptedExport(selectedIds) },
                onRestoreBackup = {
                    openBackupLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                    )
                },
            )

            else -> MapHomeScreen(
                openTrack = openTrack,
                points = activePoints,
                now = now,
                locationPermissionGranted = locationPermissionGranted,
                onOpenHistory = { showingHistory = true },
                onStart = startRecording,
                onPause = {
                    openTrack?.let { sendAction(context, LocationForegroundService.ACTION_PAUSE, it.id) }
                },
                onResume = {
                    openTrack?.let { sendAction(context, LocationForegroundService.ACTION_RESUME, it.id) }
                },
                onFinish = {
                    openTrack?.let { sendAction(context, LocationForegroundService.ACTION_FINISH, it.id) }
                },
                onAddMoment = {
                    val track = openTrack
                    val anchorPoint = activePoints.lastOrNull()
                    when {
                        track == null -> momentMessage = "请先开始记录轨迹，再添加图文记录。"
                        anchorPoint == null -> momentMessage = "还没有保存的轨迹点。请等待定位并保存首个轨迹点后重试。"
                        else -> {
                            momentEditorTrackId = track.id
                            momentEditorPointSequence = anchorPoint.sequence
                        }
                    }
                },
                onDelete = { openTrack?.let { trackToDelete = it } },
                onRequestLocation = {
                    currentLocationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
            )
        }

        momentEditorTrackId?.let { editingTrackId ->
            momentEditorPointSequence?.let { pointSequence ->
                TrackMomentEditorDialog(
                    trackId = editingTrackId,
                    pointSequence = pointSequence,
                    repository = application.tracks,
                    onDismiss = {
                        momentEditorTrackId = null
                        momentEditorPointSequence = null
                    },
                    onSaved = {
                        momentEditorTrackId = null
                        momentEditorPointSequence = null
                    },
                )
            }
        }

        momentMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { momentMessage = null },
                title = { Text("图文记录") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { momentMessage = null }) { Text("知道了") }
                },
            )
        }

        if (momentMessage == null) {
            locationWarning?.let { message ->
                AlertDialog(
                    onDismissRequest = { application.setLocationWarning(null) },
                    title = { Text("定位暂不可用") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { application.setLocationWarning(null) }) { Text("知道了") }
                    },
                )
            }
        }

        if (momentMessage == null && locationWarning == null) {
            trackFinishNotice?.let { message ->
                AlertDialog(
                    onDismissRequest = { application.setTrackFinishNotice(null) },
                    title = { Text("本次记录未保存") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { application.setTrackFinishNotice(null) }) { Text("知道了") }
                    },
                )
            }
        }

        permissionMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { permissionMessage = null },
                title = { Text("需要权限") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { permissionMessage = null }) { Text("知道了") }
                },
            )
        }

        tracks.firstOrNull { it.id == startupRecoveryTrackId }?.let { track ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("发现尚未结束的记录") },
                text = {
                    Text(
                        if (activePoints.isEmpty()) {
                            "“${track.title}”目前仍未结束，且没有有效轨迹点。可以继续记录；若结束，本次空记录会被删除。"
                        } else {
                            "“${track.title}”目前仍未结束。已保存的轨迹点仍在本机。你可以继续记录，或结束并保存这条记录。"
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        startupRecoveryTrackId = null
                        sendAction(context, LocationForegroundService.ACTION_RESUME, track.id)
                    }) { Text("继续记录") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        startupRecoveryTrackId = null
                        sendAction(context, LocationForegroundService.ACTION_FINISH, track.id)
                    }) { Text(if (activePoints.isEmpty()) "结束并删除" else "结束记录") }
                },
            )
        }

        if (exportPasswordDialog) {
            AlertDialog(
                onDismissRequest = {
                    exportPasswordDialog = false
                    exportPassword = ""
                    exportPasswordConfirmation = ""
                    exportPasswordError = null
                    exportTrackIds = null
                },
                title = { Text("设置备份密码") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("新备份会加密。恢复或分享给其他用户时，需要提供此密码；密码无法找回。")
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text("密码（至少 8 个字符）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        OutlinedTextField(
                            value = exportPasswordConfirmation,
                            onValueChange = { exportPasswordConfirmation = it },
                            label = { Text("再次输入密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        exportPasswordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        when {
                            exportPassword.length < 8 -> exportPasswordError = "密码至少需要 8 个字符。"
                            exportPassword != exportPasswordConfirmation -> exportPasswordError = "两次输入的密码不一致。"
                            else -> {
                                pendingExportPassword = exportPassword.toCharArray()
                                exportPassword = ""
                                exportPasswordConfirmation = ""
                                exportPasswordDialog = false
                                exportPasswordError = null
                                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                                createBackupLauncher.launch("FootPrint-backup-$timestamp.fpbackup")
                            }
                        }
                    }) { Text("继续") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        exportPasswordDialog = false
                        exportPassword = ""
                        exportPasswordConfirmation = ""
                        exportPasswordError = null
                        exportTrackIds = null
                    }) { Text("取消") }
                },
            )
        }

        encryptedRestore?.let { backup ->
            AlertDialog(
                onDismissRequest = {
                    encryptedRestore = null
                    restorePassword = ""
                    restorePasswordError = null
                    scope.launch(Dispatchers.IO) { backupManager.cleanup(backup) }
                },
                title = { Text("输入备份密码") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("该备份已加密。请输入导出时设置的密码。")
                        OutlinedTextField(
                            value = restorePassword,
                            onValueChange = { restorePassword = it },
                            label = { Text("备份密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !backupBusy,
                        )
                        restorePasswordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = unlockEncryptedBackup, enabled = !backupBusy) {
                        Text(if (backupBusy) "正在解密…" else "解密并校验")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        encryptedRestore = null
                        restorePassword = ""
                        restorePasswordError = null
                        scope.launch(Dispatchers.IO) { backupManager.cleanup(backup) }
                    }, enabled = !backupBusy) { Text("取消") }
                },
            )
        }

        stagedRestore?.let { backup ->
            val backupDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(backup.createdAt))
            AlertDialog(
                onDismissRequest = {
                    stagedRestore = null
                    scope.launch(Dispatchers.IO) { backupManager.cleanup(backup) }
                },
                title = { Text("确认恢复备份") },
                text = {
                    Text(
                        "备份时间：$backupDate\n包含 ${backup.trackCount} 条轨迹、${backup.pointCount} 个轨迹点、${backup.momentCount} 条图文记录和 ${backup.attachmentCount} 张图片。导入会与本机记录合并；相同 ID 的轨迹会跳过，不会清空现有数据。未完成轨迹会恢复为“待继续”。" +
                            if (backup.encrypted) "此备份已通过密码解密。" else "这是旧版未加密 ZIP，包含精确位置数据。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        stagedRestore = null
                        scope.launch {
                            backupBusy = true
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    backupManager.restoreBackup(backup)
                                }
                                backupMessage = "恢复完成：导入 ${result.importedTrackCount} 条轨迹、${result.importedPointCount} 个轨迹点、${result.importedMomentCount} 条图文记录和 ${result.importedAttachmentCount} 张图片；跳过 ${result.skippedTrackCount} 条同 ID 记录。"
                            } catch (error: Exception) {
                                backupMessage = "恢复失败，数据库已回滚：${error.message ?: "备份无法导入"}"
                            } finally {
                                withContext(Dispatchers.IO) { backupManager.cleanup(backup) }
                                backupBusy = false
                            }
                        }
                    }) { Text("合并导入") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        stagedRestore = null
                        scope.launch(Dispatchers.IO) { backupManager.cleanup(backup) }
                    }) { Text("取消") }
                },
            )
        }

        backupMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { backupMessage = null },
                title = { Text("备份与恢复") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { backupMessage = null }) { Text("知道了") }
                },
            )
        }

        trackToDelete?.let { track ->
            AlertDialog(
                onDismissRequest = { trackToDelete = null },
                title = { Text("删除这条足迹？") },
                text = { Text("“${track.title}”及其轨迹点会从本机删除。") },
                confirmButton = {
                    TextButton(onClick = {
                        trackToDelete = null
                        scope.launch {
                            if (track.status == TrackStatus.RECORDING || track.status == TrackStatus.PAUSED || track.status == TrackStatus.INTERRUPTED) {
                                sendAction(context, LocationForegroundService.ACTION_DISCARD, track.id)
                            } else {
                                application.tracks.deleteTrack(track.id)
                            }
                        }
                    }) { Text("删除", color = Color(0xFFB33C32)) }
                },
                dismissButton = { TextButton(onClick = { trackToDelete = null }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun MapHomeScreen(
    openTrack: TrackEntity?,
    points: List<TrackPointEntity>,
    now: Long,
    locationPermissionGranted: Boolean,
    onOpenHistory: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onAddMoment: () -> Unit,
    onDelete: () -> Unit,
    onRequestLocation: () -> Unit,
) {
    var recenterRequest by remember { mutableStateOf(0) }
    var compassResetRequest by remember { mutableStateOf(0) }
    var mapBearing by remember { mutableStateOf(0f) }
    val lastPoint = points.lastOrNull()
    val liveSpeedMetersPerSecond = lastPoint
        ?.takeIf {
            openTrack?.status == TrackStatus.RECORDING &&
                now - it.recordedAt in 0L..LIVE_SPEED_FRESHNESS_MILLIS
        }
        ?.speedMetersPerSecond

    Box(Modifier.fillMaxSize()) {
        AMapTrackMap(
            points = points,
            hasApiKey = BuildConfig.AMAP_API_KEY != "YOUR_AMAP_API_KEY",
            showCurrentLocation = true,
            locationPermissionGranted = locationPermissionGranted,
            showLiveTrackLocation = openTrack?.status == TrackStatus.RECORDING,
            showCompass = false,
            recenterRequest = recenterRequest,
            compassResetRequest = compassResetRequest,
            onCameraBearingChanged = { bearing ->
                val normalizedBearing = ((bearing % 360f) + 360f) % 360f
                if (mapBearing.toInt() != normalizedBearing.toInt()) mapBearing = normalizedBearing
            },
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            text = "FootPrint",
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 22.dp, top = 14.dp),
            color = HomeGreen.copy(alpha = 0.32f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Surface(
                modifier = Modifier.align(Alignment.End).padding(bottom = 8.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.96f),
                shadowElevation = 6.dp,
            ) {
                IconButton(
                    onClick = { compassResetRequest++ },
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).rotate(-mapBearing),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_compass),
                            contentDescription = "指南针，N 指北，S 指南，点击恢复正北",
                            tint = Color.Unspecified,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            "N",
                            modifier = Modifier.offset(y = (-6).dp),
                            color = Color.White,
                            fontSize = 8.sp,
                            lineHeight = 8.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "S",
                            modifier = Modifier.offset(y = 6.dp),
                            color = Color.White,
                            fontSize = 8.sp,
                            lineHeight = 8.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            val showLocationAction = !locationPermissionGranted ||
                openTrack?.status != TrackStatus.RECORDING || points.isEmpty()
            if (showLocationAction) {
                Surface(
                    modifier = Modifier.align(Alignment.End).padding(bottom = 8.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.96f),
                    shadowElevation = 6.dp,
                ) {
                    IconButton(
                        onClick = {
                            if (locationPermissionGranted) recenterRequest++ else onRequestLocation()
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_my_location),
                            contentDescription = if (locationPermissionGranted) "回到当前位置" else "开启当前位置",
                            tint = HomeGreen,
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.82f),
                shadowElevation = 10.dp,
            ) {
                if (openTrack == null) {
                    Row(
                        modifier = Modifier.padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("开始一段漫步", fontWeight = FontWeight.SemiBold)
                        }
                        RoundIconAction(
                            icon = R.drawable.ic_history,
                            label = "足迹",
                            description = "打开足迹列表",
                            onClick = onOpenHistory,
                        )
                        RoundIconAction(
                            icon = R.drawable.ic_record,
                            label = "开始",
                            description = "开始记录轨迹",
                            onClick = onStart,
                            containerColor = HomeGreen,
                            iconColor = Color.White,
                            size = 54.dp,
                        )
                    }
                } else {
                    val elapsed = openTrack.durationSeconds + if (openTrack.status == TrackStatus.RECORDING) {
                        openTrack.activeSince?.let { ((now - it).coerceAtLeast(0) / 1000) } ?: 0
                    } else 0
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(
                                            if (openTrack.status == TrackStatus.RECORDING) HomeGreen else HomeMuted,
                                            CircleShape,
                                        ),
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(statusLabel(openTrack.status), fontWeight = FontWeight.SemiBold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatSpeed(liveSpeedMetersPerSecond),
                                    color = HomeInk,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("实时速度", color = HomeMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "${formatDuration(elapsed)} · ${formatDistance(openTrack.distanceMeters)} · ${points.size} 点",
                                color = HomeMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        RoundIconAction(
                            R.drawable.ic_note_add,
                            "图文",
                            if (points.isNotEmpty()) "添加图文记录" else "等待首个轨迹点保存后添加图文记录",
                            onAddMoment,
                            containerColor = Color(0xFFE8EEDC),
                            iconColor = HomeGreen,
                        )
                        if (openTrack.status == TrackStatus.RECORDING) {
                            RoundIconAction(R.drawable.ic_pause, "暂停", "暂停记录", onPause)
                        } else {
                            RoundIconAction(R.drawable.ic_play, "继续", "继续记录", onResume)
                        }
                        RoundIconAction(
                            R.drawable.ic_stop,
                            "结束",
                            "结束并保存轨迹",
                            onFinish,
                            containerColor = Color(0xFFF5E7E4),
                            iconColor = Color(0xFFB33C32),
                        )
                        RoundIconAction(
                            R.drawable.ic_delete,
                            "删除",
                            "删除当前轨迹",
                            onDelete,
                            containerColor = Color(0xFFF1F2EF),
                            iconColor = HomeMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIconAction(
    icon: Int,
    label: String,
    description: String,
    onClick: () -> Unit,
    containerColor: Color = HomePaleGreen,
    iconColor: Color = HomeGreen,
    size: Dp = 46.dp,
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = containerColor, modifier = Modifier.size(size)) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = description,
                    tint = iconColor,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = HomeMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TrackHistoryPage(
    tracks: List<TrackEntity>,
    backupBusy: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (TrackEntity) -> Unit,
    onCreateBackup: () -> Unit,
    onExportSelected: (Set<String>) -> Unit,
    onRestoreBackup: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val filteredTracks = tracks.filter { track ->
        val query = searchQuery.trim()
        query.isEmpty() || track.title.contains(query, ignoreCase = true) ||
            track.note.contains(query, ignoreCase = true)
    }
    LaunchedEffect(tracks) {
        val currentIds = tracks.mapTo(HashSet<String>()) { it.id }
        selectedTrackIds = selectedTrackIds.intersect(currentIds)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomePaper)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(WindowInsets.navigationBars.asPaddingValues()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "返回地图",
                    tint = HomeInk,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("我的足迹", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${tracks.size} 条记录 · 保存在本机", color = HomeMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onCreateBackup,
                enabled = !backupBusy && tracks.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (backupBusy) "处理中…" else "导出全部")
            }
            Button(
                onClick = onRestoreBackup,
                enabled = !backupBusy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = HomeGreen),
            ) {
                Text(if (backupBusy) "处理中…" else "恢复备份")
            }
        }
        Text(
            "加密备份包含轨迹、图文文字和图片；旧版 ZIP 仍可恢复，但不含图片附件。",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            color = HomeMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = {
                selectionMode = !selectionMode
                selectedTrackIds = emptySet()
            },
            enabled = !backupBusy && tracks.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(if (selectionMode) "取消选择" else "勾选轨迹导出")
        }
        if (selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { selectedTrackIds = selectedTrackIds + filteredTracks.map { it.id } },
                    enabled = !backupBusy && filteredTracks.isNotEmpty(),
                ) { Text("全选当前结果") }
                TextButton(
                    onClick = { selectedTrackIds = emptySet() },
                    enabled = !backupBusy && selectedTrackIds.isNotEmpty(),
                ) { Text("清空") }
            }
            Button(
                onClick = { onExportSelected(selectedTrackIds) },
                enabled = !backupBusy && selectedTrackIds.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HomeGreen),
            ) { Text("导出所选 (${selectedTrackIds.size})") }
        }
        if (tracks.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("搜索名称或备注") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        }
        if (filteredTracks.isEmpty()) {
            Text(
                if (tracks.isEmpty()) "还没有足迹。开始记录后，轨迹会显示在这里。" else "没有匹配的足迹。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                color = HomeMuted,
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
            ) {
                items(filteredTracks, key = { it.id }) { track ->
                    HistoryTrackCard(
                        track = track,
                        selectionMode = selectionMode,
                        selected = track.id in selectedTrackIds,
                        onOpen = { onOpen(track.id) },
                        onDelete = { onDelete(track) },
                        onToggleSelected = {
                            selectedTrackIds = if (track.id in selectedTrackIds) {
                                selectedTrackIds - track.id
                            } else {
                                selectedTrackIds + track.id
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryTrackCard(
    track: TrackEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(onClick = if (selectionMode) onToggleSelected else onOpen),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 15.dp, end = 7.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(track.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(statusLabel(track.status), color = HomeGreen, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(4.dp))
                val duration = track.durationSeconds + if (track.status == TrackStatus.RECORDING) {
                    track.activeSince?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0) / 1000) } ?: 0
                } else 0
                Text(
                    "${formatDate(track.startedAt)} · ${formatDuration(duration)} · ${formatDistance(track.distanceMeters)}",
                    color = HomeMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (track.note.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(track.note, color = HomeMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "删除${track.title}",
                        tint = HomeMuted,
                    )
                }
            }
        }
    }
}

private fun hasLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun startLocationService(context: android.content.Context) {
    ContextCompat.startForegroundService(context, serviceIntent(context, LocationForegroundService.ACTION_START))
}

private fun serviceIntent(context: android.content.Context, action: String) =
    Intent(context, LocationForegroundService::class.java).setAction(action)

private fun sendAction(context: android.content.Context, action: String, trackId: String) {
    val intent = serviceIntent(context, action).putExtra(LocationForegroundService.EXTRA_TRACK_ID, trackId)
    ContextCompat.startForegroundService(context, intent)
}

private fun statusLabel(status: String) = when (status) {
    TrackStatus.RECORDING -> "正在记录"
    TrackStatus.PAUSED -> "已暂停"
    TrackStatus.INTERRUPTED -> "待继续"
    else -> "已完成"
}

private fun formatDistance(meters: Double): String = if (meters < 1_000) {
    "${meters.toInt()} 米"
} else {
    String.format(Locale.getDefault(), "%.2f 公里", meters / 1_000)
}

private fun formatSpeed(speedMetersPerSecond: Float?): String {
    val speed = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f } ?: return "-- km/h"
    return String.format(Locale.getDefault(), "%.1f km/h", speed * 3.6f)
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainder = seconds % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainder)
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, remainder)
}

private fun formatDate(timestamp: Long) =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
