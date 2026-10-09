package com.footprint.app.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.footprint.app.FootprintApplication
import com.footprint.app.data.TrackFinishResult
import com.footprint.app.data.TrackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LocationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { (application as FootprintApplication).tracks }
    private var locationClient: AMapLocationClient? = null
    @Volatile
    private var activeTrackId: String? = null
    @Volatile
    private var hasReportedLocationIssue = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_START -> serviceScope.launch { startNewTrack() }
            ACTION_RESUME -> serviceScope.launch { resumeTrack(intent.getStringExtra(EXTRA_TRACK_ID)) }
            ACTION_PAUSE -> serviceScope.launch { pauseTrack(intent.getStringExtra(EXTRA_TRACK_ID)) }
            ACTION_FINISH -> serviceScope.launch { finishTrack(intent.getStringExtra(EXTRA_TRACK_ID)) }
            ACTION_DISCARD -> serviceScope.launch { discardTrack(intent.getStringExtra(EXTRA_TRACK_ID)) }
            else -> serviceScope.launch {
                val openTrack = repository.getOpenTrack()
                if (openTrack?.status == TrackStatus.RECORDING) {
                    activeTrackId = openTrack.id
                    startLocationUpdates()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startNewTrack() {
        val existing = repository.getOpenTrack()
        if (existing != null) {
            activeTrackId = existing.id
            if (existing.status == TrackStatus.PAUSED || existing.status == TrackStatus.INTERRUPTED) {
                repository.resumeTrack(existing.id)
            }
        } else {
            activeTrackId = repository.createTrack().id
        }
        startLocationUpdates()
    }

    private suspend fun resumeTrack(trackId: String?) {
        val track = trackId?.let { repository.getTrack(it) } ?: repository.getOpenTrack()
        if (track == null) {
            startNewTrack()
            return
        }
        activeTrackId = track.id
        repository.resumeTrack(track.id)
        startLocationUpdates()
    }

    private suspend fun pauseTrack(trackId: String?) {
        stopLocationUpdates()
        (activeTrackId ?: trackId)?.let { repository.pauseTrack(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun finishTrack(trackId: String?) {
        stopLocationUpdates()
        val result = (activeTrackId ?: trackId)?.let { repository.finishTrack(it) }
        if (result == TrackFinishResult.EMPTY) {
            (application as FootprintApplication).setTrackFinishNotice(
                "没有记录到有效定位点，本次轨迹未保存。请确认定位正常后再开始记录。",
            )
        }
        activeTrackId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun discardTrack(trackId: String?) {
        stopLocationUpdates()
        (activeTrackId ?: trackId)?.let { repository.deleteTrack(it) }
        activeTrackId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startLocationUpdates() {
        if (locationClient != null) return
        hasReportedLocationIssue = false
        (application as FootprintApplication).setLocationWarning(null)
        try {
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)
            locationClient = AMapLocationClient(applicationContext).apply {
                setLocationListener { location -> onLocation(location) }
                val option = AMapLocationClientOption()
                option.setLocationPurpose(AMapLocationClientOption.AMapLocationPurpose.Sport)
                option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
                option.setInterval(LOCATION_INTERVAL_MILLIS)
                option.setLocationCacheEnable(false)
                option.setOnceLocation(false)
                option.setNeedAddress(false)
                setLocationOption(option)
                startLocation()
                isRecordingActive = true
            }
        } catch (error: Exception) {
            reportLocationIssue("无法启动定位服务：${error.message ?: "请检查定位权限和系统定位开关"}")
            serviceScope.launch {
                activeTrackId?.let { repository.markInterrupted(it) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun onLocation(location: AMapLocation?) {
        if (location == null) {
            reportLocationIssue("暂未收到定位结果，请检查系统定位开关后重试。")
            return
        }
        if (location.errorCode != 0) {
            reportLocationIssue("定位失败（错误码 ${location.errorCode}），请检查系统定位开关和网络后重试。")
            return
        }
        if (!location.hasAccuracy()) {
            reportLocationIssue("定位结果缺少精度信息，暂未保存轨迹点。请稍后重试。")
            return
        }
        if (!location.accuracy.isFinite() || location.accuracy < 0f) {
            reportLocationIssue("定位精度无效，暂未保存轨迹点。请稍后重试。")
            return
        }
        if (location.accuracy > MAX_ACCEPTABLE_ACCURACY_METERS) {
            reportLocationIssue(
                "当前定位误差约 ${location.accuracy.toInt()} 米，超过 ${MAX_ACCEPTABLE_ACCURACY_METERS.toInt()} 米记录门槛，暂未保存轨迹点。请确认已开启精确位置，并移到窗边或室外等待定位变准。",
            )
            return
        }
        val trackId = activeTrackId ?: return
        hasReportedLocationIssue = false
        val receivedAt = System.currentTimeMillis()
        serviceScope.launch {
            repository.saveLocation(
                trackId = trackId,
                latitude = location.latitude,
                longitude = location.longitude,
                recordedAt = receivedAt,
                accuracyMeters = location.accuracy,
                speedMetersPerSecond = location.speed.takeIf { it >= 0f },
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
            )
        }
    }

    private fun reportLocationIssue(message: String) {
        if (hasReportedLocationIssue) return
        hasReportedLocationIssue = true
        (application as FootprintApplication).setLocationWarning(message)
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("FootPrint 正在记录")
            .setContentText("轨迹已保存在手机本地")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "轨迹记录", NotificationManager.IMPORTANCE_LOW).apply {
                description = "步行轨迹记录期间显示"
            },
        )
    }

    private fun stopLocationUpdates() {
        locationClient?.stopLocation()
        locationClient?.onDestroy()
        locationClient = null
        isRecordingActive = false
    }

    override fun onDestroy() {
        stopLocationUpdates()
        isRecordingActive = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var isRecordingActive: Boolean = false
            private set

        const val ACTION_START = "com.footprint.app.action.START"
        const val ACTION_RESUME = "com.footprint.app.action.RESUME"
        const val ACTION_PAUSE = "com.footprint.app.action.PAUSE"
        const val ACTION_FINISH = "com.footprint.app.action.FINISH"
        const val ACTION_DISCARD = "com.footprint.app.action.DISCARD"
        const val EXTRA_TRACK_ID = "track_id"
        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MILLIS = 4_000L
        private const val MAX_ACCEPTABLE_ACCURACY_METERS = 50f
    }
}
