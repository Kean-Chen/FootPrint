package com.footprint.app.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.footprint.app.BuildConfig
import com.footprint.app.data.FootprintDatabase
import com.footprint.app.data.AttachmentEntity
import com.footprint.app.data.TrackEntity
import com.footprint.app.data.TrackMomentEntity
import com.footprint.app.data.TrackPointEntity
import com.footprint.app.data.TrackStatus
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class CreatedTrackBackup(
    val file: File,
    val trackCount: Int,
    val pointCount: Long,
    val momentCount: Int,
    val attachmentCount: Int,
)

internal data class StagedTrackBackup(
    internal val directory: File,
    val trackCount: Int,
    val pointCount: Long,
    val momentCount: Int,
    val attachmentCount: Int,
    val createdAt: Long,
    val encrypted: Boolean,
)

internal data class EncryptedTrackBackup(internal val file: File)

internal sealed interface PreparedTrackBackup {
    data class Ready(val backup: StagedTrackBackup) : PreparedTrackBackup
    data class PasswordRequired(val backup: EncryptedTrackBackup) : PreparedTrackBackup
}

internal data class TrackRestoreSummary(
    val importedTrackCount: Int,
    val skippedTrackCount: Int,
    val importedPointCount: Long,
    val importedMomentCount: Int,
    val importedAttachmentCount: Int,
)

