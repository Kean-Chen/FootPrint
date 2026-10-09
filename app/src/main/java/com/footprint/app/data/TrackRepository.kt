package com.footprint.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TrackRepository(
    private val database: FootprintDatabase,
    private val context: Context,
) {
    private val dao = database.trackDao()

    fun observeTracks(): Flow<List<TrackEntity>> = dao.observeTracks()
    fun observePoints(trackId: String): Flow<List<TrackPointEntity>> = dao.observePoints(trackId)
    fun observeMoments(trackId: String): Flow<List<TrackMomentWithAttachments>> = dao.observeMoments(trackId)
    suspend fun getTrack(trackId: String): TrackEntity? = dao.getTrack(trackId)
    suspend fun getOpenTrack(): TrackEntity? = dao.getOpenTrack()

    /** Copies selected images into private app storage and then commits their metadata atomically. */
    suspend fun addMoment(
        trackId: String,
        pointSequence: Long,
        text: String,
        imageUris: List<Uri>,
    ): TrackMomentEntity =
        withContext(Dispatchers.IO) {
            require(imageUris.size <= MAX_IMAGES_PER_MOMENT) { "一次最多添加 $MAX_IMAGES_PER_MOMENT 张图片" }
            require(text.isNotBlank() || imageUris.isNotEmpty()) { "请添加文字或至少一张图片" }
            require(text.length <= MAX_MOMENT_TEXT_LENGTH) { "文字不能超过 10,000 个字符" }

            val momentId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val moment = TrackMomentEntity(momentId, trackId, text.trim(), now, pointSequence)
            val copiedFiles = mutableListOf<File>()
            try {
                val attachments = imageUris.mapIndexed { index, uri ->
                    val contentType = context.contentResolver.getType(uri)
                        ?.substringBefore(';')
                        ?.lowercase(Locale.ROOT)
                        ?: "image/jpeg"
                    require(contentType.startsWith("image/")) { "仅支持图片文件" }
                    val attachmentId = UUID.randomUUID().toString()
                    val extension = extensionForImage(contentType)
                    val relativePath = "track_media/$attachmentId.$extension"
                    val destination = resolveAttachmentPath(relativePath)
                        ?: error("附件保存路径无效")
                    destination.parentFile?.mkdirs()
                    copiedFiles += destination
                    val fileSize = context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destination).buffered().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_IMAGE_BYTES) { "单张图片不能超过 20 MB" }
                                output.write(buffer, 0, count)
                            }
                            total
                        }
                    } ?: error("无法读取所选图片")
                    require(fileSize > 0L) { "图片文件为空" }
                    AttachmentEntity(
                        id = attachmentId,
                        trackId = trackId,
                        relativePath = relativePath,
                        capturedAt = null,
                        createdAt = now + index,
                        contentType = contentType,
                        fileSize = fileSize,
                        momentId = momentId,
                    )
                }

                database.withTransaction {
                    check(dao.getTrack(trackId) != null) { "这条轨迹已不存在" }
                    check(dao.getPoint(trackId, pointSequence) != null) { "关联的轨迹点已不存在，请重新添加图文记录" }
                    dao.insertMoment(moment)
                    if (attachments.isNotEmpty()) dao.insertAttachments(attachments)
                }
                moment
            } catch (error: Throwable) {
                copiedFiles.forEach { it.delete() }
                throw error
            }
        }

    fun attachmentFile(relativePath: String): File? = resolveAttachmentPath(relativePath)

    suspend fun deleteMoment(momentId: String) = withContext(Dispatchers.IO) {
        val files = dao.getAttachmentsForMoment(momentId).mapNotNull { resolveAttachmentPath(it.relativePath) }
        dao.deleteMoment(momentId)
        files.forEach { it.delete() }
    }

    suspend fun createTrack(now: Long = System.currentTimeMillis()): TrackEntity {
        val track = TrackEntity(
            id = UUID.randomUUID().toString(),
            title = "散步 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(now))}",
            status = TrackStatus.RECORDING,
            startedAt = now,
            activeSince = now,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertTrack(track)
        return track
    }

    suspend fun resumeTrack(trackId: String, now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val track = dao.getTrack(trackId) ?: return@withTransaction
            if (track.status == TrackStatus.RECORDING) return@withTransaction
            if (track.status == TrackStatus.COMPLETED) return@withTransaction
            dao.updateState(trackId, TrackStatus.RECORDING, track.durationSeconds, now, null, now)
        }
    }

    suspend fun pauseTrack(trackId: String, now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val track = dao.getTrack(trackId) ?: return@withTransaction
            if (track.status != TrackStatus.RECORDING) return@withTransaction
            val elapsed = track.activeSince?.let { ((now - it).coerceAtLeast(0) / 1000) } ?: 0
            dao.updateState(
                trackId,
                TrackStatus.PAUSED,
                track.durationSeconds + elapsed,
                null,
                null,
                now,
            )
        }
    }

    suspend fun finishTrack(trackId: String, now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val track = dao.getTrack(trackId) ?: return@withTransaction
            if (track.status == TrackStatus.COMPLETED) return@withTransaction
            val elapsed = if (track.status == TrackStatus.RECORDING) {
                track.activeSince?.let { ((now - it).coerceAtLeast(0) / 1000) } ?: 0
            } else 0
            dao.updateState(
                trackId,
                TrackStatus.COMPLETED,
                track.durationSeconds + elapsed,
                null,
                now,
                now,
            )
        }
    }

    suspend fun markInterrupted(trackId: String, now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val track = dao.getTrack(trackId) ?: return@withTransaction
            if (track.status != TrackStatus.RECORDING) return@withTransaction
            val elapsed = track.activeSince?.let { ((now - it).coerceAtLeast(0) / 1000) } ?: 0
            dao.updateState(
                trackId,
                TrackStatus.INTERRUPTED,
                track.durationSeconds + elapsed,
                null,
                null,
                now,
            )
        }
    }

    suspend fun markInterruptedAtLastPoint(trackId: String) {
        database.withTransaction {
            val track = dao.getTrack(trackId) ?: return@withTransaction
            if (track.status != TrackStatus.RECORDING) return@withTransaction
            val lastActiveAt = dao.getLastPoint(trackId)?.recordedAt ?: track.updatedAt
            val elapsed = track.activeSince?.let {
                ((lastActiveAt - it).coerceAtLeast(0) / 1_000)
            } ?: 0
            dao.updateState(
                trackId,
                TrackStatus.INTERRUPTED,
                track.durationSeconds + elapsed,
                null,
                null,
                lastActiveAt,
            )
        }
    }

    suspend fun saveLocation(
        trackId: String,
        latitude: Double,
        longitude: Double,
        recordedAt: Long,
        accuracyMeters: Float,
        speedMetersPerSecond: Float?,
        altitudeMeters: Double?,
    ) {
        database.withTransaction {
            val track = dao.getTrack(trackId) ?: return@withTransaction
            if (track.status != TrackStatus.RECORDING) return@withTransaction
            val previous = dao.getLastPoint(trackId)
            if (previous != null && recordedAt <= previous.recordedAt) return@withTransaction

            val sequence = (previous?.sequence ?: -1L) + 1L
            dao.insertPoint(
                TrackPointEntity(
                    trackId = trackId,
                    sequence = sequence,
                    latitude = latitude,
                    longitude = longitude,
                    recordedAt = recordedAt,
                    accuracyMeters = accuracyMeters,
                    speedMetersPerSecond = speedMetersPerSecond,
                    altitudeMeters = altitudeMeters,
                ),
            )
            val addedDistance = previous?.let {
                haversineMeters(it.latitude, it.longitude, latitude, longitude)
            } ?: 0.0
            dao.updatePointSummary(
                trackId = trackId,
                distanceMeters = track.distanceMeters + addedDistance,
                latitude = latitude,
                longitude = longitude,
                updatedAt = recordedAt,
            )
        }
    }

    suspend fun updateDescription(trackId: String, title: String, note: String) {
        dao.updateDescription(trackId, title.trim(), note.trim(), System.currentTimeMillis())
    }

    suspend fun deleteTrack(trackId: String) = withContext(Dispatchers.IO) {
        val files = dao.getAttachments(trackId).mapNotNull { resolveAttachmentPath(it.relativePath) }
        dao.deleteTrack(trackId)
        files.forEach { it.delete() }
    }

    private fun resolveAttachmentPath(relativePath: String): File? {
        if (!relativePath.startsWith("track_media/")) return null
        val root = File(context.filesDir, "track_media").canonicalFile
        val file = File(context.filesDir, relativePath).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun extensionForImage(contentType: String): String = when (contentType) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        else -> "img"
    }

    private companion object {
        const val MAX_IMAGES_PER_MOMENT = 8
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        const val MAX_MOMENT_TEXT_LENGTH = 10_000
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val latDelta = Math.toRadians(lat2 - lat1)
        val lonDelta = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(latDelta / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(lonDelta / 2).let { it * it }
        return earthRadiusMeters * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }
}
