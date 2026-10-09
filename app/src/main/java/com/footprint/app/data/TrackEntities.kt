package com.footprint.app.data

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object TrackStatus {
    const val RECORDING = "RECORDING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val INTERRUPTED = "INTERRUPTED"
}

@Entity(tableName = "tracks", indices = [Index("startedAt")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val note: String = "",
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** Sum of completed recording segments; the current segment is represented by activeSince. */
    val durationSeconds: Long = 0,
    val activeSince: Long? = null,
    val distanceMeters: Double = 0.0,
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,
    val coordinateSystem: String = "GCJ-02",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "track_points",
    primaryKeys = ["trackId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId", "recordedAt")],
)
data class TrackPointEntity(
    val trackId: String,
    val sequence: Long,
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Long,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float?,
    val altitudeMeters: Double?,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackMomentEntity::class,
            parentColumns = ["id"],
            childColumns = ["momentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId"), Index("momentId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val relativePath: String,
    val capturedAt: Long?,
    val createdAt: Long,
    val contentType: String,
    val fileSize: Long,
    val momentId: String? = null,
)

@Entity(
    tableName = "track_moments",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackPointEntity::class,
            parentColumns = ["trackId", "sequence"],
            childColumns = ["trackId", "pointSequence"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId"), Index(value = ["trackId", "pointSequence"])],
)
data class TrackMomentEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val text: String,
    val createdAt: Long,
    val pointSequence: Long? = null,
)

data class TrackMomentWithAttachments(
    @Embedded val moment: TrackMomentEntity,
    @Relation(parentColumn = "id", entityColumn = "momentId")
    val attachments: List<AttachmentEntity>,
)
