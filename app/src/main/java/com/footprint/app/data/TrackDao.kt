package com.footprint.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY startedAt DESC")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY startedAt DESC")
    suspend fun getAllTracks(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getTrack(trackId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE status IN ('RECORDING', 'PAUSED', 'INTERRUPTED') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpenTrack(): TrackEntity?

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY sequence")
    fun observePoints(trackId: String): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY sequence")
    suspend fun getPoints(trackId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND sequence > :afterSequence ORDER BY sequence LIMIT :limit")
    suspend fun getPointsPage(trackId: String, afterSequence: Long, limit: Int): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY sequence DESC LIMIT 1")
    suspend fun getLastPoint(trackId: String): TrackPointEntity?

    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND sequence = :sequence LIMIT 1")
    suspend fun getPoint(trackId: String, sequence: Long): TrackPointEntity?

    @Transaction
    @Query("SELECT * FROM track_moments WHERE trackId = :trackId ORDER BY createdAt DESC")
    fun observeMoments(trackId: String): Flow<List<TrackMomentWithAttachments>>

    @Transaction
    @Query("SELECT * FROM track_moments WHERE trackId = :trackId ORDER BY createdAt DESC")
    suspend fun getMomentsWithAttachments(trackId: String): List<TrackMomentWithAttachments>

    @Query("SELECT * FROM attachments WHERE trackId = :trackId")
    suspend fun getAttachments(trackId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE momentId = :momentId")
    suspend fun getAttachmentsForMoment(momentId: String): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMoment(moment: TrackMomentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMoments(moments: List<TrackMomentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM track_moments WHERE id = :momentId")
    suspend fun deleteMoment(momentId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoint(point: TrackPointEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("UPDATE tracks SET status = :status, durationSeconds = :durationSeconds, activeSince = :activeSince, endedAt = :endedAt, updatedAt = :updatedAt WHERE id = :trackId")
    suspend fun updateState(
        trackId: String,
        status: String,
        durationSeconds: Long,
        activeSince: Long?,
        endedAt: Long?,
        updatedAt: Long,
    )

    @Query("UPDATE tracks SET distanceMeters = :distanceMeters, startLatitude = COALESCE(startLatitude, :latitude), startLongitude = COALESCE(startLongitude, :longitude), endLatitude = :latitude, endLongitude = :longitude, updatedAt = :updatedAt WHERE id = :trackId")
    suspend fun updatePointSummary(
        trackId: String,
        distanceMeters: Double,
        latitude: Double,
        longitude: Double,
        updatedAt: Long,
    )

    @Query("UPDATE tracks SET title = :title, note = :note, updatedAt = :updatedAt WHERE id = :trackId")
    suspend fun updateDescription(trackId: String, title: String, note: String, updatedAt: Long)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: String)
}
