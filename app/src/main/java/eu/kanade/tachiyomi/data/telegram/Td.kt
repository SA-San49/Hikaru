package eu.kanade.tachiyomi.data.telegram

import android.content.Context
import android.os.Build
import android.util.Base64
import eu.kanade.domain.telegram.TelegramPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okhttp3.Headers
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.min

@Suppress("TooManyFunctions", "LargeClass", "MagicNumber")
object Td {

    sealed class Auth {
        data object Idle : Auth()
        data object Unavailable : Auth()
        data object Starting : Auth()
        data object WaitPhone : Auth()
        data object WaitCode : Auth()
        data class WaitPassword(val hint: String) : Auth()
        data class WaitRegistration(val terms: String) : Auth()
        data class WaitOtherDevice(val link: String) : Auth()
        data object Ready : Auth()
        data object Closed : Auth()
    }

    data class Chat(
        val id: Long,
        val title: String,
        val kind: Kind,
        val order: Long,
        val unread: Int,
        val archived: Boolean = false,
        val photoFileId: Int = 0,
    ) {
        enum class Kind { SAVED, PRIVATE, BOT, GROUP, CHANNEL, SECRET }
    }

    data class ChatVideo(
        val chatId: Long,
        val messageId: Long,
        val fileId: Int,
        val title: String,
        val duration: Int,
        val date: Int,
        val size: Long,
        val thumb: String?,
        val caption: String = "",
        val fileName: String = "",
        val thumbFileId: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val isAnimation: Boolean = false,
    )

    enum class SearchIn(val key: String) {
        CHAT("chat"),
        VIDEO("video"),
        BOTH("both"),
        ;

        companion object {
            fun fromKey(key: String?): SearchIn = entries.firstOrNull { it.key == key } ?: CHAT
        }
    }

    data class ChatVideoPage(
        val videos: List<ChatVideo>,
        val nextCursor: Long,
        val scanned: Int,
        val exhausted: Boolean,
    )

    data class ChatHistoryVideos(
        val videos: List<ChatVideo>,
        val nextCursor: Long,
        val more: Boolean,
        val note: String? = null,
    )

    data class ChatImage(
        val chatId: Long,
        val messageId: Long,
        val fileId: Int,
        val fileName: String = "",
        val caption: String = "",
        val minithumb: String? = null,
    )

    data class ChatCatalogueBatch(
        val videos: List<ChatVideo>,
        val images: List<ChatImage>,
        val nextCursor: Long,
        val more: Boolean,
        val note: String? = null,
    )

    data class FileState(
        val path: String,
        val size: Long,
        val downloadOffset: Long,
        val readyFromOffset: Long,
        val complete: Boolean,
        val active: Boolean,
        val canBeDownloaded: Boolean,
    )

    private const val BUSY_TIMEOUT_MS = 30_000L
    private const val MIN_PHONE_LEN = 8
    private const val API_HASH_HEX_LEN = 32
    private const val CHAT_LOAD_PAGE = 100
    private const val CHAT_LOAD_MAX = 2_000
    private const val SEARCH_CHATS_LIMIT = 20
    private const val SEARCH_CHATS_TAKE = 12
    private const val LINK_TAIL = 10
    private const val SCAN_MESSAGES_PER_CALL = 400
    private const val BOTH_TEXT_MAX = 2_000
    private const val HISTORY_PAGE = 100
    private const val SEARCH_HITS_PER_CALL = 100
    private const val POST_VIDEOS_MAX = 2_000
    private const val TAIL_PAGE = 100
    private const val TAIL_MAX_PAGES = 60
    private const val THUMB_WAIT_MS = 20_000L
    private const val THUMB_POLL_MS = 35L
    private const val THUMB_MIN_WIDTH = 320
    private const val THUMB_DOWNLOAD_PRIORITY = 24
    private const val DOWNLOAD_PRIORITY = 32
    private const val STREAM_EVICT_DELAY_MS = 10_000L
    private const val POSTER_SEARCH_LIMIT = 30
    private const val POSTER_CHUNKS_MAX = 4
    data class ChunkPlan(
        val totalBytes: Long,
        val sizeMb: Double,
        val chunkMb: Int,
        val chunkBytes: Long,
        val totalChunks: Int,
    )

