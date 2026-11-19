package com.stroexd.hsdecktracker.powerlog

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep
import com.stroexd.hsdecktracker.core.powerlog.enableConstructedGameLogs
import com.stroexd.hsdecktracker.core.powerlog.resolvePowerLogAttachOffset
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.system.exitProcess

internal const val ROOT_UID = 0
internal const val SHELL_UID = 2000

internal fun isPrivilegedServiceUid(uid: Int): Boolean = uid == ROOT_UID || uid == SHELL_UID
internal const val POWER_LOG_CHUNK_SIZE = 128 * 1024
internal const val KEY_STATUS = "status"
internal const val KEY_FILE_ID = "fileId"
internal const val KEY_OFFSET = "offset"
internal const val KEY_MODIFIED = "modified"
internal const val KEY_BYTES = "bytes"
internal const val KEY_RESET = "reset"
internal const val KEY_SKIP_PARTIAL = "skipPartial"
internal const val KEY_REPLAYING_MATCH = "replayingMatch"
internal const val KEY_LOG_SESSION = "logSession"
internal const val KEY_DECK_CHUNK = "deckChunk"
internal const val STATUS_FOUND = 1
internal const val STATUS_ABSENT = 2
internal const val STATUS_FAILED = 3

internal enum class GameLogSetupResult {
    UPDATED, ALREADY_ENABLED, GAME_FILES_MISSING, FAILED,
}

/** Shizuku UserService. The caller never supplies a filesystem location. */
@Keep
class PowerLogReaderService(context: Context) : IPowerLogReader.Stub() {
    private val appUid = context.applicationInfo.uid
    private val allowedVolume = Paths.get("/storage/emulated", (appUid / ANDROID_USER_RANGE).toString())

    init {
        check(isPrivilegedServiceUid(Process.myUid()))
    }

    override fun readChunk(fileId: String, offset: Long, attachAtEnd: Boolean, deckFileId: String, deckOffset: Long): Bundle {
        checkCaller()
        require(fileId.length <= 500 && deckFileId.length <= 500 && offset >= 0 && deckOffset >= 0)
        return try {
            inAuthorizedDirectory(LOG_DIRECTORY) { logs ->
                val session = logs.asSequence().map { it.fileName }
                    .filter { SESSION_DIRECTORY.matches(it.toString()) }
                    .sortedByDescending { it.toString() }
                    .firstOrNull { attributes(logs, it)?.isDirectory == true }
                if (session == null) {
                    readSession(logs, LOG_DIRECTORY, fileId, offset, attachAtEnd, deckFileId, deckOffset)
                } else {
                    logs.newDirectoryStream(session, NOFOLLOW_LINKS).use {
                        readSession(it, LOG_DIRECTORY.resolve(session), fileId, offset, attachAtEnd, deckFileId, deckOffset)
                    }
                }
            }
        } catch (_: NoSuchFileException) {
            status(STATUS_ABSENT)
        } catch (_: IOException) {
            status(STATUS_FAILED)
        } catch (_: SecurityException) {
            status(STATUS_FAILED)
        }
    }

    override fun enableGameLogging(): Int {
        checkCaller()
        return try {
            inAuthorizedDirectory(GAME_FILES, ::enableGameLoggingIn).ordinal
        } catch (_: NoSuchFileException) {
            GameLogSetupResult.GAME_FILES_MISSING.ordinal
        } catch (_: IOException) {
            GameLogSetupResult.FAILED.ordinal
        } catch (_: SecurityException) {
            GameLogSetupResult.FAILED.ordinal
        }
    }

    private fun enableGameLoggingIn(directory: SecureDirectoryStream<Path>): GameLogSetupResult {
        val original = attributes(directory, LOG_CONFIG_NAME)
        if (original != null && (!original.isRegularFile || original.size() > MAX_CONFIG_BYTES)) {
            return GameLogSetupResult.FAILED
        }
        val text = if (original == null) "" else directory.newByteChannel(LOG_CONFIG_NAME, READ_OPTIONS).use { channel ->
            val bytes = read(channel, 0, MAX_CONFIG_BYTES + 1)
            if (bytes.size > MAX_CONFIG_BYTES) return GameLogSetupResult.FAILED
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        }
        val enabled = enableConstructedGameLogs(text)
        if (enabled == text) return GameLogSetupResult.ALREADY_ENABLED
        val temporary = Paths.get(".log.config-hearthlog-${UUID.randomUUID()}.tmp")
        try {
            directory.newByteChannel(temporary, WRITE_OPTIONS).use { channel ->
                val pending = ByteBuffer.wrap(enabled.toByteArray(Charsets.UTF_8))
                while (pending.hasRemaining()) {
                    if (channel.write(pending) == 0) throw IOException("The game configuration write did not progress")
                }
            }
            val current = attributes(directory, LOG_CONFIG_NAME)
            if (current?.fileKey() != original?.fileKey() || current?.size() != original?.size() ||
                current?.lastModifiedTime() != original?.lastModifiedTime() || current?.isRegularFile != original?.isRegularFile
            ) return GameLogSetupResult.FAILED
            directory.move(temporary, directory, LOG_CONFIG_NAME)
        } finally {
            runCatching { directory.deleteFile(temporary) }
        }
        return GameLogSetupResult.UPDATED
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        if (!isPrivilegedServiceUid(caller) && caller != appUid) throw SecurityException("Invalid caller")
        exitProcess(0)
    }