internal class TrackBackupManager(
    private val context: Context,
    private val database: FootprintDatabase,
) {
    private val dao = database.trackDao()

    private data class BackupAttachment(
        val attachment: AttachmentEntity,
        val mediaEntry: String,
    )

    suspend fun createBackup(trackIds: Set<String>?, password: CharArray): CreatedTrackBackup = withContext(Dispatchers.IO) {
        require(password.size >= MIN_PASSWORD_LENGTH) { "备份密码至少需要 $MIN_PASSWORD_LENGTH 个字符" }
        require(trackIds == null || trackIds.isNotEmpty()) { "请至少选择一条轨迹" }
        val directory = File(context.cacheDir, "footprint-backup-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "无法创建临时备份目录" }
        val tracksFile = File(directory, TRACKS_FILE)
        val pointsFile = File(directory, POINTS_FILE)
        val momentsFile = File(directory, MOMENTS_FILE)
        val attachmentsFile = File(directory, ATTACHMENTS_FILE)
        val zipFile = File(directory, ZIP_FILE)
        val archiveFile = File(directory, ARCHIVE_FILE)
        val exportedAt = System.currentTimeMillis()
        val mediaFiles = mutableListOf<Pair<String, File>>()

        try {
            var pointCount = 0L
            var momentCount = 0
            var attachmentCount = 0
            val tracks = database.withTransaction {
                val allRows = dao.getAllTracks()
                val allIds = allRows.mapTo(HashSet<String>()) { it.id }
                require(trackIds == null || trackIds.all { it in allIds }) { "所选轨迹已不存在，请刷新列表后重试" }
                val rows = if (trackIds == null) allRows else allRows.filter { it.id in trackIds }
                tracksFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write("[")
                    rows.forEachIndexed { index, track ->
                        if (index > 0) writer.write(",")
                        writer.write(trackForBackup(track, exportedAt).toJson().toString())
                    }
                    writer.write("]")
                }

                pointsFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    rows.forEach { track ->
                        var afterSequence = -1L
                        while (true) {
                            val page = dao.getPointsPage(track.id, afterSequence, POINT_PAGE_SIZE)
                            if (page.isEmpty()) break
                            page.forEach { point ->
                                writer.write(point.toJson().toString())
                                writer.newLine()
                                afterSequence = point.sequence
                                pointCount += 1
                            }
                        }
                    }
                }

                val momentRows = rows.flatMap { dao.getMomentsWithAttachments(it.id) }
                momentsFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(JSONArray(momentRows.map { it.moment.toJson() }).toString())
                }
                val attachmentRows = momentRows.flatMap { it.attachments }.sortedBy { it.createdAt }
                val entryByAttachmentId = attachmentRows.associate { attachment ->
                    val source = resolveStoredAttachment(attachment.relativePath)
                        ?: error("图文记录 ${attachment.id} 的图片路径无效")
                    require(source.isFile && source.length() == attachment.fileSize) {
                        "图文记录 ${attachment.id} 的图片文件缺失或已损坏"
                    }
                    val extension = source.extension.lowercase(Locale.ROOT)
                    require(extension.matches(Regex("[a-z0-9]{1,8}"))) { "图片扩展名无效" }
                    val entry = "media/${attachment.id}.$extension"
                    mediaFiles += entry to source
                    attachment.id to entry
                }
                attachmentsFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write("[")
                    attachmentRows.forEachIndexed { index, attachment ->
                        if (index > 0) writer.write(",")
                        writer.write(attachment.toArchiveJson(entryByAttachmentId.getValue(attachment.id)).toString())
                    }
                    writer.write("]")
                }
                momentCount = momentRows.size
                attachmentCount = attachmentRows.size
                rows.size
            }

            val checksums = linkedMapOf(
                TRACKS_FILE to sha256(tracksFile),
                POINTS_FILE to sha256(pointsFile),
                MOMENTS_FILE to sha256(momentsFile),
                ATTACHMENTS_FILE to sha256(attachmentsFile),
            )
            mediaFiles.forEach { (entry, file) -> checksums[entry] = sha256(file) }

            val manifest = JSONObject()
                .put("format", ARCHIVE_FORMAT)
                .put("schemaVersion", ARCHIVE_VERSION)
                .put("createdAt", exportedAt)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("trackCount", tracks)
                .put("pointCount", pointCount)
                .put("momentCount", momentCount)
                .put("attachmentCount", attachmentCount)
                .put("sha256", JSONObject().apply { checksums.forEach { (name, checksum) -> put(name, checksum) } })

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                zip.writeEntry(MANIFEST_FILE, manifest.toString().toByteArray(Charsets.UTF_8))
                zip.writeFileEntry(TRACKS_FILE, tracksFile)
                zip.writeFileEntry(POINTS_FILE, pointsFile)
                zip.writeFileEntry(MOMENTS_FILE, momentsFile)
                zip.writeFileEntry(ATTACHMENTS_FILE, attachmentsFile)
                mediaFiles.forEach { (entry, file) -> zip.writeFileEntry(entry, file) }
            }
            tracksFile.delete()
            pointsFile.delete()
            momentsFile.delete()
            attachmentsFile.delete()
            encryptArchive(zipFile, archiveFile, password)
            zipFile.delete()
            CreatedTrackBackup(archiveFile, tracks, pointCount, momentCount, attachmentCount)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun prepareBackup(input: InputStream): PreparedTrackBackup {
        val directory = File(context.cacheDir, "footprint-restore-input-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "无法创建临时恢复目录" }
        val inputFile = File(directory, "selected-backup")
        try {
            input.copyBoundedTo(inputFile, MAX_ENCRYPTED_BYTES)
            val prefix = ByteArray(MAGIC.size)
            FileInputStream(inputFile).use { source ->
                require(source.read(prefix) == prefix.size) { "备份文件为空或格式无效" }
            }
            if (prefix.contentEquals(MAGIC)) {
                return PreparedTrackBackup.PasswordRequired(EncryptedTrackBackup(inputFile))
            }
            val staged = FileInputStream(inputFile).use { stageZip(it, encrypted = false) }
            directory.deleteRecursively()
            return PreparedTrackBackup.Ready(staged)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun stageEncryptedBackup(backup: EncryptedTrackBackup, password: CharArray): StagedTrackBackup {
        require(password.isNotEmpty()) { "请输入备份密码" }
        val zipFile = File(backup.file.parentFile, "decrypted-${UUID.randomUUID()}.zip")
        try {
            decryptArchive(backup.file, zipFile, password)
            return FileInputStream(zipFile).use { stageZip(it, encrypted = true) }
        } finally {
            zipFile.delete()
        }
    }

    private fun stageZip(input: InputStream, encrypted: Boolean): StagedTrackBackup {
        val directory = File(context.cacheDir, "footprint-restore-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "无法创建临时恢复目录" }
        val files = mutableMapOf<String, File>()
        try {
            var totalBytes = 0L
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && (entry.name in ARCHIVE_FILES || isMediaEntry(entry.name))) {
                        "备份包含不支持的文件"
                    }
                    require(entry.name !in files) { "备份中存在重复文件" }
                    val target = File(directory, entry.name)
                    target.parentFile?.mkdirs()
                    val entryLimit = when (entry.name) {
                        MANIFEST_FILE -> MAX_MANIFEST_BYTES
                        TRACKS_FILE -> MAX_TRACKS_BYTES
                        POINTS_FILE -> MAX_POINTS_BYTES
                        MOMENTS_FILE, ATTACHMENTS_FILE -> MAX_METADATA_BYTES
                        else -> MAX_IMAGE_BYTES
                    }
                    val copied = zip.copyEntryTo(target, entryLimit)
                    totalBytes += copied
                    require(totalBytes <= MAX_TOTAL_BYTES) { "备份文件过大" }
                    files[entry.name] = target
                    zip.closeEntry()
                }
            }

            val manifestFile = files[MANIFEST_FILE] ?: error("备份缺少 manifest.json")
            val tracksFile = files[TRACKS_FILE] ?: error("备份缺少 tracks.json")
            val pointsFile = files[POINTS_FILE] ?: error("备份缺少 points.jsonl")
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            require(manifest.getString("format") == ARCHIVE_FORMAT) { "这不是 FootPrint 备份文件" }
            val schemaVersion = manifest.getInt("schemaVersion")
            require(schemaVersion in LEGACY_ARCHIVE_VERSION..ARCHIVE_VERSION) { "暂不支持此备份格式版本" }

            val checksums = manifest.getJSONObject("sha256")
            val checksumArray = checksums.names() ?: JSONArray()
            val checksumNames = (0 until checksumArray.length()).mapTo(HashSet()) { checksumArray.getString(it) }
            require(checksumNames == files.keys - MANIFEST_FILE) { "备份清单与文件内容不一致" }
            checksumNames.forEach { name ->
                val stagedFile = files[name] ?: error("备份清单引用了缺失文件")
                require(checksums.getString(name).equals(sha256(stagedFile), ignoreCase = true)) {
                    "$name 校验失败，备份可能已损坏"
                }
            }
            require(TRACKS_FILE in files && POINTS_FILE in files) { "备份缺少轨迹数据" }
            if (schemaVersion >= 2) {
                require(MOMENTS_FILE in files && ATTACHMENTS_FILE in files) { "备份缺少图文记录清单" }
            } else {
                require(files.keys.all { it in LEGACY_ARCHIVE_FILES }) { "旧版备份包含不支持的文件" }
            }

            val tracks = JSONArray(tracksFile.readText(Charsets.UTF_8))
            require(tracks.length() == manifest.getInt("trackCount")) { "轨迹数量与清单不一致" }
            val trackIds = LinkedHashMap<String, Int>(tracks.length())
            for (index in 0 until tracks.length()) {
                val track = parseTrack(tracks.getJSONObject(index))
                require(track.id !in trackIds) { "备份中存在重复轨迹 ID" }
                trackIds[track.id] = index
            }

            val pointCount = validatePoints(pointsFile, trackIds)
            require(pointCount == manifest.getLong("pointCount")) { "轨迹点数量与清单不一致" }
            val counts = validateMomentsAndAttachments(
                files = files,
                trackIds = trackIds.keys,
                manifest = manifest,
                schemaVersion = schemaVersion,
                pointsFile = pointsFile,
            )
            return StagedTrackBackup(
                directory = directory,
                trackCount = tracks.length(),
                pointCount = pointCount,
                momentCount = counts.first,
                attachmentCount = counts.second,
                createdAt = manifest.getLong("createdAt"),
                encrypted = encrypted,
            )
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    suspend fun restoreBackup(backup: StagedTrackBackup): TrackRestoreSummary = withContext(Dispatchers.IO) {
        val tracksFile = File(backup.directory, TRACKS_FILE)
        val pointsFile = File(backup.directory, POINTS_FILE)
        val tracksJson = JSONArray(tracksFile.readText(Charsets.UTF_8))
        val tracks = (0 until tracksJson.length()).map { index ->
            restoreSafeTrack(parseTrack(tracksJson.getJSONObject(index)))
        }

        val existingIds = dao.getAllTracks().mapTo(HashSet<String>()) { it.id }
        val tracksToImport = tracks.filterNot { it.id in existingIds }
        val importedIds = tracksToImport.mapTo(HashSet<String>()) { it.id }
        val momentRows = readMomentRows(backup.directory, importedIds)
        val attachmentRows = readAttachmentRows(backup.directory, importedIds)
        val copiedMediaFiles = mutableListOf<File>()
        try {
            val attachmentEntities = attachmentRows.map { row ->
                val staged = File(backup.directory, row.mediaEntry)
                val relativePath = "track_media/${row.mediaEntry.substringAfterLast('/')}"
                val destination = File(context.filesDir, relativePath)
                destination.parentFile?.mkdirs()
                require(!destination.exists()) { "图片文件 ID 已存在，请检查备份" }
                copiedMediaFiles += destination
                staged.copyTo(destination, overwrite = false)
                row.attachment.copy(relativePath = relativePath)
            }

            val summary = database.withTransaction {
                // Recheck inside the transaction in case another restore started concurrently.
                val currentIds = dao.getAllTracks().mapTo(HashSet<String>()) { it.id }
                require(tracksToImport.none { it.id in currentIds }) { "轨迹正在被其他恢复任务导入，请重试" }
                if (tracksToImport.isNotEmpty()) dao.insertTracks(tracksToImport)

                var importedPointCount = 0L
                val batch = ArrayList<TrackPointEntity>(INSERT_BATCH_SIZE)
                pointsFile.bufferedReader(Charsets.UTF_8).use { reader: BufferedReader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val point = parsePoint(JSONObject(line))
                        if (point.trackId in importedIds) {
                            batch += point
                            if (batch.size >= INSERT_BATCH_SIZE) {
                                dao.insertPoints(batch)
                                importedPointCount += batch.size
                                batch.clear()
                            }
                        }
                    }
                }
                if (batch.isNotEmpty()) {
                    dao.insertPoints(batch)
                    importedPointCount += batch.size
                }
                // Moments have composite foreign keys to track points, so insert them after the points.
                if (momentRows.isNotEmpty()) dao.insertMoments(momentRows)
                if (attachmentEntities.isNotEmpty()) dao.insertAttachments(attachmentEntities)

                TrackRestoreSummary(
                    importedTrackCount = tracksToImport.size,
                    skippedTrackCount = tracks.size - tracksToImport.size,
                    importedPointCount = importedPointCount,
                    importedMomentCount = momentRows.size,
                    importedAttachmentCount = attachmentEntities.size,
                )
            }
            summary
        } catch (error: Throwable) {
            copiedMediaFiles.forEach { it.delete() }
            throw error
        }
    }

    fun cleanup(backup: CreatedTrackBackup) {
        backup.file.parentFile?.deleteRecursively()
    }

    fun cleanup(backup: StagedTrackBackup) {
        backup.directory.deleteRecursively()
    }

    fun cleanup(backup: EncryptedTrackBackup) {
        backup.file.parentFile?.deleteRecursively()
    }

    // The v2 envelope authenticates each bounded chunk independently and ends with an
    // authenticated sentinel, so large backups can be decrypted without buffering the file.
    private fun encryptArchive(source: File, target: File, password: CharArray) {
        val salt = ByteArray(SALT_SIZE).also(secureRandom::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_SIZE).also(secureRandom::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC)
            .put(ENVELOPE_VERSION.toByte())
            .putInt(PBKDF2_ITERATIONS)
            .put(salt)
            .put(noncePrefix)
            .array()
        val key = deriveKey(password, salt)
        FileInputStream(source).use { input ->
            DataOutputStream(BufferedOutputStream(FileOutputStream(target))).use { output ->
                output.write(header)
                val buffer = ByteArray(IO_BUFFER_SIZE)
                var chunkIndex = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.writeInt(count)
                    val cipher = newRecordCipher(Cipher.ENCRYPT_MODE, key, noncePrefix, header, chunkIndex, count)
                    output.write(cipher.doFinal(buffer, 0, count))
                    chunkIndex += 1
                }
                output.writeInt(0)
                val finalCipher = newRecordCipher(Cipher.ENCRYPT_MODE, key, noncePrefix, header, chunkIndex, 0)
                output.write(finalCipher.doFinal(ByteArray(0)))
            }
        }
    }

    private fun decryptArchive(source: File, target: File, password: CharArray) {
        require(source.length() >= HEADER_SIZE + GCM_TAG_BYTES) { "加密备份文件不完整" }
        try {
            DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
                val header = ByteArray(HEADER_SIZE)
                readFully(input, header)
                val buffer = ByteBuffer.wrap(header)
                val magic = ByteArray(MAGIC.size)
                buffer.get(magic)
                require(magic.contentEquals(MAGIC)) { "备份文件头无效" }
                val version = buffer.get().toInt() and 0xff
                require(version == ENVELOPE_VERSION) { "暂不支持此加密备份版本" }
                val iterations = buffer.int
                require(iterations in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) { "备份加密参数无效" }
                val salt = ByteArray(SALT_SIZE)
                buffer.get(salt)
                val noncePrefix = ByteArray(NONCE_PREFIX_SIZE)
                buffer.get(noncePrefix)
                val key = deriveKey(password, salt, iterations)
                FileOutputStream(target).use { output ->
                    var plaintextBytes = 0L
                    var chunkIndex = 0
                    while (true) {
                        val length = try {
                            input.readInt()
                        } catch (_: java.io.EOFException) {
                            error("加密备份缺少结束标记")
                        }
                        require(length in 0..IO_BUFFER_SIZE) { "加密备份分块长度无效" }
                        val encryptedChunk = ByteArray(length + GCM_TAG_BYTES)
                        readFully(input, encryptedChunk)
                        val cipher = newRecordCipher(
                            Cipher.DECRYPT_MODE,
                            key,
                            noncePrefix,
                            header,
                            chunkIndex,
                            length,
                        )
                        val plain = cipher.doFinal(encryptedChunk)
                        if (length == 0) {
                            require(plain.isEmpty()) { "加密备份结束标记无效" }
                            require(input.read() == -1) { "加密备份包含多余数据" }
                            break
                        }
                        require(plain.size == length) { "加密备份分块长度不匹配" }
                        plaintextBytes += plain.size
                        require(plaintextBytes <= MAX_TOTAL_BYTES + ZIP_OVERHEAD_BYTES) { "解密后的备份过大" }
                        output.write(plain)
                        chunkIndex += 1
                    }
                }
            }
        } catch (error: BadPaddingException) {
            target.delete()
            throw IllegalArgumentException("密码错误或备份文件已损坏", error)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
    ): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            SecretKeySpec(keyBytes, "AES")
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun newRecordCipher(
        mode: Int,
        key: SecretKeySpec,
        noncePrefix: ByteArray,
        header: ByteArray,
        chunkIndex: Int,
        plaintextLength: Int,
    ): Cipher {
        val nonce = ByteBuffer.allocate(NONCE_SIZE)
            .put(noncePrefix)
            .putInt(chunkIndex)
            .array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(RECORD_AAD_SIZE).putInt(chunkIndex).putInt(plaintextLength).array())
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) error("加密备份文件不完整")
            offset += count
        }
    }

    private fun InputStream.copyBoundedTo(target: File, maxBytes: Long) {
        var copied = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= maxBytes) { "备份文件过大" }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun validatePoints(pointsFile: File, trackIds: Map<String, Int>): Long {
        var pointCount = 0L
        var lastTrackIndex = -1
        var lastSequence = -1L
        pointsFile.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val point = parsePoint(JSONObject(line))
                val trackIndex = trackIds[point.trackId] ?: error("轨迹点引用了不存在的轨迹")
                require(trackIndex >= lastTrackIndex) { "轨迹点顺序无效" }
                if (trackIndex != lastTrackIndex) {
                    lastTrackIndex = trackIndex
                    lastSequence = -1L
                }
                require(point.sequence > lastSequence) { "轨迹点序号重复或顺序错误" }
                lastSequence = point.sequence
                pointCount += 1
            }
        }
        return pointCount
    }

    private fun validateMomentsAndAttachments(
        files: Map<String, File>,
        trackIds: Set<String>,
        manifest: JSONObject,
        schemaVersion: Int,
        pointsFile: File,
    ): Pair<Int, Int> {
        if (schemaVersion < 2) return 0 to 0
        val momentsJson = JSONArray(File(files.getValue(MOMENTS_FILE).path).readText(Charsets.UTF_8))
        val momentTracks = HashMap<String, String>(momentsJson.length())
        val momentPointReferences = HashSet<Pair<String, Long>>()
        for (index in 0 until momentsJson.length()) {
            val moment = parseMoment(momentsJson.getJSONObject(index))
            require(moment.trackId in trackIds) { "图文记录引用了不存在的轨迹" }
            require(moment.id !in momentTracks) { "备份中存在重复图文记录 ID" }
            momentTracks[moment.id] = moment.trackId
            moment.pointSequence?.let { sequence ->
                require(sequence >= 0L) { "图文记录关联的轨迹点序号无效" }
                momentPointReferences += moment.trackId to sequence
            }
        }

        val attachmentsJson = JSONArray(File(files.getValue(ATTACHMENTS_FILE).path).readText(Charsets.UTF_8))
        val attachmentIds = HashSet<String>(attachmentsJson.length())
        val referencedMedia = HashSet<String>(attachmentsJson.length())
        for (index in 0 until attachmentsJson.length()) {
            val row = parseArchiveAttachment(attachmentsJson.getJSONObject(index))
            val attachment = row.attachment
            require(attachment.id !in attachmentIds) { "备份中存在重复图片 ID" }
            attachmentIds += attachment.id
            require(attachment.trackId in trackIds) { "图片引用了不存在的轨迹" }
            val momentId = attachment.momentId ?: error("图片缺少图文记录关联")
            require(momentTracks[momentId] == attachment.trackId) { "图片和图文记录的轨迹关系无效" }
            require(attachment.contentType.startsWith("image/")) { "备份包含非图片附件" }
            require(attachment.fileSize in 1L..MAX_IMAGE_BYTES) { "图片大小无效" }
            require(row.mediaEntry == "media/${attachment.id}.${row.mediaEntry.substringAfterLast('.').lowercase()}") {
                "图片文件名无效"
            }
            val media = files[row.mediaEntry] ?: error("备份缺少图文图片文件")
            require(media.length() == attachment.fileSize) { "图片大小与清单不一致" }
            referencedMedia += row.mediaEntry
        }
        val archiveMedia = files.keys.filterTo(HashSet(), ::isMediaEntry)
        require(archiveMedia == referencedMedia) { "备份中的图片文件和图片清单不一致" }
        require(manifest.getInt("momentCount") == momentsJson.length()) { "图文记录数量与清单不一致" }
        require(manifest.getInt("attachmentCount") == attachmentsJson.length()) { "图片数量与清单不一致" }
        validateMomentPointReferences(pointsFile, momentPointReferences)
        return momentsJson.length() to attachmentsJson.length()
    }

    private fun validateMomentPointReferences(pointsFile: File, references: Set<Pair<String, Long>>) {
        if (references.isEmpty()) return
        val found = HashSet<Pair<String, Long>>(references.size)
        pointsFile.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val point = JSONObject(line)
                val reference = point.getString("trackId") to point.getLong("sequence")
                if (reference in references) found += reference
            }
        }
        require(found == references) { "图文记录引用了备份中不存在的轨迹点" }
    }

    private fun readMomentRows(directory: File, importedTrackIds: Set<String>): List<TrackMomentEntity> {
        val file = File(directory, MOMENTS_FILE)
        if (!file.isFile || importedTrackIds.isEmpty()) return emptyList()
        val json = JSONArray(file.readText(Charsets.UTF_8))
        return (0 until json.length()).map { parseMoment(json.getJSONObject(it)) }
            .filter { it.trackId in importedTrackIds }
    }

    private fun readAttachmentRows(directory: File, importedTrackIds: Set<String>): List<BackupAttachment> {
        val file = File(directory, ATTACHMENTS_FILE)
        if (!file.isFile || importedTrackIds.isEmpty()) return emptyList()
        val json = JSONArray(file.readText(Charsets.UTF_8))
        return (0 until json.length()).map { parseArchiveAttachment(json.getJSONObject(it)) }
            .filter { it.attachment.trackId in importedTrackIds }
    }

    private fun parseMoment(json: JSONObject): TrackMomentEntity {
        val text = json.optString("text", "")
        require(text.length <= MAX_MOMENT_TEXT_LENGTH) { "图文记录文字过长" }
        return TrackMomentEntity(
            id = json.requiredString("id").also(::requireUuid),
            trackId = json.requiredString("trackId"),
            text = text,
            createdAt = json.getLong("createdAt"),
            pointSequence = json.optionalLong("pointSequence"),
        )
    }

    private fun parseArchiveAttachment(json: JSONObject): BackupAttachment {
        val mediaEntry = json.requiredString("mediaEntry")
        require(isMediaEntry(mediaEntry)) { "图片文件名无效" }
        val id = json.requiredString("id").also(::requireUuid)
        val basename = mediaEntry.substringAfterLast('/')
        require(basename.substringBeforeLast('.') == id) { "图片 ID 和文件名不一致" }
        val momentId = json.requiredString("momentId").also(::requireUuid)
        val contentType = json.requiredString("contentType").lowercase(Locale.ROOT)
        val fileSize = json.getLong("fileSize")
        return BackupAttachment(
            attachment = AttachmentEntity(
                id = id,
                trackId = json.requiredString("trackId"),
                relativePath = "track_media/$basename",
                capturedAt = json.optionalLong("capturedAt"),
                createdAt = json.getLong("createdAt"),
                contentType = contentType,
                fileSize = fileSize,
                momentId = momentId,
            ),
            mediaEntry = mediaEntry,
        )
    }

    private fun requireUuid(value: String) {
        require(runCatching { UUID.fromString(value) }.isSuccess) { "备份中包含无效 ID" }
    }

    private fun isMediaEntry(name: String): Boolean =
        name.matches(Regex("media/[0-9a-fA-F-]{36}\\.[a-z0-9]{1,8}"))

    private fun resolveStoredAttachment(relativePath: String): File? {
        if (!relativePath.startsWith("track_media/")) return null
        val root = File(context.filesDir, "track_media").canonicalFile
        val candidate = File(context.filesDir, relativePath).canonicalFile
        return candidate.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun trackForBackup(track: TrackEntity, exportedAt: Long): TrackEntity = when (track.status) {
        TrackStatus.RECORDING -> track.copy(
            status = TrackStatus.INTERRUPTED,
            durationSeconds = track.durationSeconds +
                (track.activeSince?.let { ((exportedAt - it).coerceAtLeast(0) / 1_000) } ?: 0),
            activeSince = null,
            endedAt = null,
        )
        TrackStatus.PAUSED, TrackStatus.INTERRUPTED -> track.copy(
            status = TrackStatus.INTERRUPTED,
            activeSince = null,
            endedAt = null,
        )
        else -> track
    }

    private fun restoreSafeTrack(track: TrackEntity): TrackEntity = when (track.status) {
        TrackStatus.RECORDING, TrackStatus.PAUSED, TrackStatus.INTERRUPTED -> track.copy(
            status = TrackStatus.INTERRUPTED,
            activeSince = null,
            endedAt = null,
        )
        else -> track.copy(activeSince = null)
    }

    private fun TrackEntity.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("note", note)
        .put("status", status)
        .put("startedAt", startedAt)
        .put("endedAt", endedAt.jsonValue())
        .put("durationSeconds", durationSeconds)
        .put("activeSince", activeSince.jsonValue())
        .put("distanceMeters", distanceMeters)
        .put("startLatitude", startLatitude.jsonValue())
        .put("startLongitude", startLongitude.jsonValue())
        .put("endLatitude", endLatitude.jsonValue())
        .put("endLongitude", endLongitude.jsonValue())
        .put("coordinateSystem", coordinateSystem)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    private fun TrackPointEntity.toJson(): JSONObject = JSONObject()
        .put("trackId", trackId)
        .put("sequence", sequence)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("recordedAt", recordedAt)
        .put("accuracyMeters", accuracyMeters.toDouble())
        .put("speedMetersPerSecond", speedMetersPerSecond?.toDouble().jsonValue())
        .put("altitudeMeters", altitudeMeters.jsonValue())

    private fun TrackMomentEntity.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("trackId", trackId)
        .put("text", text)
        .put("createdAt", createdAt)
        .put("pointSequence", pointSequence.jsonValue())

    private fun AttachmentEntity.toArchiveJson(mediaEntry: String): JSONObject = JSONObject()
        .put("id", id)
        .put("trackId", trackId)
        .put("momentId", momentId)
        .put("capturedAt", capturedAt.jsonValue())
        .put("createdAt", createdAt)
        .put("contentType", contentType)
        .put("fileSize", fileSize)
        .put("mediaEntry", mediaEntry)

    private fun parseTrack(json: JSONObject): TrackEntity {
        val status = json.getString("status")
        require(status in TRACK_STATUSES) { "备份中包含未知轨迹状态" }
        val startLatitude = json.optionalDouble("startLatitude")
        val startLongitude = json.optionalDouble("startLongitude")
        val endLatitude = json.optionalDouble("endLatitude")
        val endLongitude = json.optionalDouble("endLongitude")
        require((startLatitude == null) == (startLongitude == null)) { "起点坐标不完整" }
        require((endLatitude == null) == (endLongitude == null)) { "终点坐标不完整" }
        requireValidCoordinate(startLatitude, startLongitude)
        requireValidCoordinate(endLatitude, endLongitude)
        val distance = json.getDouble("distanceMeters")
        val duration = json.getLong("durationSeconds")
        require(distance.isFinite() && distance >= 0.0) { "轨迹距离无效" }
        require(duration >= 0L) { "轨迹时长无效" }
        return TrackEntity(
            id = json.requiredString("id"),
            title = json.requiredString("title"),
            note = json.optString("note", ""),
            status = status,
            startedAt = json.getLong("startedAt"),
            endedAt = json.optionalLong("endedAt"),
            durationSeconds = duration,
            activeSince = json.optionalLong("activeSince"),
            distanceMeters = distance,
            startLatitude = startLatitude,
            startLongitude = startLongitude,
            endLatitude = endLatitude,
            endLongitude = endLongitude,
            coordinateSystem = json.optString("coordinateSystem", "GCJ-02").ifBlank { "GCJ-02" },
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
        )
    }

    private fun parsePoint(json: JSONObject): TrackPointEntity {
        val latitude = json.getDouble("latitude")
        val longitude = json.getDouble("longitude")
        val accuracy = json.getDouble("accuracyMeters")
        val speed = json.optionalDouble("speedMetersPerSecond")
        val altitude = json.optionalDouble("altitudeMeters")
        requireValidCoordinate(latitude, longitude)
        require(accuracy.isFinite() && accuracy >= 0.0 && accuracy <= Float.MAX_VALUE) { "定位精度无效" }
        require(speed == null || (speed.isFinite() && speed >= 0.0 && speed <= Float.MAX_VALUE)) { "轨迹速度无效" }
        require(altitude == null || altitude.isFinite()) { "海拔数据无效" }
        return TrackPointEntity(
            trackId = json.requiredString("trackId"),
            sequence = json.getLong("sequence").also { require(it >= 0L) { "轨迹点序号无效" } },
            latitude = latitude,
            longitude = longitude,
            recordedAt = json.getLong("recordedAt"),
            accuracyMeters = accuracy.toFloat(),
            speedMetersPerSecond = speed?.toFloat(),
            altitudeMeters = altitude,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun ZipInputStream.copyEntryTo(target: File, maxBytes: Long): Long {
        var copied = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= maxBytes) { "备份中的文件过大" }
                output.write(buffer, 0, count)
            }
        }
        return copied
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.writeFileEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(this, IO_BUFFER_SIZE) }
        closeEntry()
    }

    private fun JSONObject.requiredString(key: String): String {
        require(has(key) && !isNull(key)) { "备份缺少 $key" }
        return getString(key).also { require(it.isNotBlank()) { "备份字段 $key 为空" } }
    }

    private fun JSONObject.optionalLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return getLong(key)
    }

    private fun JSONObject.optionalDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return getDouble(key)
    }

    private fun Any?.jsonValue(): Any = this ?: JSONObject.NULL

    private fun requireValidCoordinate(latitude: Double?, longitude: Double?) {
        require(latitude == null || latitude.isFinite() && latitude in -90.0..90.0) { "纬度无效" }
        require(longitude == null || longitude.isFinite() && longitude in -180.0..180.0) { "经度无效" }
    }

    private companion object {
        const val ARCHIVE_FORMAT = "com.footprint.app.backup"
        const val ARCHIVE_VERSION = 3
        const val LEGACY_ARCHIVE_VERSION = 1
        const val MANIFEST_FILE = "manifest.json"
        const val TRACKS_FILE = "tracks.json"
        const val POINTS_FILE = "points.jsonl"
        const val MOMENTS_FILE = "moments.json"
        const val ATTACHMENTS_FILE = "attachments.json"
        const val ZIP_FILE = "FootPrint-backup.zip"
        const val ARCHIVE_FILE = "FootPrint-backup.fpbackup"
        const val POINT_PAGE_SIZE = 500
        const val INSERT_BATCH_SIZE = 500
        const val IO_BUFFER_SIZE = 32 * 1024
        const val MAX_MANIFEST_BYTES = 1024L * 1024L
        const val MAX_TRACKS_BYTES = 128L * 1024L * 1024L
        const val MAX_POINTS_BYTES = 768L * 1024L * 1024L
        const val MAX_METADATA_BYTES = 128L * 1024L * 1024L
        const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
        const val MAX_MOMENT_TEXT_LENGTH = 10_000
        const val MAX_TOTAL_BYTES = 896L * 1024L * 1024L
        const val MAX_ENCRYPTED_BYTES = MAX_TOTAL_BYTES + 2L * 1024L * 1024L
        const val ZIP_OVERHEAD_BYTES = 1L * 1024L * 1024L
        const val MIN_PASSWORD_LENGTH = 8
        const val KEY_BITS = 256
        const val SALT_SIZE = 16
        const val NONCE_SIZE = 12
        const val NONCE_PREFIX_SIZE = NONCE_SIZE - 4
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val ENVELOPE_VERSION = 2
        const val HEADER_SIZE = 4 + 1 + 4 + SALT_SIZE + NONCE_PREFIX_SIZE
        const val RECORD_AAD_SIZE = 8
        const val PBKDF2_ITERATIONS = 600_000
        const val MIN_PBKDF2_ITERATIONS = 100_000
        const val MAX_PBKDF2_ITERATIONS = 1_000_000
        val MAGIC = byteArrayOf(0x46, 0x50, 0x42, 0x4b)
        val secureRandom = SecureRandom()
        val ARCHIVE_FILES = setOf(MANIFEST_FILE, TRACKS_FILE, POINTS_FILE, MOMENTS_FILE, ATTACHMENTS_FILE)
        val LEGACY_ARCHIVE_FILES = setOf(MANIFEST_FILE, TRACKS_FILE, POINTS_FILE)
        val TRACK_STATUSES = setOf(
            TrackStatus.RECORDING,
            TrackStatus.PAUSED,
            TrackStatus.COMPLETED,
            TrackStatus.INTERRUPTED,
        )
    }
}