    private const val MIN_ANIMATION_BYTES = 256L * 1024
    private const val ONE_MB_BYTES = 1024L * 1024L
    const val CHUNK_CACHE_TARGET_BYTES: Long = 15L * ONE_MB_BYTES
    const val AHEAD_BYTES: Long = CHUNK_CACHE_TARGET_BYTES
    private const val SMALL_VIDEO_MB = 60.0
    private const val MEDIUM_VIDEO_MB = 360.0
    private const val SMALL_CHUNK_MB = 1
    private const val MEDIUM_CHUNK_MB = 3
    private const val LARGE_CHUNK_MB = 5
    private const val CHUNK_POLL_MS = 40L
    private const val CHUNK_IDLE_POLL_MS = 100L
    private const val PREWARM_MAX_IDLE_MS = 10_000L
    private val IMAGE_EXTENSIONS = setOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif", ".heic", ".tiff",
    )
    private val VIDEO_EXTENSIONS = setOf(
        ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".flv", ".wmv",
    )

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val restartWanted = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var client: Client? = null

    @Volatile
    private var live: Pair<Int, String>? = null

    @Volatile
    private var pending: Pair<Int, String>? = null

    @Volatile
    private var myId: Long = 0

    @Volatile
    private var savedChatId: Long = 0

    private val _auth = MutableStateFlow<Auth>(Auth.Idle)
    val auth: StateFlow<Auth> = _auth.asStateFlow()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _me = MutableStateFlow("")
    val me: StateFlow<String> = _me.asStateFlow()

    private val revisionCounter = AtomicInteger(0)
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _sentTo = MutableStateFlow("")
    val sentTo: StateFlow<String> = _sentTo.asStateFlow()

    private val chatValues = ConcurrentHashMap<Long, TdApi.Chat>()
    private val userValues = ConcurrentHashMap<Long, TdApi.User>()
    private val pendingUsers = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val mainOrder = ConcurrentHashMap<Long, Long>()
    private val archiveOrder = ConcurrentHashMap<Long, Long>()
    private val leftBehind = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val fileValues = ConcurrentHashMap<Int, TdApi.File>()
    private val fetchedFiles = ConcurrentHashMap<Int, TdApi.File>()
    private val downloadErrors = ConcurrentHashMap<Int, String>()
    private val verifiedVideoFiles = ConcurrentHashMap<Int, String>()
    private val activeStreams = ConcurrentHashMap<Int, AtomicInteger>()
    private val streamedVideoFiles = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val playbackCursors = ConcurrentHashMap<Int, Long>()
    private val streamFileSizes = ConcurrentHashMap<Int, Long>()
    private val pausedByProbe = ConcurrentHashMap<Int, AtomicInteger>()
    private val chunkBufferJobs = ConcurrentHashMap<Int, Job>()
    private val activeFileDownloads = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!isCacheMediaEnabled()) {
            scope.launch { purgeMediaFiles(context.applicationContext) }
        }
        if (client != null) return
        if (loadError != null) {
            _auth.value = Auth.Unavailable
            return
        }
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val prefs = Injekt.get<TelegramPreferences>()
            val apiId = prefs.apiId().get()
            val apiHash = prefs.apiHash().get()
            if (apiId <= 0 || apiHash.isBlank()) {
                started.set(false)
                _auth.value = Auth.Idle
                return@launch
            }
            start(apiId, apiHash)
        }
    }

    fun isCacheMediaEnabled(): Boolean =
        runCatching { Injekt.get<TelegramPreferences>().cacheMedia().get() }.getOrDefault(false)

    fun setCredentials(context: Context, apiId: Int, apiHash: String) {
        appContext = context.applicationContext
        if (apiId <= 0 || apiHash.isBlank()) return
        val wanted = apiId to apiHash
        val changed = pending != wanted
        pending = wanted
        _problem.value = null
        val running = client
        if (running == null) {
            start(apiId, apiHash)
            return
        }
        if (changed && live != wanted) restart()
    }

    fun restart() {
        _problem.value = null
        _busy.value = false
        val running = client
        if (running == null) {
            live = null
            started.set(false)
            pending?.let { (id, hash) -> start(id, hash) }
            return
        }
        restartWanted.set(true)
        _auth.value = Auth.Starting
        runCatching { running.send(TdApi.Close(), null, null) }
    }

    fun clearProblem() {
        _problem.value = null
    }

    fun signInAgain() {
        _problem.value = null
        _busy.value = false
        _sentTo.value = ""
        pending = null
        live = null
        restartWanted.set(false)
        val running = client
        if (running == null) {
            started.set(false)
            _auth.value = Auth.Idle
            return
        }
        _auth.value = Auth.Idle
        runCatching { running.send(TdApi.Close(), null, null) }
    }

    fun apiIdOf(raw: String): Int? =
        raw.trim().filter { it in '0'..'9' }
            .toIntOrNull()
            ?.takeIf { it > 0 }

    fun apiHashChars(raw: String): String =
        raw.trim().filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.lowercase()

    fun apiHashOf(raw: String): String? = apiHashChars(raw).takeIf { it.length == API_HASH_HEX_LEN }

    fun keyProblem(idText: String, hashText: String): String? {
        if (apiIdOf(idText) == null) {
            return "The api_id is the NUMBER shown on my.telegram.org (digits only)."
        }
        val chars = apiHashChars(hashText)
        if (chars.length != API_HASH_HEX_LEN) {
            return "An api_hash is exactly 32 hex characters — this one has ${chars.length}. " +
                "Copy both values from the same app on my.telegram.org."
        }
        return null
    }

    private fun fail(error: TdApi.Error) {
        _busy.value = false
        _problem.value = TelegramError.explain(error.message)
    }

    private fun note(t: Throwable) {
        _busy.value = false
        _problem.value = "Telegram library error: " + (t.message ?: t.javaClass.simpleName)
    }

    private fun armBusyTimeout() {
        scope.launch {
            delay(BUSY_TIMEOUT_MS)
            if (_busy.value) _busy.value = false
        }
    }

    private fun start(apiId: Int, apiHash: String) {
        if (client != null) return
        _auth.value = Auth.Starting
        val linkError = runCatching {
            System.loadLibrary("tdjni")
            null
        }.getOrElse { it.message ?: it.toString() }
        if (linkError != null) {
            loadError = linkError
            available = false
            started.set(false)
            _auth.value = Auth.Unavailable
            return
        }
        runCatching {
            Client.create(
                { update -> onUpdate(update) },
                { t -> note(t) },
                { t -> note(t) },
            )
        }.onSuccess { c ->
            available = true
            loadError = null
            client = c
            live = apiId to apiHash
            pending = apiId to apiHash
            started.set(true)
            _auth.value = Auth.Starting
        }.onFailure { e ->
            available = false
            loadError = e.message ?: e.toString()
            started.set(false)
            _auth.value = Auth.Unavailable
        }
    }

    fun signOut() {
        _problem.value = null
        _busy.value = false
        runCatching { client?.send(TdApi.LogOut(), null, null) }
        chatValues.clear()
        userValues.clear()
        pendingUsers.clear()
        mainOrder.clear()
        archiveOrder.clear()
        leftBehind.clear()
        fileValues.clear()
        fetchedFiles.clear()
        verifiedVideoFiles.clear()
        _chats.value = emptyList()
        _me.value = ""
        _sentTo.value = ""
        myId = 0
        savedChatId = 0
    }

    fun normalizePhone(phone: String): String {
        val keep = phone.filter { it.isDigit() || it == '+' }
        val digits = keep.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return "+" + digits.trimStart('0').ifEmpty { digits }
    }

    fun submitPhone(phone: String) {
        val number = normalizePhone(phone)
        if (number.length < MIN_PHONE_LEN) {
            _problem.value = "Include your country code, for example +1 555 123 4567."
            return
        }
        val c = client ?: run {
            _problem.value = "Telegram is not connected — tap Change API key or try again."
            return
        }
        _problem.value = null
        _busy.value = true
        _sentTo.value = number
        armBusyTimeout()
        val settings = TdApi.PhoneNumberAuthenticationSettings(
            false,
            false,
            false,
            false,
            true,
            null,
            emptyArray<String>(),
        )
        runCatching {
            c.send(
                TdApi.SetAuthenticationPhoneNumber(number, settings),
                Client.ResultHandler { result ->
                    _busy.value = false
                    if (result is TdApi.Error) fail(result)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    fun submitCode(code: String) {
        val value = code.trim()
        if (value.isEmpty()) return
        val c = client ?: run {
            _problem.value = "Telegram is not connected — try again."
            return
        }
        _problem.value = null
        _busy.value = true
        armBusyTimeout()
        runCatching {
            c.send(
                TdApi.CheckAuthenticationCode(value),
                Client.ResultHandler { result ->
                    _busy.value = false
                    if (result is TdApi.Error) fail(result)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        val c = client ?: run {
            _problem.value = "Telegram is not connected — try again."
            return
        }
        _problem.value = null
        _busy.value = true
        armBusyTimeout()
        runCatching {
            c.send(
                TdApi.CheckAuthenticationPassword(password),
                Client.ResultHandler { result ->
                    _busy.value = false
                    if (result is TdApi.Error) fail(result)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    fun submitRegistration(first: String, last: String) {
        val c = client ?: run {
            _problem.value = "Telegram is not connected — try again."
            return
        }
        _problem.value = null
        _busy.value = true
        armBusyTimeout()
        runCatching {
            c.send(
                TdApi.RegisterUser(first, last, true),
                Client.ResultHandler { result ->
                    _busy.value = false
                    if (result is TdApi.Error) fail(result)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    fun requestQrLogin() {
        val c = client ?: return
        _problem.value = null
        _busy.value = true
        armBusyTimeout()
        runCatching {
            c.send(
                TdApi.RequestQrCodeAuthentication(longArrayOf()),
                Client.ResultHandler { result ->
                    _busy.value = false
                    if (result is TdApi.Error) fail(result)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    fun loadChats() {
        val c = client ?: return
        scope.launch { refreshMe() }
        loadChatPage(c, TdApi.ChatListMain(), 0)
        loadChatPage(c, TdApi.ChatListArchive(), 0)
    }

    private fun loadChatPage(c: Client, list: TdApi.ChatList, loaded: Int) {
        if (loaded >= CHAT_LOAD_MAX) return
        runCatching {
            c.send(
                TdApi.LoadChats(list, CHAT_LOAD_PAGE),
                Client.ResultHandler { result ->
                    if (result !is TdApi.Error) loadChatPage(c, list, loaded + CHAT_LOAD_PAGE)
                },
                null,
            )
        }.onFailure { note(it) }
    }

    private suspend fun refreshMe() {
        val user = query(TdApi.GetMe()) as? TdApi.User ?: return
        myId = user.id
        userValues[user.id] = user
        _me.value = listOf(user.firstName, user.lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Me" }
        ensureSavedChat()
        publishChats()
    }

    private suspend fun ensureSavedChat() {
        val me = myId
        if (me == 0L) return
        val chat = query(TdApi.CreatePrivateChat(me, false)) as? TdApi.Chat ?: return
        savedChatId = chat.id
        chatValues[chat.id] = chat
    }

    private fun ensureUserLoaded(userId: Long) {
        if (userId == 0L || userValues.containsKey(userId) || !pendingUsers.add(userId)) return
        scope.launch {
            val u = query(TdApi.GetUser(userId)) as? TdApi.User
            if (u != null) {
                userValues[userId] = u
                publishChats()
            } else {
                pendingUsers.remove(userId)
            }
        }
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthState(update.authorizationState)
            is TdApi.UpdateUser -> {
                userValues[update.user.id] = update.user
                publishChats()
            }
            is TdApi.UpdateNewChat -> {
                chatValues[update.chat.id] = update.chat
                absorbPositions(update.chat.id, update.chat.positions)
                publishChats()
            }
            is TdApi.UpdateChatPosition -> {
                absorbPosition(update.chatId, update.position)
                publishChats()
            }
            is TdApi.UpdateChatLastMessage -> {
                absorbPositions(update.chatId, update.positions)
                publishChats()
            }
            is TdApi.UpdateChatTitle -> {
                chatValues[update.chatId]?.let { c ->
                    c.title = update.title
                    publishChats()
                }
            }
            is TdApi.UpdateChatPhoto -> {
                chatValues[update.chatId]?.let { c ->
                    c.photo = update.photo
                    publishChats()
                }
            }
            is TdApi.UpdateChatReadInbox -> publishChats()
            is TdApi.UpdateChatRemovedFromList -> {
                forgetInList(update.chatId, update.chatList)
                publishChats()
            }
            is TdApi.UpdateChatAddedToList -> {
                leftBehind.remove(update.chatId)
                publishChats()
            }
            is TdApi.UpdateFile -> {
                fileValues[update.file.id] = update.file
                _revision.value = revisionCounter.incrementAndGet()
            }
            is TdApi.UpdateConnectionState ->
                _online.value = update.state is TdApi.ConnectionStateReady
        }
    }

    private fun absorbPosition(chatId: Long, position: TdApi.ChatPosition) {
        when (position.list) {
            is TdApi.ChatListMain ->
                if (position.order == 0L) mainOrder.remove(chatId) else mainOrder[chatId] = position.order
            is TdApi.ChatListArchive ->
                if (position.order == 0L) archiveOrder.remove(chatId) else archiveOrder[chatId] = position.order
            else -> Unit
        }
        if (position.order != 0L) leftBehind.remove(chatId)
    }

    private fun absorbPositions(chatId: Long, positions: Array<TdApi.ChatPosition>?) {
        positions?.forEach { absorbPosition(chatId, it) }
    }

    private fun forgetInList(chatId: Long, list: TdApi.ChatList?) {
        when (list) {
            is TdApi.ChatListMain -> mainOrder.remove(chatId)
            is TdApi.ChatListArchive -> archiveOrder.remove(chatId)
            else -> Unit
        }
        leftBehind.add(chatId)
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _busy.value = false
                if (_auth.value != Auth.WaitPhone) _sentTo.value = ""
                _auth.value = Auth.WaitPhone
            }
            is TdApi.AuthorizationStateWaitCode -> {
                _busy.value = false
                _problem.value = null
                _auth.value = Auth.WaitCode
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _busy.value = false
                _problem.value = null
                _auth.value = Auth.WaitPassword(state.passwordHint.orEmpty())
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                _busy.value = false
                _auth.value = Auth.WaitRegistration(state.termsOfService?.text?.text.orEmpty())
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                _busy.value = false
                _auth.value = Auth.WaitOtherDevice(state.link)
            }
            is TdApi.AuthorizationStateReady -> {
                _busy.value = false
                _problem.value = null
                _auth.value = Auth.Ready
                loadChats()
            }
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing,
            -> Unit
            is TdApi.AuthorizationStateClosed -> {
                client = null
                live = null
                started.set(false)
                chatValues.clear()
                userValues.clear()
                pendingUsers.clear()
                mainOrder.clear()
                archiveOrder.clear()
                leftBehind.clear()
                fileValues.clear()
                fetchedFiles.clear()
                _chats.value = emptyList()
                _me.value = ""
                _busy.value = false
                _auth.value = Auth.Closed
                if (restartWanted.getAndSet(false)) {
                    pending?.let { (id, hash) -> start(id, hash) }
                }
            }
            else -> {
                _busy.value = false
                _auth.value = Auth.Closed
            }
        }
    }

    private fun sendParameters() {
        val creds = pending ?: return
        val ctx = appContext ?: return
        val root = File(ctx.filesDir, "tdlib")
        File(root, "db").mkdirs()
        File(root, "files").mkdirs()
        client?.send(
            TdApi.SetTdlibParameters(
                false,
                File(root, "db").absolutePath,
                File(root, "files").absolutePath,
                ByteArray(0),
                true,
                true,
                true,
                false,
                creds.first,
                creds.second,
                Locale.getDefault().language.ifBlank { "en" },
                Build.MANUFACTURER + " " + Build.MODEL,
                "Android " + Build.VERSION.RELEASE,
                "Hikaru " + BuildConfig.VERSION_NAME,
            ),
            Client.ResultHandler { result ->
                if (result is TdApi.Error) fail(result)
            },
            null,
        )
    }

    private suspend fun <T : TdApi.Object> query(
        function: TdApi.Function<T>,
        onError: ((TdApi.Error) -> Unit)? = null,
    ): TdApi.Object? {
        val c = client ?: return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                c.send(function) { result ->
                    if (result is TdApi.Error) {
                        onError?.invoke(result)
                        cont.resume(null)
                    } else {
                        cont.resume(result)
                    }
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    private fun errorText(e: TdApi.Error): String =
        "Telegram said ${e.code}: ${e.message.ifBlank { "refused" }}"

    private fun publishChats() {
        val me = myId
        val saved = savedChatId
        val list = chatValues.values.mapNotNull { chat ->
            val order = mainOrder[chat.id] ?: 0L
            val archive = archiveOrder[chat.id] ?: 0L
            val kind = when (val type = chat.type) {
                is TdApi.ChatTypePrivate -> {
                    if (chat.id == saved || (me != 0L && (type.userId == me || chat.id == me))) {
                        Chat.Kind.SAVED
                    } else {
                        val user = userValues[type.userId]
                        if (user == null) {
                            ensureUserLoaded(type.userId)
                        }
                        if (user?.type is TdApi.UserTypeBot) Chat.Kind.BOT else Chat.Kind.PRIVATE
                    }
                }
                is TdApi.ChatTypeSecret -> Chat.Kind.SECRET
                is TdApi.ChatTypeSupergroup ->
                    if (type.isChannel) Chat.Kind.CHANNEL else Chat.Kind.GROUP
                else -> Chat.Kind.GROUP
            }
            val membership = if (mainOrder.containsKey(chat.id) || archiveOrder.containsKey(chat.id)) {
                false
            } else {
                (chat.chatLists?.size ?: 0) > 0 && !leftBehind.contains(chat.id)
            }
            if (order == 0L && archive == 0L && !membership && kind != Chat.Kind.SAVED) {
                return@mapNotNull null
            }
            val photoId = chat.photo?.small?.id ?: chat.photo?.big?.id ?: 0
            Chat(
                id = chat.id,
                title = if (kind == Chat.Kind.SAVED) "Saved Messages" else chat.title.ifBlank { "Chat" },
                kind = kind,
                order = if (order != 0L) order else archive,
                unread = chat.unreadCount,
                archived = order == 0L && archive != 0L,
                photoFileId = photoId,
            )
        }.sortedWith(
            compareBy(
                {
                    when {
                        it.kind == Chat.Kind.SAVED -> 0
                        it.archived -> 2
                        else -> 1
                    }
                },
                { -it.order },
            ),
        )
        if (list != _chats.value) _chats.value = list
    }

    fun isSignedIn(): Boolean = _auth.value is Auth.Ready

    fun hasSavedSession(context: Context): Boolean {
        val dbDir = File(context.filesDir, "tdlib/db")
        return dbDir.exists() && (dbDir.listFiles()?.isNotEmpty() == true)
    }

    fun displayShortSide(context: Context? = appContext): Int {
        val ctx = context ?: appContext ?: return 0
        val dm = ctx.resources.displayMetrics
        return minOf(dm.widthPixels, dm.heightPixels)
    }

    suspend fun ensureReady(context: Context? = appContext, timeoutMs: Long = 12_000L): Boolean {
        if (isSignedIn()) return true
        val ctx = context ?: appContext
        if (ctx != null && client == null) {
            init(ctx)
        }
        val state = withTimeoutOrNull(timeoutMs) {
            auth.first { s ->
                when (s) {
                    is Auth.Ready,
                    is Auth.WaitPhone,
                    is Auth.WaitCode,
                    is Auth.WaitPassword,
                    is Auth.WaitRegistration,
                    is Auth.Unavailable,
                    -> true
                    else -> false
                }
            }
        }
        return state is Auth.Ready || isSignedIn()
    }

    suspend fun ensureChatLoaded(chatId: Long): Boolean {
        if (chatId == 0L || !isSignedIn()) return false
        if (chatValues.containsKey(chatId)) return true
        val chat = query(TdApi.GetChat(chatId)) as? TdApi.Chat ?: return false
        chatValues[chat.id] = chat
        absorbPositions(chat.id, chat.positions)
        return true
    }

    suspend fun publicChatId(username: String): Long? = publicChat(username)?.first

    suspend fun publicChatPhotoId(username: String): Int {
        val name = handleOf(username) ?: return 0
        val chat = query(TdApi.SearchPublicChat(name)) as? TdApi.Chat ?: return 0
        return chat.photo?.small?.id ?: chat.photo?.big?.id ?: 0
    }

    suspend fun joinedChat(username: String): Pair<Long, String>? {
        val name = handleOf(username) ?: return null
        val found = query(TdApi.SearchChats(name, null, SEARCH_CHATS_LIMIT)) as? TdApi.Chats ?: return null
        val ids = found.chatIds?.toList().orEmpty().take(SEARCH_CHATS_TAKE)
        if (ids.isEmpty()) return null
        var titleHit: Pair<Long, String>? = null
        for (id in ids) {
            val chat = query(TdApi.GetChat(id)) as? TdApi.Chat ?: continue
            val sgId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: 0L
            val handles = if (sgId != 0L) {
                val sg = query(TdApi.GetSupergroup(sgId)) as? TdApi.Supergroup
                sg?.usernames?.activeUsernames?.toList().orEmpty() +
                    sg?.usernames?.disabledUsernames?.toList().orEmpty()
            } else {
                emptyList()
            }
            if (handles.any { it.equals(name, ignoreCase = true) }) return chat.id to chat.title
            if (titleHit == null && chat.title.equals(name, ignoreCase = true)) {
                titleHit = chat.id to chat.title
            }
        }
        return titleHit
    }

    suspend fun publicChat(username: String): Pair<Long, String>? {
        val name = handleOf(username) ?: return null
        val chat = query(TdApi.SearchPublicChat(name)) as? TdApi.Chat ?: return null
        return chat.id to chat.title
    }

    private fun handleOf(username: String): String? {
        val name = username.trim()
            .removePrefix("@")
            .removePrefix("https://t.me/")
            .removePrefix("http://t.me/")
            .removePrefix("t.me/")
            .substringBefore("/")
        return name.takeIf { it.isNotBlank() }
    }

    suspend fun chatVideosPage(chatId: Long, before: Long = 0, limit: Int = 60): ChatHistoryVideos {
        val videos = LinkedHashMap<Long, ChatVideo>()
        var cursor = Long.MAX_VALUE
        var sawMessages = false
        var note: String? = null
        var indexError: String? = null
        val indexed = query(
            TdApi.SearchChatMessages(
                chatId,
                null,
                "",
                null,
                before,
                0,
                limit.coerceIn(1, SEARCH_HITS_PER_CALL),
                TdApi.SearchMessagesFilterVideo(),
            ),
        ) { indexError = errorText(it) } as? TdApi.FoundChatMessages
        if (indexed != null) {
            for (m in indexed.messages) {
                sawMessages = true
                cursor = minOf(cursor, m.id)
                videoOf(chatId, m)?.let { videos.putIfAbsent(it.messageId, it) }
            }
        } else if (indexError != null) {
            note = indexError
        }
        var historyError: String? = null
        val history = query(TdApi.GetChatHistory(chatId, before, 0, limit, false)) {
            historyError = errorText(it)
        } as? TdApi.Messages
        if (history != null) {
            for (m in history.messages) {
                sawMessages = true
                cursor = minOf(cursor, m.id)
                videoOf(chatId, m)?.let { videos.putIfAbsent(it.messageId, it) }
            }
        } else if (historyError != null) {
            note = note ?: historyError
        }
        val page = videos.values.sortedByDescending { it.messageId }
        return ChatHistoryVideos(
            videos = page,
            nextCursor = if (cursor == Long.MAX_VALUE) 0L else cursor,
            more = sawMessages,
            note = if (page.isEmpty()) note else null,
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun chatCatalogueBatch(
        chatId: Long,
        before: Long = 0L,
        targetMessages: Int = 200,
    ): ChatCatalogueBatch = coroutineScope {
        ensureChatLoaded(chatId)
        val photoDeferred = async {
            val foundImages = LinkedHashMap<Long, ChatImage>()
            val photoPages = if (before == 0L) 2 else 1
            var photoCursor = before
            for (pageIdx in 0 until photoPages) {
                val photoHits = query(
                    TdApi.SearchChatMessages(
                        chatId,
                        null,
                        "",
                        null,
                        photoCursor,
                        0,
                        SEARCH_HITS_PER_CALL,
                        TdApi.SearchMessagesFilterPhoto(),
                    ),
                ) as? TdApi.FoundChatMessages ?: break
                val batchMsgs = photoHits.messages
                if (batchMsgs.isEmpty()) break
                var oldestId = Long.MAX_VALUE
                for (m in batchMsgs) {
                    if (m.id < oldestId) oldestId = m.id
                    imageOf(chatId, m)?.let { foundImages.putIfAbsent(it.messageId, it) }
                }
                if (oldestId == Long.MAX_VALUE || (photoCursor != 0L && oldestId >= photoCursor)) break
                photoCursor = oldestId
            }
            foundImages
        }

        val videos = LinkedHashMap<Long, ChatVideo>()
        val historyImages = LinkedHashMap<Long, ChatImage>()
        var cursor = before
        var sawMessages = false
        var scanned = 0
        var note: String? = null

        while (scanned < targetMessages) {
            var historyError: String? = null
            val history = query(TdApi.GetChatHistory(chatId, cursor, 0, HISTORY_PAGE, false)) {
                historyError = errorText(it)
            } as? TdApi.Messages
            if (history == null) {
                if (historyError != null) note = note ?: historyError
                break
            }
            if (history.messages.isEmpty()) break
            var next = Long.MAX_VALUE
            for (m in history.messages) {
                scanned++
                sawMessages = true
                if (m.id < next) next = m.id
                videoOf(chatId, m)?.let { videos.putIfAbsent(it.messageId, it) }
                imageOf(chatId, m)?.let { historyImages.putIfAbsent(it.messageId, it) }
            }
            if (next == Long.MAX_VALUE || (cursor != 0L && next >= cursor)) {
                cursor = 0L
                break
            }
            cursor = next
        }

        val mergedImages = photoDeferred.await()
        historyImages.forEach { (id, img) -> mergedImages.putIfAbsent(id, img) }

        ChatCatalogueBatch(
            videos = videos.values.sortedByDescending { it.messageId },
            images = mergedImages.values.sortedByDescending { it.messageId },
            nextCursor = if (cursor == Long.MAX_VALUE) 0L else cursor,
            more = sawMessages && cursor != 0L,
            note = if (videos.isEmpty()) note else null,
        )
    }

    suspend fun chatRecentImages(chatId: Long, limit: Int = SEARCH_HITS_PER_CALL): List<ChatImage> {
        ensureChatLoaded(chatId)
        val images = LinkedHashMap<Long, ChatImage>()
        val photoHits = query(
            TdApi.SearchChatMessages(
                chatId,
                null,
                "",
                null,
                0L,
                0,
                limit.coerceIn(1, SEARCH_HITS_PER_CALL),
                TdApi.SearchMessagesFilterPhoto(),
            ),
        ) as? TdApi.FoundChatMessages
        photoHits?.messages?.forEach { m ->
            imageOf(chatId, m)?.let { images.putIfAbsent(it.messageId, it) }
        }
        val history = query(TdApi.GetChatHistory(chatId, 0L, 0, HISTORY_PAGE, false)) as? TdApi.Messages
        history?.messages?.forEach { m ->
            imageOf(chatId, m)?.let { images.putIfAbsent(it.messageId, it) }
        }
        return images.values.toList()
    }

    private fun imageOf(chatId: Long, message: TdApi.Message): ChatImage? =
        when (val c = message.content) {
            is TdApi.MessagePhoto -> {
                val best = c.photo.sizes
                    .filter { it.width >= THUMB_MIN_WIDTH }
                    .minByOrNull { it.width }
                    ?: c.photo.sizes.maxByOrNull { it.width * it.height }
                val photoFile = best?.photo
                val fileId = photoFile?.id ?: 0
                if (fileId == 0 || photoFile == null) {
                    null
                } else {
                    fileValues.putIfAbsent(fileId, photoFile)
                    ChatImage(
                        chatId = chatId,
                        messageId = message.id,
                        fileId = fileId,
                        fileName = "",
                        caption = c.caption.text,
                        minithumb = minithumb(c.photo.minithumbnail),
                    )
                }
            }
            is TdApi.MessageDocument -> {
                val doc = c.document
                val lower = doc.fileName.lowercase(Locale.US)
                val isImg = doc.mimeType.startsWith("image/", ignoreCase = true) ||
                    lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") ||
                    lower.endsWith(".webp")
                if (isImg && doc.document.id != 0) {
                    fileValues.putIfAbsent(doc.document.id, doc.document)
                    ChatImage(
                        chatId = chatId,
                        messageId = message.id,
                        fileId = doc.document.id,
                        fileName = doc.fileName,
                        caption = c.caption.text,
                        minithumb = minithumb(doc.minithumbnail),
                    )
                } else {
                    null
                }
            }
            else -> null
        }

    suspend fun postVideos(chatId: Long, messageId: Long): List<ChatVideo> {
        if (chatId == 0L || messageId == 0L) return emptyList()
        val msg = query(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message ?: return emptyList()
        val direct = videoOf(chatId, msg)
        return if (direct != null) listOf(direct) else videosOfPost(chatId, msg, "")
    }

    suspend fun linkVideo(url: String): ChatVideo? {
        val info = query(TdApi.GetMessageLinkInfo(url)) as? TdApi.MessageLinkInfo ?: return null
        val message = info.message ?: return null
        val chatId = if (info.chatId != 0L) info.chatId else message.chatId
        if (chatId == 0L) return null
        videoOf(chatId, message)?.let { return it }
        var from = message.id
        var pages = 0
        while (pages < 2) {
            pages++
            val page = query(
                TdApi.GetChatHistory(chatId, from, -(LINK_TAIL - 1), LINK_TAIL, false),
            ) as? TdApi.Messages ?: break
            val messages = page.messages
            if (messages.isEmpty()) break
            var newest = from
            var i = messages.size - 1
            while (i >= 0) {
                val m = messages[i]
                i--
                if (m.id > newest) newest = m.id
                if (m.id <= message.id) continue
                videoOf(chatId, m)?.let { return it }
            }
            if (newest <= from) break
            from = newest
        }
        return null
    }

    private fun isImageFileNameOrMime(fileName: String, mimeType: String): Boolean {
        if (mimeType.startsWith("image/", ignoreCase = true)) return true
        val lower = fileName.trim().lowercase(Locale.US)
        return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun isPlayableDocument(fileName: String, mimeType: String): Boolean {
        if (isImageFileNameOrMime(fileName, mimeType)) return false
        if (mimeType.startsWith("video/", ignoreCase = true)) return true
        val lower = fileName.trim().lowercase(Locale.US)
        val isGenericMime = mimeType.isBlank() || mimeType.equals("application/octet-stream", ignoreCase = true)
        return isGenericMime && VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun inferVideoMime(fileName: String, mimeType: String): String {
        val lower = fileName.trim().lowercase(Locale.US)
        if (mimeType.startsWith("video/", ignoreCase = true)) return mimeType
        return when {
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".ts") -> "video/mp2t"
            lower.endsWith(".flv") -> "video/x-flv"
            else -> "video/mp4"
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun videoOf(chatId: Long, message: TdApi.Message): ChatVideo? {
        return when (val c = message.content) {
            is TdApi.MessageVideo -> {
                val id = c.video.video.id
                if (id == 0 || isImageFileNameOrMime(c.video.fileName, c.video.mimeType)) return null
                verifiedVideoFiles[id] = c.video.mimeType.ifBlank { "video/mp4" }
                fileValues.putIfAbsent(id, c.video.video)
                ChatVideo(
                    chatId = chatId,
                    messageId = message.id,
                    fileId = id,
                    title = c.caption.text.ifBlank { c.video.fileName }.ifBlank { "Video" },
                    duration = c.video.duration,
                    date = message.date,
                    size = c.video.video.size,
                    thumb = minithumb(c.video.minithumbnail),
                    caption = c.caption.text,
                    fileName = c.video.fileName,
                    thumbFileId = c.video.thumbnail?.file?.id ?: 0,
                    width = c.video.width,
                    height = c.video.height,
                )
            }
            is TdApi.MessageAnimation -> {
                val id = c.animation.animation.id
                val animInvalid = id == 0 ||
                    isImageFileNameOrMime(c.animation.fileName, c.animation.mimeType) ||
                    (c.animation.duration <= 0 && c.animation.animation.size < MIN_ANIMATION_BYTES)
                if (animInvalid) return null
                verifiedVideoFiles[id] = c.animation.mimeType.ifBlank { "video/mp4" }
                fileValues.putIfAbsent(id, c.animation.animation)
                ChatVideo(
                    chatId = chatId,
                    messageId = message.id,
                    fileId = id,
                    title = c.caption.text.ifBlank { c.animation.fileName }.ifBlank { "Video" },
                    duration = c.animation.duration,
                    date = message.date,
                    size = c.animation.animation.size,
                    thumb = minithumb(c.animation.minithumbnail),
                    caption = c.caption.text,
                    fileName = c.animation.fileName,
                    thumbFileId = c.animation.thumbnail?.file?.id ?: 0,
                    width = c.animation.width,
                    height = c.animation.height,
                    isAnimation = true,
                )
            }
            is TdApi.MessageDocument -> {
                val doc = c.document
                val id = doc.document.id
                if (id == 0 || !isPlayableDocument(doc.fileName, doc.mimeType)) return null
                verifiedVideoFiles[id] = inferVideoMime(doc.fileName, doc.mimeType)
                fileValues.putIfAbsent(id, doc.document)
                ChatVideo(
                    chatId = chatId,
                    messageId = message.id,
                    fileId = id,
                    title = c.caption.text.ifBlank { doc.fileName }.ifBlank { "Video" },
                    duration = 0,
                    date = message.date,
                    size = doc.document.size,
                    thumb = minithumb(doc.minithumbnail),
                    caption = c.caption.text,
                    fileName = doc.fileName,
                    thumbFileId = doc.thumbnail?.file?.id ?: 0,
                )
            }
            else -> null
        }
    }

    @Suppress("ReturnCount")
    suspend fun searchPosterByChunks(
        chatId: Long,
        chunks: List<String>,
        imageSink: MutableMap<Long, ChatImage>? = null,
        matcher: (ChatImage) -> Boolean,
    ): ChatImage? {
        if (chatId == 0L || chunks.isEmpty() || !isSignedIn()) return null
        val capped = chunks.take(POSTER_CHUNKS_MAX)
        val firstHit = searchSinglePosterChunk(chatId, capped.first(), imageSink, matcher)
        if (firstHit != null || capped.size == 1) return firstHit

        return coroutineScope {
            capped.drop(1).map { chunk ->
                async { searchSinglePosterChunk(chatId, chunk, imageSink, matcher) }
            }.awaitAll().firstOrNull { it != null }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun searchSinglePosterChunk(
        chatId: Long,
        chunk: String,
        imageSink: MutableMap<Long, ChatImage>?,
        matcher: (ChatImage) -> Boolean,
    ): ChatImage? {
        val found = query(
            TdApi.SearchChatMessages(
                chatId,
                null,
                chunk,
                null,
                0L,
                0,
                POSTER_SEARCH_LIMIT,
                TdApi.SearchMessagesFilterPhoto(),
            ),
        ) as? TdApi.FoundChatMessages ?: return null
        for (m in found.messages) {
            val img = imageOf(chatId, m) ?: continue
            imageSink?.put(img.messageId, img)
            if (matcher(img)) return img
        }
        return null
    }

    private fun textOf(message: TdApi.Message): String = when (val c = message.content) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessageVideo -> c.caption.text
        is TdApi.MessageAnimation -> c.caption.text
        is TdApi.MessageDocument -> c.caption.text
        is TdApi.MessagePhoto -> c.caption.text
        is TdApi.MessageAudio -> c.caption.text
        else -> ""
    }

    private suspend fun videosOfPost(chatId: Long, anchor: TdApi.Message, q: String): List<ChatVideo> {
        val out = mutableListOf<ChatVideo>()
        val seen = HashSet<Long>()
        val anchorText = textOf(anchor)
        videoOf(chatId, anchor)?.let { if (seen.add(it.messageId)) out += it }
        var from = anchor.id
        var pages = 0
        var done = false
        while (!done && out.size < POST_VIDEOS_MAX && pages < TAIL_MAX_PAGES) {
            pages++
            val newer = query(
                TdApi.GetChatHistory(chatId, from, -(TAIL_PAGE - 1), TAIL_PAGE, false),
            ) as? TdApi.Messages ?: break
            val messages = newer.messages
            if (messages.isEmpty()) break
            var newest = from
            var i = messages.size - 1
            while (i >= 0) {
                val m = messages[i]
                i--
                if (m.id > newest) newest = m.id
                if (m.id <= anchor.id || !seen.add(m.id)) continue
                val video = videoOf(chatId, m)
                if (video != null) {
                    val own = video.caption
                    if (
                        own.isNotBlank() &&
                        q.isNotBlank() &&
                        !own.replace('_', ' ').contains(q.replace('_', ' '), ignoreCase = true)
                    ) {
                        done = true
                        break
                    }
                    out += if (anchorText.isBlank()) video else video.copy(caption = anchorText)
                    if (out.size >= POST_VIDEOS_MAX) {
                        done = true
                        break
                    }
                    continue
                }
                if (textOf(m).isNotBlank()) {
                    done = true
                    break
                }
            }
            if (done || newest <= from) break
            from = newest
        }
        return out
    }

    @Suppress("ReturnCount")
    suspend fun searchChatVideos(
        chatId: Long,
        text: String,
        inText: SearchIn,
        cursor: Long = 0,
        limit: Int = 60,
    ): ChatVideoPage {
        val q = text.trim()
        if (q.isEmpty()) return ChatVideoPage(emptyList(), 0, 0, true)
        if (inText == SearchIn.BOTH) {
            val byText = searchChatVideos(chatId, text, SearchIn.CHAT, cursor = 0L, limit = BOTH_TEXT_MAX)
            val byName = searchChatVideos(chatId, text, SearchIn.VIDEO, cursor, limit)
            val seen = HashSet<Long>()
            return ChatVideoPage(
                videos = (byText.videos + byName.videos).filter { seen.add(it.messageId) },
                nextCursor = byName.nextCursor,
                scanned = byName.scanned + byText.scanned,
                exhausted = byName.exhausted,
            )
        }
        if (inText == SearchIn.CHAT) {
            val found = query(
                TdApi.SearchChatMessages(
                    chatId,
                    null,
                    q,
                    null,
                    cursor,
                    0,
                    limit.coerceIn(1, SEARCH_HITS_PER_CALL),
                    TdApi.SearchMessagesFilterEmpty(),
                ),
            ) as? TdApi.FoundChatMessages ?: return ChatVideoPage(emptyList(), 0, 0, true)
            val out = mutableListOf<ChatVideo>()
            val seen = HashSet<Long>()
            for (hit in found.messages) {
                for (v in videosOfPost(chatId, hit, q)) {
                    if (seen.add(v.messageId)) out += v
                }
                if (out.size >= limit) break
            }
            val next = found.nextFromMessageId
            return ChatVideoPage(out, next, found.messages.size, next == 0L)
        }
        val out = mutableListOf<ChatVideo>()
        val seen = HashSet<Long>()
        var from = cursor
        var scanned = 0
        var more = true
        while (more && scanned < SCAN_MESSAGES_PER_CALL && out.size < limit) {
            val page = query(TdApi.GetChatHistory(chatId, from, 0, HISTORY_PAGE, false))
                as? TdApi.Messages
                ?: return ChatVideoPage(out, 0, scanned, false)
            if (page.messages.isEmpty()) {
                more = false
                break
            }
            var advanced = false
            for (m in page.messages) {
                scanned++
                if (!seen.add(m.id)) continue
                advanced = true
                val v = videoOf(chatId, m) ?: continue
                val normQ = q.replace('_', ' ')
                if (
                    v.fileName.replace('_', ' ').contains(normQ, ignoreCase = true) ||
                    v.caption.replace('_', ' ').contains(normQ, ignoreCase = true)
                ) {
                    out += v
                }
            }
            from = page.messages.last().id
            more = advanced
        }
        val exhausted = !more
        return ChatVideoPage(
            videos = out,
            nextCursor = if (exhausted) 0L else from,
            scanned = scanned,
            exhausted = exhausted,
        )
    }

    suspend fun touchMessagePhoto(chatId: Long, messageId: Long): Int {
        if (chatId == 0L || messageId == 0L) return 0
        val msg = query(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message ?: return 0
        val freshImg = imageOf(chatId, msg)
        if (freshImg != null && freshImg.fileId != 0) {
            downloadErrors.remove(freshImg.fileId)
            return freshImg.fileId
        }
        val freshVid = videoOf(chatId, msg)
        val thumbId = freshVid?.thumbFileId ?: 0
        if (thumbId != 0) downloadErrors.remove(thumbId)
        return thumbId
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    suspend fun thumbPath(
        fileId: Int,
        chatId: Long = 0L,
        messageId: Long = 0L,
        waitMs: Long = THUMB_WAIT_MS,
    ): String? {
        var activeId = fileId
        if (activeId == 0 && chatId != 0L && messageId != 0L) {
            activeId = touchMessagePhoto(chatId, messageId)
        }
        if (activeId == 0) return null
        runCatching { loadFile(activeId) }
        val deadline = System.currentTimeMillis() + waitMs
        var asked = false
        var repaired = false
        while (true) {
            val revBefore = revision.value
            val f = fileValues[activeId] ?: fetchedFiles[activeId]
            val local = f?.local
            if (local?.isDownloadingCompleted == true && local.path.isNotBlank()) {
                val diskFile = File(local.path)
                if (diskFile.exists() && diskFile.length() > 0L) return local.path
            }
            val hasErr = downloadErrors[activeId] != null || (local != null && !local.canBeDownloaded)
            val hasMsgRef = chatId != 0L && messageId != 0L
            if (hasErr && !repaired && hasMsgRef) {
                repaired = true
                downloadErrors.remove(activeId)
                val freshId = touchMessagePhoto(chatId, messageId)
                if (freshId != 0) {
                    activeId = freshId
                    asked = false
                    continue
                }
            }
            if (!asked || local?.isDownloadingActive != true) {
                request(activeId, 0L, 0L, THUMB_DOWNLOAD_PRIORITY)
                asked = true
            }
            if (System.currentTimeMillis() >= deadline) return null
            awaitUpdate(revBefore, THUMB_POLL_MS)
        }
    }

    suspend fun persistAvatar(context: Context, key: String, photoFileId: Int): String? {
        if (photoFileId == 0) return null
        val srcPath = thumbPath(photoFileId) ?: return null
        val srcFile = File(srcPath)
        if (!srcFile.exists()) return null
        val dir = File(context.filesDir, "tdlib_avatars").apply { mkdirs() }
        val dst = File(dir, "$key.jpg")
        return runCatching {
            srcFile.copyTo(dst, overwrite = true)
            dst.absolutePath
        }.getOrNull()
    }

    private fun minithumb(t: TdApi.Minithumbnail?): String? {
        val data = t?.data ?: return null
        if (data.isEmpty()) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun fileState(fileId: Int): FileState? {
        val f = fileValues[fileId] ?: fetchedFiles[fileId] ?: return null
        val local = f.local
        return FileState(
            path = local?.path.orEmpty(),
            size = maxOf(f.size, f.expectedSize),
            downloadOffset = local?.downloadOffset ?: 0L,
            readyFromOffset = local?.downloadedPrefixSize ?: 0L,
            complete = local?.isDownloadingCompleted == true,
            active = local?.isDownloadingActive == true,
            canBeDownloaded = local?.canBeDownloaded == true ||
                local?.isDownloadingActive == true ||
                local?.isDownloadingCompleted == true,
        )
    }

    suspend fun loadFile(fileId: Int): TdApi.File? {
        fileValues[fileId]?.let { return it }
        fetchedFiles[fileId]?.let { return it }
        val f = query(TdApi.GetFile(fileId)) as? TdApi.File ?: return null
        fetchedFiles[fileId] = f
        return f
    }

    suspend fun stateOf(fileId: Int): FileState? {
        loadFile(fileId)
        return fileState(fileId)
    }

    fun downloadError(fileId: Int): String? = downloadErrors[fileId]

    fun clearDownloadError(fileId: Int) {
        downloadErrors.remove(fileId)
    }

    suspend fun awaitUpdate(previousRevision: Int, timeoutMs: Long = 25L) {
        withTimeoutOrNull(timeoutMs) {
            revision.first { it != previousRevision }
        }
    }

    fun request(fileId: Int, offset: Long, limit: Long, priority: Int = DOWNLOAD_PRIORITY) {
        val c = client ?: return
        runCatching {
            c.send(
                TdApi.DownloadFile(fileId, priority, offset, limit, false),
                Client.ResultHandler { result ->
                    if (result is TdApi.Error) {
                        downloadErrors[fileId] = result.message
                        logcat(LogPriority.ERROR) {
                            "TDLib refused to download file $fileId from $offset: ${result.message}"
                        }
                    } else {
                        downloadErrors.remove(fileId)
                    }
                    _revision.value = revisionCounter.incrementAndGet()
                },
                null,
            )
        }
    }

    /**
     * Computes the adaptive chunk-count plan using Hikaru's N = S / C formula,
     * aligned strictly to Telegram's 1 MB MTProto part boundary and dividing 15 MB evenly.
     */
    fun planChunks(totalBytes: Long): ChunkPlan {
        val safeBytes = totalBytes.coerceAtLeast(1L)
        val sizeMb = safeBytes.toDouble() / ONE_MB_BYTES.toDouble()
        val chunkMb = when {
            sizeMb <= SMALL_VIDEO_MB -> SMALL_CHUNK_MB
            sizeMb <= MEDIUM_VIDEO_MB -> MEDIUM_CHUNK_MB
            else -> LARGE_CHUNK_MB
        }
        val chunkBytes = chunkMb.toLong() * ONE_MB_BYTES
        val totalChunks = ((safeBytes + chunkBytes - 1L) / chunkBytes).toInt().coerceAtLeast(1)
        return ChunkPlan(
            totalBytes = safeBytes,
            sizeMb = sizeMb,
            chunkMb = chunkMb,
            chunkBytes = chunkBytes,
            totalChunks = totalChunks,
        )
    }

    fun alignTo1MbFloor(offset: Long): Long =
        (offset.coerceAtLeast(0L) / ONE_MB_BYTES) * ONE_MB_BYTES

    fun cancelDownload(fileId: Int) {
        if (fileId == 0) return
        chunkBufferJobs.remove(fileId)?.cancel()
        runCatching {
            client?.send(TdApi.CancelDownloadFile(fileId, false), null, null)
        }
    }

    fun prewarm(fileId: Int, totalSize: Long = 0L) {
        if (fileId == 0) return
        downloadErrors.remove(fileId)
        streamedVideoFiles.forEach { otherId ->
            if (otherId != fileId &&
                (activeStreams[otherId]?.get() ?: 0) <= 0 &&
                !activeFileDownloads.contains(otherId)
            ) {
                cancelDownload(otherId)
            }
        }
        val knownSize = if (totalSize > 0L) totalSize else (fileState(fileId)?.size ?: 0L)
        if (knownSize > 0L) {
            startChunkBuffer(fileId = fileId, totalSize = knownSize, fromOffset = 0L)
        } else {
            request(fileId, 0L, CHUNK_CACHE_TARGET_BYTES)
        }
    }

    fun startChunkBuffer(fileId: Int, totalSize: Long, fromOffset: Long = 0L) {
        if (fileId == 0) return
        if (totalSize > 0L) streamFileSizes[fileId] = totalSize
        playbackCursors[fileId] = fromOffset.coerceAtLeast(0L)
        val existing = chunkBufferJobs[fileId]
        if (existing != null && existing.isActive) {
            _revision.value = revisionCounter.incrementAndGet()
            return
        }
        val job = scope.launch {
            runChunkBufferLoop(fileId)
        }
        chunkBufferJobs[fileId] = job
    }

    fun updatePlaybackCursor(fileId: Int, offset: Long, totalSize: Long = 0L) {
        if (fileId == 0) return
        if (totalSize > 0L) streamFileSizes[fileId] = totalSize
        val prev = playbackCursors.put(fileId, offset.coerceAtLeast(0L)) ?: 0L
        if (chunkBufferJobs[fileId]?.isActive != true) {
            startChunkBuffer(fileId, totalSize, offset)
        } else if (offset < prev || offset - prev >= ONE_MB_BYTES) {
            _revision.value = revisionCounter.incrementAndGet()
        }
    }

    fun beginProbe(fileId: Int) {
        if (fileId != 0) {
            pausedByProbe.computeIfAbsent(fileId) { AtomicInteger(0) }.incrementAndGet()
        }
    }

    fun endProbe(fileId: Int) {
        if (fileId == 0) return
        val left = pausedByProbe[fileId]?.decrementAndGet() ?: 0
        if (left <= 0) {
            pausedByProbe.remove(fileId)
            _revision.value = revisionCounter.incrementAndGet()
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod", "NestedBlockDepth")
    private suspend fun runChunkBufferLoop(fileId: Int) {
        val startedMs = System.currentTimeMillis()
        var lastReqOffset = -1L
        var lastReqLimit = -1L
        var lastReqAtMs = 0L
        while (true) {
            val streamActive = (activeStreams[fileId]?.get() ?: 0) > 0
            if (!streamActive && System.currentTimeMillis() - startedMs > PREWARM_MAX_IDLE_MS) {
                break
            }
            if ((pausedByProbe[fileId]?.get() ?: 0) > 0) {
                val rev = revision.value
                awaitUpdate(rev, CHUNK_POLL_MS)
                continue
            }
            val state = fileState(fileId) ?: stateOf(fileId)
            if (state?.complete == true) break
            val totalSize = (state?.size ?: 0L).takeIf { it > 0L } ?: (streamFileSizes[fileId] ?: 0L)
            if (totalSize <= 0L) {
                val rev = revision.value
                awaitUpdate(rev, CHUNK_POLL_MS)
                continue
            }
            val cursor = (playbackCursors[fileId] ?: 0L).coerceIn(0L, totalSize - 1L)
            val plan = planChunks(totalSize)
            val aheadEnd = min(totalSize, cursor + CHUNK_CACHE_TARGET_BYTES)
            val startChunk = (cursor / plan.chunkBytes).toInt().coerceIn(0, plan.totalChunks - 1)
            val endChunk = ((aheadEnd - 1L) / plan.chunkBytes).toInt().coerceIn(startChunk, plan.totalChunks - 1)

            var requestedChunk = false
            for (chunkIdx in startChunk..endChunk) {
                if ((pausedByProbe[fileId]?.get() ?: 0) > 0) break
                val chunkStart = chunkIdx.toLong() * plan.chunkBytes
                val chunkEnd = min(totalSize, chunkStart + plan.chunkBytes)
                val effectiveStart = if (cursor > chunkStart) alignTo1MbFloor(cursor) else chunkStart
                val avail = availableFrom(fileId, effectiveStart)
                val needed = chunkEnd - effectiveStart
                if (avail >= needed) continue

                val nextMiss = alignTo1MbFloor(effectiveStart + avail.coerceAtLeast(0L))
                    .coerceIn(effectiveStart, chunkEnd - 1L)
                val limit = (chunkEnd - nextMiss).coerceAtLeast(1L)
                val now = System.currentTimeMillis()
                val alreadyDownloading = state != null &&
                    state.active &&
                    state.downloadOffset in effectiveStart..nextMiss &&
                    lastReqOffset == nextMiss &&
                    lastReqLimit == limit &&
                    now - lastReqAtMs < 500L
                if (!alreadyDownloading) {
                    request(fileId, nextMiss, limit, DOWNLOAD_PRIORITY)
                    lastReqOffset = nextMiss
                    lastReqLimit = limit
                    lastReqAtMs = now
                }
                requestedChunk = true
                break
            }
            val rev = revision.value
            awaitUpdate(rev, if (requestedChunk) CHUNK_POLL_MS else CHUNK_IDLE_POLL_MS)
        }
        chunkBufferJobs.remove(fileId)
    }

    fun onStreamOpened(fileId: Int) {
        if (fileId == 0) return
        streamedVideoFiles.add(fileId)
        activeStreams.computeIfAbsent(fileId) { AtomicInteger(0) }.incrementAndGet()
        streamedVideoFiles.forEach { otherId ->
            if (otherId != fileId &&
                (activeStreams[otherId]?.get() ?: 0) <= 0 &&
                !activeFileDownloads.contains(otherId)
            ) {
                if (!isCacheMediaEnabled()) {
                    evictFile(otherId)
                } else {
                    cancelDownload(otherId)
                }
            }
        }
    }

    fun onStreamClosed(fileId: Int) {
        if (fileId == 0) return
        val remaining = activeStreams[fileId]?.decrementAndGet() ?: 0
        if (remaining <= 0) {
            chunkBufferJobs.remove(fileId)?.cancel()
            if (!isCacheMediaEnabled()) {
                scope.launch {
                    delay(STREAM_EVICT_DELAY_MS)
                    if ((activeStreams[fileId]?.get() ?: 0) <= 0 && !isCacheMediaEnabled()) {
                        evictFile(fileId)
                    }
                }
            }
        }
    }

    fun onPlaybackStopped() {
        if (isCacheMediaEnabled()) return
        val ids = streamedVideoFiles.toList()
        ids.forEach { id ->
            if (!activeFileDownloads.contains(id)) {
                activeStreams.remove(id)
                chunkBufferJobs.remove(id)?.cancel()
                playbackCursors.remove(id)
                streamFileSizes.remove(id)
                evictFile(id)
            }
        }
        appContext?.let { ctx ->
            scope.launch { purgeMediaFiles(ctx) }
        }
    }

    fun holdFileDownload(fileId: Int) {
        if (fileId == 0) return
        activeFileDownloads.add(fileId)
        chunkBufferJobs.remove(fileId)?.cancel()
    }

    fun releaseFileDownload(fileId: Int) {
        if (fileId == 0) return
        activeFileDownloads.remove(fileId)
        if (!isCacheMediaEnabled() && (activeStreams[fileId]?.get() ?: 0) <= 0) {
            evictFile(fileId)
        }
    }

    private fun evictFile(fileId: Int) {
        if (fileId == 0 || activeFileDownloads.contains(fileId)) return
        streamedVideoFiles.remove(fileId)
        playbackCursors.remove(fileId)
        streamFileSizes.remove(fileId)
        val path = (fileValues[fileId] ?: fetchedFiles[fileId])?.local?.path.orEmpty()
        cancelDownload(fileId)
        runCatching {
            client?.send(TdApi.DeleteFile(fileId), null, null)
        }
        fileValues.remove(fileId)
        fetchedFiles.remove(fileId)
        if (path.isNotBlank()) {
            runCatching { File(path).delete() }
        }
    }

    fun purgeMediaFiles(context: Context): Long {
        val filesDir = File(context.filesDir, "tdlib/files")
        if (!filesDir.exists()) return 0L
        val mediaDirs = setOf("videos", "documents", "animations", "temp", "music", "voice")
        var freed = 0L
        streamedVideoFiles.toList().forEach { evictFile(it) }
        filesDir.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name in mediaDirs) {
                freed += child.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                child.deleteRecursively()
            }
        }
        return freed
    }

    suspend fun availableFrom(fileId: Int, offset: Long): Long =
        (query(TdApi.GetFileDownloadedPrefixSize(fileId, offset)) as? TdApi.FileDownloadedPrefixSize)
            ?.size ?: -1L

    fun isVerifiedVideoFile(fileId: Int): Boolean =
        fileId != 0 && verifiedVideoFiles.containsKey(fileId)

    fun markVerifiedVideo(fileId: Int, mime: String = "video/mp4") {
        if (fileId != 0) verifiedVideoFiles.putIfAbsent(fileId, mime)
    }

    fun isImageFilePath(path: String): Boolean {
        if (path.isBlank()) return false
        val lower = path.lowercase(Locale.US)
        if (lower.contains("/photos/") || lower.contains("/thumbnails/")) return true
        return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }

    fun videoMimeFor(fileId: Int, path: String): String {
        val lower = path.lowercase(Locale.US)
        return when {
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            else -> verifiedVideoFiles[fileId]?.takeIf { it.startsWith("video/", ignoreCase = true) } ?: "video/mp4"
        }
    }

    suspend fun verifyVideoFile(fileId: Int, chatId: Long, messageId: Long): Int {
        if (isVerifiedVideoFile(fileId)) return fileId
        if (chatId != 0L && messageId != 0L) {
            return touchMessage(chatId, messageId)
        }
        return fileId
    }

    suspend fun chatVideoById(chatId: Long, messageId: Long): ChatVideo? {
        if (chatId == 0L || messageId == 0L) return null
        val msg = query(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message ?: return null
        return videoOf(chatId, msg)
    }

    suspend fun touchMessage(chatId: Long, messageId: Long): Int {
        if (chatId == 0L || messageId == 0L) return 0
        val msg = query(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message ?: return 0
        val freshVideo = videoOf(chatId, msg) ?: return 0
        val freshId = freshVideo.fileId
        if (freshId != 0) downloadErrors.remove(freshId)
        return freshId
    }

    fun cacheSizeBytes(context: Context): Long {
        val filesDir = File(context.filesDir, "tdlib/files")
        if (!filesDir.exists()) return 0L
        val mediaDirs = setOf("videos", "documents", "animations", "temp", "music", "voice")
        return filesDir.listFiles()
            ?.filter { it.isDirectory && it.name in mediaDirs }
            ?.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            ?: 0L
    }

    fun clearCache(context: Context): Long = purgeMediaFiles(context)

    fun effectiveResolution(width: Int, height: Int): Int {
        if (width <= 0 && height <= 0) return 0
        val longSide = maxOf(width, height)
        val shortSide = if (width > 0 && height > 0) minOf(width, height) else longSide
        return when {
            longSide >= 3800 || shortSide >= 2000 -> 2160
            longSide >= 2500 || shortSide >= 1400 -> 1440
            longSide >= 1880 || shortSide >= 1000 -> 1080
            longSide >= 1240 || shortSide >= 700 -> 720
            longSide >= 820 || shortSide >= 460 -> 480
            else -> 360
        }
    }

    fun resolutionTag(width: Int, height: Int): String = when (effectiveResolution(width, height)) {
        2160 -> "2160p (4K)"
        1440 -> "1440p (2K)"
        1080 -> "1080p"
        720 -> "720p"
        480 -> "480p"
        360 -> "360p"
        else -> "Auto"
    }

    @Suppress("CyclomaticComplexMethod", "LongParameterList", "ReturnCount")
    fun buildQualityVideos(
        baseUrl: String,
        width: Int = 0,
        height: Int = 0,
        headers: Headers? = null,
        preferredQuality: String = "hls",
        displayShortSide: Int = 0,
        originalOnly: Boolean = false,
    ): List<Video> {
        val sep = if (baseUrl.contains("?")) "&" else "?"
        val sourceRes = effectiveResolution(width, height)
        if (originalOnly) {
            return listOf(
                Video(
                    videoUrl = "${baseUrl}${sep}q=original",
                    videoTitle = "Original",
                    resolution = sourceRes,
                    headers = headers,
                    preferred = true,
                    initialized = true,
                ).apply { status = Video.State.READY },
            )
        }
        val pref = preferredQuality.lowercase(Locale.US).ifBlank { "hls" }
        val presets = listOf(
            2160 to "2160p (4K)",
            1440 to "1440p (2K)",
            1080 to "1080p",
            720 to "720p",
            480 to "480p",
            360 to "360p",
        )
        val availablePresets = if (sourceRes > 0) {
            presets.filter { (res, _) -> res <= sourceRes }
        } else {
            emptyList()
        }
        val targetPresetRes = pref.toIntOrNull()?.let { wanted ->
            if (availablePresets.isEmpty()) {
                null
            } else {
                availablePresets.firstOrNull { (res, _) -> res <= wanted }?.first
                    ?: availablePresets.lastOrNull()?.first
            }
        }
        val hlsSelected = pref == "hls" || (pref != "original" && targetPresetRes == null)
        val hlsRes = when {
            sourceRes > 0 && displayShortSide > 0 -> minOf(sourceRes, displayShortSide)
            sourceRes > 0 -> sourceRes
            displayShortSide > 0 -> displayShortSide
            else -> 0
        }

        val list = mutableListOf<Video>()
        list += Video(
            videoUrl = "${baseUrl}${sep}q=hls",
            videoTitle = "HLS",
            resolution = hlsRes,
            headers = headers,
            preferred = hlsSelected,
            initialized = true,
        ).apply { status = Video.State.READY }

        list += Video(
            videoUrl = "${baseUrl}${sep}q=original",
            videoTitle = "Original",
            resolution = sourceRes,
            headers = headers,
            preferred = pref == "original",
            initialized = true,
        ).apply { status = Video.State.READY }

        for ((res, label) in availablePresets) {
            list += Video(
                videoUrl = "${baseUrl}${sep}q=$res",
                videoTitle = label,
                resolution = res,
                headers = headers,
                preferred = targetPresetRes == res,
                initialized = true,
            ).apply { status = Video.State.READY }
        }
        return list
    }
}