    private fun checkCaller() {
        if (!isPrivilegedServiceUid(Process.myUid()) || Binder.getCallingUid() != appUid) {
            throw SecurityException("Invalid caller")
        }
    }

    private fun <T> inAuthorizedDirectory(path: Path, block: (SecureDirectoryStream<Path>) -> T): T {
        if (allowedVolume.toRealPath() != allowedVolume) throw SecurityException("The storage path was redirected")
        Files.newDirectoryStream(allowedVolume).use { volume ->
            val root = volume as? SecureDirectoryStream<Path> ?: throw IOException("Secure directory access unavailable")
            return withDirectory(root, path, block = block)
        }
    }

    private fun readSession(
        directory: SecureDirectoryStream<Path>,
        path: Path,
        fileId: String, offset: Long, attachAtEnd: Boolean,
        deckFileId: String, deckOffset: Long,
    ): Bundle {
        val power = logInfo(directory, path, POWER_LOG_NAME)
        val data = power?.let { readLog(directory, POWER_LOG_NAME, it, fileId, offset, attachAtEnd) } ?: status(STATUS_ABSENT)
        data.putString(KEY_LOG_SESSION, path.toString())
        val deckChunk = try {
            logInfo(directory, path, DECK_LOG_NAME)?.let { readLog(directory, DECK_LOG_NAME, it, deckFileId, deckOffset, false) }
        } catch (_: IOException) {
            null
        }
        if (deckChunk != null) data.putBundle(KEY_DECK_CHUNK, deckChunk)
        return data
    }

    private fun logInfo(directory: SecureDirectoryStream<Path>, parent: Path, name: Path): LogInfo? {
        val attrs = attributes(directory, name) ?: return null
        if (!attrs.isRegularFile) return null
        val identity = attrs.fileKey()?.toString() ?: return null
        return LogInfo(parent.resolve(name), identity, attrs.lastModifiedTime().toMillis())
    }

    private fun readLog(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        log: LogInfo,
        previousId: String,
        previousOffset: Long,
        attachAtEnd: Boolean,
    ): Bundle {
        val attrs = attributes(directory, name)
        if (attrs?.isRegularFile != true || attrs.fileKey()?.toString() != log.identity) {
            return status(STATUS_ABSENT)
        }
        directory.newByteChannel(name, READ_OPTIONS).use { channel ->
            val size = channel.size()
            val currentId = log.relativePath.toString() + "|" + log.identity
            val reset = attachAtEnd || previousId != currentId || previousOffset > size
            val position = if (attachAtEnd) resolvePowerLogAttachOffset(channel, size) else if (reset) 0L else previousOffset
            val bytes = read(channel, position, (size - position).coerceAtMost(POWER_LOG_CHUNK_SIZE.toLong()).toInt())
            val skipPartial = position == size && size > 0 &&
                read(channel, size - 1, 1).firstOrNull() != '\n'.code.toByte()
            if (attributes(directory, name)?.fileKey()?.toString() != log.identity) {
                return status(STATUS_ABSENT)
            }
            return status(STATUS_FOUND).apply {
                putString(KEY_FILE_ID, currentId)
                putLong(KEY_OFFSET, position + bytes.size)
                putLong(KEY_MODIFIED, log.lastModified)
                putByteArray(KEY_BYTES, bytes)
                putBoolean(KEY_RESET, reset)
                putBoolean(KEY_SKIP_PARTIAL, skipPartial)
                putBoolean(KEY_REPLAYING_MATCH, attachAtEnd && position < size)
            }
        }
    }

    private fun read(channel: SeekableByteChannel, offset: Long, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(count)
        channel.position(offset)
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // Gather a full Binder chunk even when a filesystem read is short.
        }
        return buffer.array().copyOf(buffer.position())
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes? =
        try {
            directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?.readAttributes()
        } catch (_: NoSuchFileException) {
            null
        }

    /** Keep each directory handle open so a replacement path cannot redirect a later read. */
    private fun <T> withDirectory(
        root: SecureDirectoryStream<Path>,
        path: Path,
        index: Int = 0,
        block: (SecureDirectoryStream<Path>) -> T,
    ): T {
        if (index == path.nameCount) return block(root)
        root.newDirectoryStream(path.getName(index), NOFOLLOW_LINKS).use {
            return withDirectory(it, path, index + 1, block)
        }
    }

    private fun status(value: Int) = Bundle().apply { putInt(KEY_STATUS, value) }

    private data class LogInfo(val relativePath: Path, val identity: String, val lastModified: Long)

    companion object {
        private const val ANDROID_USER_RANGE = 100_000
        private const val STANDARD_PACKAGE = "com.blizzard.wtcg.hearthstone"
        private const val MAX_CONFIG_BYTES = 64 * 1024
        private val GAME_FILES = Paths.get("Android", "data", STANDARD_PACKAGE, "files")
        private val LOG_DIRECTORY = GAME_FILES.resolve("Logs")
        private val LOG_CONFIG_NAME = Paths.get("log.config")
        private val POWER_LOG_NAME = Paths.get("Power.log")
        private val DECK_LOG_NAME = Paths.get("Decks.log")
        private val SESSION_DIRECTORY = Regex("Hearthstone_\\d{4}(?:_\\d{2}){5}")
        private val READ_OPTIONS = setOf<OpenOption>(READ, NOFOLLOW_LINKS)
        private val WRITE_OPTIONS = setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
    }
}
