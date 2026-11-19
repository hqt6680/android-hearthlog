package com.stroexd.hsdecktracker.powerlog

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

enum class PowerLogSourceState {
    UNAVAILABLE,
    SHELL_REQUIRED,
    PERMISSION_REQUIRED,
    REQUESTING_PERMISSION,
    PERMISSION_DENIED,
    READY,
    CONNECTING,
    WAITING_FOR_LOG,
    READING,
    ERROR,
}

data class PowerLogInput(
    val reset: Boolean,
    val lines: List<String>,
    val idle: Boolean = false,
    val completeRead: Boolean = false,
    val deckLogLines: List<String> = emptyList(),
    val deckLogReset: Boolean = false,
    val acceptQueuedDeck: Boolean = true,
)

/** An app-scoped connection. A log session runs only while [lines] has a collector. */
class ShizukuLogSource(context: Context) {
    private val collecting = AtomicBoolean(false)
    private val requestPending = AtomicBoolean(false)
    private val requestDenied = AtomicBoolean(false)
    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.applicationContext, PowerLogReaderService::class.java),
    ).processNameSuffix("powerlog").daemon(false).tag("powerlog_reader").version(7)
    private val setupArgs = Shizuku.UserServiceArgs(
        ComponentName(context.applicationContext, PowerLogReaderService::class.java),
    ).processNameSuffix("powerlog_setup").daemon(false).tag("powerlog_setup").version(1)

    private val mutableState = MutableStateFlow(permissionState())
    val state: StateFlow<PowerLogSourceState> = mutableState.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        if (!collecting.get()) mutableState.value = permissionState()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        requestPending.set(false)
        requestDenied.set(false)
        mutableState.value = PowerLogSourceState.UNAVAILABLE
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code == REQUEST_CODE) {
            requestPending.set(false)
            requestDenied.set(grant != PackageManager.PERMISSION_GRANTED)
            mutableState.value = permissionState()
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun requestPermission() {
        val current = permissionState()
        if (current != PowerLogSourceState.PERMISSION_REQUIRED && current != PowerLogSourceState.PERMISSION_DENIED) {
            if (!collecting.get()) mutableState.value = current
            return
        }
        if (!requestPending.compareAndSet(false, true)) return
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                requestDenied.set(true)
                requestPending.set(false)
                mutableState.value = PowerLogSourceState.PERMISSION_DENIED
                return
            }
            requestDenied.set(false)
            mutableState.value = PowerLogSourceState.REQUESTING_PERMISSION
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: RuntimeException) {
            requestPending.set(false)
            mutableState.value = PowerLogSourceState.ERROR
        }
    }

    internal suspend fun enableGameLogging(): GameLogSetupResult = withContext(Dispatchers.IO) {
        if (permissionState() != PowerLogSourceState.READY) return@withContext GameLogSetupResult.FAILED
        val connection = ReaderConnection()
        try {
            Shizuku.bindUserService(setupArgs, connection)
            val binder = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { connection.ready.await() }
                ?: return@withContext GameLogSetupResult.FAILED
            val code = IPowerLogReader.Stub.asInterface(binder).enableGameLogging()
            GameLogSetupResult.entries.getOrNull(code) ?: GameLogSetupResult.FAILED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GameLogSetupResult.FAILED
        } finally {
            runCatching { Shizuku.unbindUserService(setupArgs, connection, true) }
        }
    }

    fun lines(): Flow<PowerLogInput> = flow {
        if (!collecting.compareAndSet(false, true)) return@flow
        try {
            emit(RESET)
            while (currentCoroutineContext().isActive) {
                val status = permissionState()
                if (status != PowerLogSourceState.READY) {
                    mutableState.value = status
                    delay(POLL_MILLIS)
                    continue
                }
                val connection = ReaderConnection()
                mutableState.value = PowerLogSourceState.CONNECTING
                try {
                    Shizuku.bindUserService(args, connection)
                    val binder = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { connection.ready.await() }
                    if (binder == null) {
                        mutableState.value = PowerLogSourceState.ERROR
                    } else {
                        readLog(connection, binder) { emit(it) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value = PowerLogSourceState.ERROR
                } finally {
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                }
                emit(RESET)
                delay(RECONNECT_MILLIS)
            }
        } finally {
            collecting.set(false)
            mutableState.value = permissionState()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun readLog(
        connection: ReaderConnection,
        binder: IBinder,
        emit: suspend (PowerLogInput) -> Unit,
    ) {
        val reader = IPowerLogReader.Stub.asInterface(binder)
        val framing = LineFraming()
        val deckFraming = LineFraming()
        var fileId = ""
        var offset = 0L
        var session: String? = null
        var deckFileId = ""
        var deckOffset = 0L
        var readingExistingDeckLog = true
        var attachAtEnd = true
        var latestModified = Long.MIN_VALUE
        val attachedFiles = LinkedHashSet<String>()
        while (currentCoroutineContext().isActive && connection.current === binder && binder.pingBinder()) {
            if (permissionState() != PowerLogSourceState.READY) return
            val message = reader.readChunk(fileId, offset, attachAtEnd, deckFileId, deckOffset)
            val currentSession = message.getString(KEY_LOG_SESSION)
            val newSession = currentSession != null && session != null && currentSession != session
            val deckChunk = message.getBundle(KEY_DECK_CHUNK)?.takeIf { it.getInt(KEY_STATUS) == STATUS_FOUND }
            val nextDeckFileId = deckChunk?.getString(KEY_FILE_ID).orEmpty()
            val resetDeck = currentSession != session || nextDeckFileId != deckFileId || deckChunk?.getBoolean(KEY_RESET) == true
            if (resetDeck) deckFraming.reset(false)
            session = currentSession
            deckFileId = nextDeckFileId
            deckOffset = deckChunk?.getLong(KEY_OFFSET) ?: 0
            val deckBytes = deckChunk?.getByteArray(KEY_BYTES) ?: ByteArray(0)
            val deckLines = deckFraming.accept(deckBytes)
            val acceptQueue = !readingExistingDeckLog ||
                message.getInt(KEY_STATUS) == STATUS_ABSENT ||
                message.getInt(KEY_STATUS) == STATUS_FOUND &&
                (message.getLong(KEY_OFFSET) == 0L || message.getBoolean(KEY_REPLAYING_MATCH))
            if (deckBytes.size == POWER_LOG_CHUNK_SIZE) {
                // Drain collection metadata before feeding the matching Power.log prefix.
                emit(PowerLogInput(false, emptyList(), deckLogLines = deckLines, deckLogReset = resetDeck, acceptQueuedDeck = acceptQueue))
                continue
            }
            readingExistingDeckLog = false
            when (message.getInt(KEY_STATUS)) {
                STATUS_ABSENT -> {
                    val resetPower = fileId.isNotEmpty()
                    if (fileId.isNotEmpty()) {
                        framing.reset(false)
                        attachAtEnd = !newSession
                    } else if (attachedFiles.isEmpty()) {
                        // With no existing file at attach time, its first CREATE_GAME is new.
                        attachAtEnd = false
                    }
                    fileId = ""
                    offset = 0L
                    if (resetPower || resetDeck || deckLines.isNotEmpty()) {
                        emit(PowerLogInput(resetPower, emptyList(), deckLogLines = deckLines,
                            deckLogReset = resetDeck, acceptQueuedDeck = acceptQueue))
                    }
                    mutableState.value = PowerLogSourceState.WAITING_FOR_LOG
                    delay(POLL_MILLIS)
                }
                STATUS_FOUND -> {
                    val reset = message.getBoolean(KEY_RESET)
                    val currentId = checkNotNull(message.getString(KEY_FILE_ID))
                    val modified = message.getLong(KEY_MODIFIED)
                    if (!attachAtEnd && currentId != fileId &&
                        (currentId in attachedFiles || modified < latestModified)
                    ) {
                        // An older or previously consumed session can reappear when a log is removed.
                        attachAtEnd = true
                        continue
                    }
                    if (reset) framing.reset(message.getBoolean(KEY_SKIP_PARTIAL))
                    fileId = currentId
                    latestModified = maxOf(modified, latestModified)
                    if (attachedFiles.add(currentId) && attachedFiles.size > MAX_REMEMBERED_FILES) {
                        attachedFiles.remove(attachedFiles.first())
                    }
                    offset = message.getLong(KEY_OFFSET)
                    val bytes = message.getByteArray(KEY_BYTES) ?: ByteArray(0)
                    attachAtEnd = false
                    mutableState.value = PowerLogSourceState.READING
                    val decoded = framing.accept(bytes)
                    if (reset || decoded.isNotEmpty() || resetDeck || deckLines.isNotEmpty()) {
                        emit(PowerLogInput(reset, decoded,
                            completeRead = bytes.size < POWER_LOG_CHUNK_SIZE && framing.atLineBoundary,
                            deckLogLines = deckLines, deckLogReset = resetDeck, acceptQueuedDeck = acceptQueue))
                    } else if (bytes.isEmpty() && framing.atLineBoundary) {
                        emit(PowerLogInput(false, emptyList(), idle = true))
                    }
                    if (bytes.size < POWER_LOG_CHUNK_SIZE) {
                        delay(DIRECT_LOG_POLL_MILLIS)
                    }
                }
                else -> {
                    mutableState.value = PowerLogSourceState.ERROR
                    return
                }
            }
        }
    }

    private fun permissionState(): PowerLogSourceState {
        return try {
            when {
                !Shizuku.pingBinder() || Shizuku.getVersion() < 13 -> PowerLogSourceState.UNAVAILABLE
                !isPrivilegedServiceUid(Shizuku.getUid()) -> PowerLogSourceState.SHELL_REQUIRED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> PowerLogSourceState.READY
                requestPending.get() -> PowerLogSourceState.REQUESTING_PERMISSION
                requestDenied.get() -> PowerLogSourceState.PERMISSION_DENIED
                else -> PowerLogSourceState.PERMISSION_REQUIRED
            }
        } catch (_: RuntimeException) {
            PowerLogSourceState.UNAVAILABLE
        }
    }

    private class ReaderConnection : ServiceConnection {
        val ready = CompletableDeferred<IBinder?>()
        @Volatile var current: IBinder? = null
            private set

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            current = service
            ready.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            current = null
            ready.complete(null)
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    private class LineFraming {
        private var carry = ByteArray(0)
        private var skipPartial = false
        val atLineBoundary: Boolean get() = carry.isEmpty() && !skipPartial

        fun reset(skip: Boolean) {
            carry = ByteArray(0)
            skipPartial = skip
        }

        fun accept(bytes: ByteArray): List<String> {
            if (bytes.isEmpty()) return emptyList()
            val lines = mutableListOf<String>()
            val combined = carry + bytes
            var start = 0
            for (index in combined.indices) {
                if (combined[index] != '\n'.code.toByte()) continue
                if (!skipPartial && index - start <= MAX_LINE_BYTES) {
                    val end = if (index > start && combined[index - 1] == '\r'.code.toByte()) index - 1 else index
                    lines += String(combined, start, end - start, Charsets.UTF_8)
                }
                skipPartial = false
                start = index + 1
            }
            if (combined.size - start > MAX_LINE_BYTES) {
                carry = ByteArray(0)
                skipPartial = true
            } else {
                carry = combined.copyOfRange(start, combined.size)
            }
            return lines
        }
    }

    companion object {
        private const val REQUEST_CODE = 5127
        private const val CONNECT_TIMEOUT_MILLIS = 5000L
        private const val RECONNECT_MILLIS = 1500L
        private const val POLL_MILLIS = 500L
        private const val DIRECT_LOG_POLL_MILLIS = 250L
        private const val MAX_REMEMBERED_FILES = 32
        private const val MAX_LINE_BYTES = 128 * 1024
        private val RESET = PowerLogInput(true, emptyList(), deckLogReset = true)
    }
}
