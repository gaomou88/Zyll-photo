package app.zyll.cloud

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.zyll.backup.AlbumRule
import app.zyll.backup.BackupCandidate
import app.zyll.backup.BackupRouter
import app.zyll.backup.BackupSettings
import app.zyll.backup.AccountLimits
import app.zyll.backup.CloudProvider
import app.zyll.backup.DriveKind
import app.zyll.backup.DriveStatus
import app.zyll.backup.Route
import app.zyll.crypto.BlobKind
import app.zyll.crypto.KeyBundle
import app.zyll.crypto.Primitives
import app.zyll.crypto.Zylc
import app.zyll.crypto.ZylcEncryptingInputStream
import app.zyll.crypto.ZylcHeader
import app.zyll.crypto.decryptStream
import app.zyll.crypto.toHex
import app.zyll.crypto.RecoveryKey
import app.zyll.media.MediaItem
import app.zyll.media.MediaRepository
import app.zyll.media.MotionPhotos
import app.zyll.motion.MotionPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import app.zyll.media.tr
import app.zyll.core.cloud.R

/** 设置页里一个网盘的样子。 */
data class DriveState(
    val provider: CloudProvider,
    /** 开发者填了客户端 ID。 */
    val configured: Boolean,
    val signedIn: Boolean,
    /** 登录过，但需要重新授权（比如存储位置改成了隐藏的应用数据区）。 */
    val needsReconnect: Boolean = false,
    val account: DriveAccount? = null,
    val error: String? = null,
    /** 超出当前限额（比如 Pro 退款后多出来的账号）：保留登录，但暂停备份。 */
    val paused: Boolean = false,
)

/** 一轮任务在做什么。 */
enum class BackupPhase { Idle, Checking, Uploading, Encrypting, Decrypting }

data class BackupStatus(
    val phase: BackupPhase = BackupPhase.Idle,
    /** 本轮要处理的数量与已完成数量。 */
    val total: Int = 0,
    val done: Int = 0,
    /** 已经有备份的照片数。 */
    val backedUp: Int = 0,
    /** 符合规则、应该备份的照片数。 */
    val eligible: Int = 0,
    /** 出问题时给用户看的话（空间不足、要重新登录等）。 */
    val message: String? = null,
    val current: String? = null,
    val lastFinishedAt: Long = 0,
    /** 上一轮新上传了几项（通知栏“备份完成”用）。 */
    val lastRunUploaded: Int = 0,
    /** 这一轮是用户手动点的（界面上要显示“检查中”）。 */
    val manual: Boolean = false,
) {
    val running: Boolean get() = phase != BackupPhase.Idle
    val pending: Int get() = (eligible - backedUp).coerceAtLeast(0)
}

/** 网盘里的一份备份（本机删掉的照片靠它在相册里继续显示、恢复）。 */
data class CloudEntry(
    val provider: CloudProvider,
    val remoteId: String,
    val encrypted: Boolean,
    val name: String,
    val album: String,
    val mime: String,
    val takenAt: Long,
    val size: Long,
    val hash: String,
    /** 备份时这张照片在本机的 MediaStore id；从网盘目录恢复的记录为负数。 */
    val mediaId: Long,
    val modified: Long,
    /** 网盘上小缩略图的文件 id；没有时为空。 */
    val thumb: String = "",
    /** 用哪套密钥加密的：空 = 当前这套；否则是以前另一套的 vaultId（还没换成当前密钥）。 */
    val keyId: String = "",
    /** 目录里标着是动态照片（mo）。 */
    val motion: Boolean = false,
) {
    val key: String get() = "${provider.name}/$remoteId"
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/** 端到端加密的状态。 */
data class EncryptionState(
    val enabled: Boolean,
    /** 正在把已有备份转换成加密 / 不加密的形式。 */
    val converting: Boolean = false,
    /** 还有多少份旧形式的备份没转换。 */
    val remaining: Int = 0,
    /** 网盘里有以前的加密备份，这台手机还没解锁（换手机、重装后）。 */
    val needsUnlock: Boolean = false,
    /** 网盘里那套加密备份和这台手机正在用的不是同一套密钥：不能直接解锁，只能放弃其中一套。 */
    val conflict: Boolean = false,
    /** 本机保存的密钥打不开了（硬件密钥失效），要用密码或恢复密钥重新解锁。 */
    val keyBroken: Boolean = false,
    /** 还有多少个旧文件等着从网盘删除（比如开启加密后要删的明文原图）。 */
    val pendingCleanup: Int = 0,
    /** 关闭加密时，这些账号还没连上：要连上并处理完它们里面的加密备份，才能彻底关闭。 */
    val waitingAccounts: List<String> = emptyList(),
    /** 和 [waitingAccounts] 一一对应的账号标识，传给 [CloudBackup.giveUpVaultAccount]（两个账号显示名一样时也分得开）。 */
    val waitingTags: List<String> = emptyList(),
    /**
     * 刚用另一套密码解开了一个账号里的旧加密备份：要用户选保留哪一套（以前的 / 现在的）。
     * 选之前那个账号先不传新照片，也不转换。
     */
    val keyChoice: Boolean = false,
    /** 等着选择时，解开的那些账号（界面上显示）。 */
    val keyChoiceAccounts: List<String> = emptyList(),
    /**
     * 别的手机最近把这些账号的加密改成了另一种：true 表示那边开了加密、这边没开，false 相反。
     * 用户选“跟着改”或“保留这台的设置”之前，这些账号先不转换。null 表示没有分歧。
     */
    val modeConflict: Boolean? = null,
    val modeConflictAccounts: List<String> = emptyList(),
    /** 有多少份备份在网盘里已经损坏（解不开或内容对不上），不会再拿来恢复或转换。 */
    val broken: Int = 0,
    /** 别的设备改了密码或换了恢复密钥，这台手机已经换上新的密钥包：提醒用户以后用新的那个。 */
    val keyChangedElsewhere: Boolean = false,
    /** [keyChangedElsewhere] 时：那边改了密码。 */
    val passwordChangedElsewhere: Boolean = false,
    /** [keyChangedElsewhere] 时：那边换了恢复密钥。 */
    val recoveryChangedElsewhere: Boolean = false,
    /**
     * 改了密码或换了恢复密钥以后，这些账号里还是旧的密钥包（还没传上去）。在那之前，那里仍然是旧的密码和恢复密钥能用、新的不能用。
     * 联网后会自动补传（不管开没开自动备份）。
     */
    val keysUnsynced: List<String> = emptyList(),
)

/** 跟着网盘账号走的其他设置（相册里的常用入口、归档、截图自动归档等），由 App 提供。 */
interface CloudSettingsExtras {
    fun export(): JSONObject
    fun import(o: JSONObject)
}

/**
 * 网盘备份的入口：连接 / 断开网盘、改备份规则、端到端加密、安排后台备份。
 * 进程内只有一份（[get]），设置页、相册界面和后台任务共用。
 */
class CloudBackup private constructor(context: Context) {
    private val app = context.applicationContext
    val auth = CloudAuth(app)
    private val settingsStore = SettingsStore(app)
    private val records = RecordStore(app)
    private val vault = Vault(app)
    private val names = app.getSharedPreferences("cloud-accounts", Context.MODE_PRIVATE)
    private val storage = StoragePrefs(app)

    // 额外登录的账号（每种网盘的第 2 个起）要在读出任何状态之前登记好
    init {
        CloudProvider.setExtra(names.getStringSet(KEY_EXTRA_ACCOUNTS, emptySet())!!.mapNotNull(CloudProvider::fromName))
    }

    private val drives = java.util.concurrent.ConcurrentHashMap<CloudProvider, CloudDrive>()

    private fun drive(p: CloudProvider): CloudDrive = drives.getOrPut(p) {
        when (p.kind) {
            DriveKind.GoogleDrive -> GoogleDriveApi(auth, app, p) { storage.space(p) }
            DriveKind.OneDrive -> OneDriveApi(auth, p)
        }
    }

    private fun forgetFolders(p: CloudProvider) {
        (drive(p) as? GoogleDriveApi)?.forgetFolders()
    }

    /**
     * 能用几个账号；App 按发行版和是否 Pro 设置，并存下来给后台任务用。
     * 新登录不能超过它；已登录但超出的账号暂停备份（见 [pausedProviders]）。
     */
    @Volatile var limits: AccountLimits = loadLimits()
        set(value) {
            if (field == value) return
            field = value
            names.edit().putInt(KEY_LIMIT_PER_KIND, value.perKind).putInt(KEY_LIMIT_TOTAL, value.total).apply()
            if (::_drives.isInitialized) refreshPaused()
        }

    private fun loadLimits(): AccountLimits {
        val perKind = names.getInt(KEY_LIMIT_PER_KIND, -1)
        val total = names.getInt(KEY_LIMIT_TOTAL, -1)
        return if (perKind > 0 && total > 0) AccountLimits(perKind, total) else AccountLimits.FREE
    }

    /** 已登录、但超出 [limits] 的账号：保留登录，暂停备份。 */
    private fun pausedProviders(): Set<CloudProvider> {
        val signedIn = signedInProviders()
        // 按用户排的顺序保留：排在前面（首选）的账号优先继续备份
        return signedIn.toSet() - limits.allowed(signedIn, _settings.value.ranked(signedIn))
    }

    private fun refreshPaused() {
        val paused = pausedProviders()
        _drives.update { list -> list.map { it.copy(paused = it.provider in paused) } }
    }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<BackupSettings> = _settings.asStateFlow()

    private lateinit var _drives: MutableStateFlow<List<DriveState>>
    val driveStates: StateFlow<List<DriveState>>

    init {
        _drives = MutableStateFlow(CloudProvider.entries.map(::initialState))
        driveStates = _drives.asStateFlow()
    }

    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val _backedUp = MutableStateFlow<Set<Long>>(emptySet())
    /** 已经有备份的照片（MediaStore id）。 */
    val backedUpIds: StateFlow<Set<Long>> = _backedUp.asStateFlow()

    private val _encryption = MutableStateFlow(EncryptionState(vault.wantEncrypted))

    private val _indexIssues = MutableStateFlow<Map<CloudProvider, IndexIssue>>(emptyMap())
    /** 哪些账号的备份目录有问题：损坏了，或者比这台手机上次看到的还旧（网盘被“还原”过）。 */
    val indexIssues: StateFlow<Map<CloudProvider, IndexIssue>> = _indexIssues.asStateFlow()

    /** 相册那边的设置；App 启动时设置。 */
    @Volatile var extras: CloudSettingsExtras? = null

    /** AI 整理结果的导出 / 导入；App 启动时设置。没设置（比如只有后台任务在跑）时不同步。 */
    @Volatile var aiResults: AiResultsSync? = null

    private val _remoteSettings = MutableStateFlow(names.getString(KEY_REMOTE_SETTINGS, null) != null)
    /** 在网盘里找到了这个账号以前的设置，可以一键恢复。 */
    val remoteSettingsAvailable: StateFlow<Boolean> = _remoteSettings.asStateFlow()
    val encryption: StateFlow<EncryptionState> = _encryption.asStateFlow()

    private val _entries = MutableStateFlow<List<CloudEntry>>(emptyList())
    /** 网盘里的全部备份。 */
    val entries: StateFlow<List<CloudEntry>> = _entries.asStateFlow()

    private val _usage = MutableStateFlow<Map<CloudProvider, Long>>(emptyMap())
    /** Zyll 在每个网盘里占用的空间（按备份记录估算）。 */
    val usage: StateFlow<Map<CloudProvider, Long>> = _usage.asStateFlow()

    private val thumbDir = File(app.filesDir, "cloud-thumbs")
    private val viewDir = File(app.cacheDir, "cloud-view")
    private val downloadLocks = HashMap<String, Mutex>()

    init {
        migrateOldKeys()
        migrateVaultAccounts()
        refreshUnlockState()
        refreshIndexIssues()
        recoverKeySwap()
        // 上次改了密码或换了恢复密钥、还有账号没传上：接着补传
        // 密钥包没补传完、或换密钥后旧密钥还等着收尾：启动时排上（只要有网就跑，不受“仅 Wi‑Fi / 仅充电”限制）
        if (keysUnsynced().isNotEmpty() || vault.oldKeyIds().isNotEmpty()) runCatching { scheduleKeySync() }
        scope.launch { reloadRecords() }
    }

    /** 这台手机（这次安装）的随机标识，写进备份目录，用来分辨哪些文件是别的手机传的。 */
    private val deviceId: String by lazy {
        names.getString(KEY_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString().also { names.edit().putString(KEY_DEVICE_ID, it).apply() }
    }

    /** 账号标识：网盘类型 + 账号名。加密相关的标记按账号记，换账号不会串。 */
    private fun accountTag(p: CloudProvider) =
        p.name + "|" + (names.getString(KEY_ACCOUNT_ID + p.name, null) ?: names.getString(p.name, null)).orEmpty()

    /** 界面上显示的账号名。 */
    private fun tagLabel(t: String) = names.getString(KEY_ACCOUNT_LABEL + t, null) ?: t.substringAfter('|').ifEmpty { t.substringBefore('|') }

    private fun vaultAccounts(): Set<String> = names.getStringSet(KEY_VAULT_ACCOUNTS, emptySet())!!

    private fun addVaultAccount(p: CloudProvider) {
        names.edit().putStringSet(KEY_VAULT_ACCOUNTS, vaultAccounts() + accountTag(p)).putBoolean(KEY_VAULT_UPLOADED_PREFIX + p.name, true).apply()
    }

    /**
     * 旧版本的键：待解锁的密钥包不分网盘（remoteBundle）、放弃标记是全局的、墓碑存在 prefs 里。
     * 统一清掉，并让每个网盘重新检查一次密钥包（处在“待解锁”的用户升级后照样会看到解锁提示）。
     */
    private fun migrateOldKeys() {
        val old = listOf("remoteBundle", "remoteVaultAbandoned", "vaultRetirePending", "remoteTombstones")
        if (old.none { names.contains(it) }) return
        val edit = names.edit()
        old.forEach { edit.remove(it) }
        CloudProvider.entries.forEach { edit.remove(KEY_REMOTE_CHECKED + it.name) }
        edit.commit()
    }

    /** 旧版本只按网盘类型记“密钥包已上传”，补记成账号。 */
    private fun migrateVaultAccounts() {
        if (vault.bundleJson == null || names.contains(KEY_VAULT_ACCOUNTS)) return
        val tags = CloudProvider.entries.filter { names.getBoolean(KEY_VAULT_UPLOADED_PREFIX + it.name, false) }.map { accountTag(it) }.toSet()
        names.edit().putStringSet(KEY_VAULT_ACCOUNTS, tags).apply()
    }

    /** 换账号（登录、退出、重新连接）时，清掉这个网盘按账号记的状态，下次重新检查。 */
    private fun resetAccount(p: CloudProvider) {
        names.edit()
            .remove(KEY_INDEX_CHECKED + p.name).remove(KEY_INDEX_ID + p.name).remove(KEY_INDEX_LOCKED + p.name)
            .remove(KEY_INDEX_SEQ + p.name).remove(KEY_INDEX_PUSHED + p.name).remove(KEY_REMOTE_CHECKED + p.name).remove(KEY_VAULT_UPLOADED_PREFIX + p.name)
            .remove(KEY_REMOTE_BUNDLE + p.name).remove(KEY_REMOTE_ABANDONED + p.name).remove(KEY_SETTINGS_DECIDED)
            .remove(KEY_AI_MODEL + p.name).remove(KEY_AI_AT + p.name).remove(KEY_AI_VERSION + p.name)
            .remove(KEY_REKEY_ACCOUNT + p.name).remove(KEY_BUNDLE_CHECKED_AT + p.name).remove(KEY_VAULT_RECHECK_AT + p.name).remove(KEY_ISSUE_RETRY_AT + p.name)
            .apply()
        wipeCheckedAt.remove(p)
        refreshUnlockState()
        refreshWipeHeld()
    }

    /** 网盘里找到、还没解锁的密钥包（按网盘）。 */
    private fun remoteBundles(): Map<CloudProvider, String> =
        CloudProvider.entries.mapNotNull { p -> names.getString(KEY_REMOTE_BUNDLE + p.name, null)?.let { p to it } }.toMap()

    private fun refreshUnlockState() {
        val pending = remoteBundles().isNotEmpty()
        val hasLocal = vault.bundleJson != null
        val broken = hasLocal && runCatching { vault.wrapKey() }.exceptionOrNull() is VaultBrokenException
        val choice = pendingKeyChoice()
        val choiceAccounts = if (choice == null) emptyList() else
            CloudProvider.entries.filter { names.getString(KEY_REKEY_ACCOUNT + it.name, null) == choice }
                .map { p -> accountLabel(p) }
        _encryption.update {
            it.copy(
                needsUnlock = pending, conflict = pending && hasLocal, keyBroken = broken, keyChoice = choice != null, keyChoiceAccounts = choiceAccounts,
                keyChangedElsewhere = hasLocal && (names.getBoolean(KEY_BUNDLE_CHANGED, false) || names.getBoolean(KEY_BUNDLE_CHANGED_RK, false)),
                passwordChangedElsewhere = hasLocal && names.getBoolean(KEY_BUNDLE_CHANGED, false),
                recoveryChangedElsewhere = hasLocal && names.getBoolean(KEY_BUNDLE_CHANGED_RK, false),
                keysUnsynced = if (hasLocal) keysUnsynced().map { accountLabel(it) } else emptyList(),
            )
        }
    }

    /** 这个网盘可以上传了：已经确认过网盘里有没有以前的加密备份，也读回了以前的备份目录。 */
    private fun isReady(p: CloudProvider) =
        names.getBoolean(KEY_REMOTE_CHECKED + p.name, false) && names.getBoolean(KEY_INDEX_CHECKED + p.name, false) &&
            // 这个账号的备份在别的设备上全部删除过：用户在这台手机上重新开始之前不往里写（见 [wipedElsewhere]）
            !wipeHeld(p) &&
            !names.contains(KEY_REMOTE_BUNDLE + p.name) &&
            // 网盘目录损坏或被还原过、还没处理：整个账号暂停（不然新备份记不进网盘目录，换手机时找不回）
            !_indexIssues.value.containsKey(p) &&
            // 刚解开另一套密钥、还在等用户选保留哪套：这个账号先不动
            (pendingKeyChoice() == null || names.getString(KEY_REKEY_ACCOUNT + p.name, null) != pendingKeyChoice())

    /** 这个账号在等用户处理密钥：里面是另一套还没解开的密钥，或者解开了、还在等选保留哪套。 */
    private fun waitingForKey(p: CloudProvider): Boolean =
        names.contains(KEY_REMOTE_BUNDLE + p.name) ||
            (pendingKeyChoice() != null && names.getString(KEY_REKEY_ACCOUNT + p.name, null) == pendingKeyChoice())

    /** 改记录、改加密状态的操作都要拿这把锁；正在备份时请它这个文件传完就让出来。 */
    private suspend fun <T> exclusive(block: suspend () -> T): T {
        urgent = true
        return lock.withLock { block() }
    }

    private fun initialState(p: CloudProvider) =
        DriveState(p, CloudConfig.of(p).configured, auth.isSignedIn(p), auth.needsReconnect(p), paused = p in pausedProviders())

    /** “Google Drive 2 (someone@gmail.com)”，括号按界面语言。 */
    fun accountLabel(p: CloudProvider): String =
        names.getString(p.name, null)?.let { tr(R.string.account_with_name, p.label, it) } ?: p.label

    /** 已连接网盘的简短描述，给系统照片选择器显示。没有连接时返回 null。 */
    fun accountSummary(): String? = CloudProvider.entries
        .filter { auth.isSignedIn(it) }
        .joinToString(tr(R.string.list_separator)) { p -> accountLabel(p) }
        .ifEmpty { null }

    /**
     * 系统照片选择器里的网盘照片是否锁着（Play 版没解锁 Pro）。锁着时选择器拿不到任何照片。
     * 由 App（看到购买状态时）和选择器接口（每次被调用时）设置；变了就通知选择器重新同步。
     */
    /**
     * 选择器接口每次调用都会设这个值。变了也不能在那次调用里同步通知系统媒体服务（两边可能互相等），
     * 所以丢到后台，等 1 秒、把连续几次变化合并成一次再通知。
     */
    @Volatile var pickerLocked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyPickerLater()
        }

    private var pickerNotifyJob: Job? = null

    private val pickerNotifyLock = Any()

    private fun notifyPickerLater() = synchronized(pickerNotifyLock) {
        pickerNotifyJob?.cancel()
        pickerNotifyJob = scope.launch {
            delay(1_000)
            notifyPicker()
        }
    }

    /** 账号变了、锁定状态变了都要换一个集合 id，系统照片选择器才会重新同步（解锁前看到的空列表不会留着）。 */
    fun pickerCollectionId(): String =
        "zyll-" + (accountSummary() ?: "none").hashCode().toUInt().toString(16) + if (pickerLocked) "-locked" else ""

    /** 通知系统照片选择器：账号或内容变了（Zyll 不是当前云端媒体应用时系统会拒绝，忽略即可）。 */
    fun notifyPicker() {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            MediaStore.notifyCloudMediaChangedEvent(app.contentResolver, "${app.packageName}.cloudpicker", pickerCollectionId())
        }
    }

    // ---------- 相簿：哪些要备份 ----------

    /** 记下新出现的相簿；“新相簿自动备份”关着时把它们设为不备份。 */
    fun registerAlbums(bucketIds: Collection<Long>) {
        val s = _settings.value
        val fresh = bucketIds.filter { it !in s.knownAlbums }
        if (fresh.isEmpty()) return
        val rules = if (s.backupNewAlbums || s.knownAlbums.isEmpty()) s.albumRules
        else s.albumRules + fresh.filter { it !in s.albumRules }.associateWith { AlbumRule.Skip }
        val next = s.copy(knownAlbums = s.knownAlbums + fresh, albumRules = rules)
        settingsStore.save(next)
        _settings.value = next
    }

    /** 单个相簿是否备份（相册页标题旁的开关、备份设置里的相簿列表用的是同一份规则）。 */
    fun setAlbumSynced(bucketId: Long, synced: Boolean) = update { s ->
        val rules = if (synced) {
            if (s.albumRules[bucketId] == AlbumRule.Skip) s.albumRules - bucketId else s.albumRules
        } else {
            s.albumRules + (bucketId to AlbumRule.Skip)
        }
        s.copy(albumRules = rules)
    }

    /** “全部备份”：打开时清掉所有“不备份”；关掉时现有相簿都先设为不备份，再按相簿单独打开。 */
    fun setBackupAll(on: Boolean, allBuckets: Collection<Long>) = update { s ->
        if (on) s.copy(albumRules = s.albumRules.filterValues { it != AlbumRule.Skip })
        else s.copy(albumRules = s.albumRules + allBuckets.filter { it !in s.albumRules }.associateWith { AlbumRule.Skip })
    }

    /** 系统没有限制 Zyll 的后台耗电（省电优化会让后台备份被推迟、被中途打断）。 */
    fun isBackgroundUnrestricted(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true

    /** 弹出系统对话框，让用户允许 Zyll 不受电池优化限制。 */
    fun backgroundUnrestrictIntent(): Intent =
        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${app.packageName}"))

    fun update(transform: (BackupSettings) -> BackupSettings) {
        val before = _settings.value
        val s = transform(before)
        // 正在跑的这一轮是按旧设置开始的（网络、充电条件、哪些相簿）：传完手上这张就停，按新设置重新排
        if (s.copy(knownAlbums = before.knownAlbums) != before && _status.value.running) {
            requeue = true
            urgent = true
        }
        // 重新打开自动备份：在别的设备上删除过的账号也从头开始备份
        if (!before.enabled && s.enabled && wipeHeldTags().isNotEmpty()) {
            names.edit().remove(KEY_WIPE_HOLD).remove(KEY_WIPED_NOTICE).apply()
            refreshWipeHeld()
        }
        settingsStore.save(s)
        _settings.value = s
        schedule()
        // 按新设置马上排一轮，条件（仅 Wi‑Fi、仅充电）也按新的：不当作“立即备份”，不然刚打开“仅充电”就开始传，看起来像没生效
        if (s.enabled) {
            if (_status.value.running) {
                backupNow()
            } else {
                requeue = true
                requeueIfNeeded(fromWorker = false)
            }
        }
    }

    /** 开了分享功能（或者库已经在可见文件夹里）的 Google 账号，登录时一并申请 drive.file。 */
    fun signInIntent(p: CloudProvider): Intent = auth.signInIntent(
        p,
        if (p.kind == DriveKind.GoogleDrive && (storage.wantVisible || storage.space(p) == DriveSpace.Visible)) listOf(CloudConfig.GOOGLE_FILE_SCOPE) else emptyList(),
        // 重新连接：预先选中原来的账号
        loginHint = if (auth.needsReconnect(p)) names.getString(p.name, null) else null,
    )

    /** 已连接网盘的排序（第一个是首选）。界面上拖动或点上移 / 下移后调用。 */
    fun setDriveOrder(order: List<CloudProvider>) {
        update { it.copy(order = order.distinct()) }
        // 超出限额时哪个账号暂停跟着顺序变
        refreshPaused()
    }

    /** 把 [p] 在已连接网盘里往前（[up]）或往后挪一位。 */
    fun moveDrive(p: CloudProvider, up: Boolean) {
        // 需要重新连接的账号也留在列表里原来的位置
        val list = _settings.value.ranked(CloudProvider.entries.filter { auth.isSignedIn(it) || auth.needsReconnect(it) }).toMutableList()
        val i = list.indexOf(p)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        setDriveOrder(list)
    }

    private fun signedInProviders() = CloudProvider.entries.filter { auth.isSignedIn(it) }

    /** [p] 现在能不能点“连接”：已登录的、需要重新连接的总是可以；新登录要看 [limits]。 */
    fun canSignIn(p: CloudProvider): Boolean =
        auth.isSignedIn(p) || auth.needsReconnect(p) || limits.canAdd(p.kind, signedInProviders())

    /**
     * 再登录一个 [kind] 的账号用哪个槽位：先用还没登录的已有槽位，没有就开一个新的。
     * 超过 [limits] 或槽位用完时返回 null。新槽位在登录成功后才会保存。
     */
    fun newAccountSlot(kind: DriveKind): CloudProvider? {
        if (!limits.canAdd(kind, signedInProviders())) return null
        return CloudProvider.entries.firstOrNull { it.kind == kind && !auth.isSignedIn(it) && !auth.needsReconnect(it) }
            ?: CloudProvider.nextFree(kind)
    }

    private fun saveExtraAccounts() {
        names.edit().putStringSet(KEY_EXTRA_ACCOUNTS, CloudProvider.entries.filter { it.slot > 0 }.map { it.name }.toSet()).apply()
    }

    private fun addSlot(p: CloudProvider) {
        if (p in CloudProvider.entries) return
        CloudProvider.register(p)
        saveExtraAccounts()
        _drives.update { list -> (list + initialState(p)).sortedBy { it.provider } }
    }

    /** 去掉一个额外账号的槽位（第一个槽位一直保留）。 */
    private fun removeSlot(p: CloudProvider) {
        if (p.slot == 0) return
        CloudProvider.unregister(p)
        saveExtraAccounts()
        drives.remove(p)
        _drives.update { list -> list.filter { it.provider != p } }
    }

    private val _accountNotice = MutableStateFlow<String?>(null)
    /** 登录时的提示，例如“这个账号已经登录过了”。界面显示后调用 [clearAccountNotice]。 */
    val accountNotice: StateFlow<String?> = _accountNotice.asStateFlow()

    fun clearAccountNotice() { _accountNotice.value = null }

    suspend fun completeSignIn(p: CloudProvider, data: Intent?) {
        addSlot(p)
        val wasReconnect = auth.needsReconnect(p)
        // 已经登录着又授权一次（比如为分享功能多授权一项）：和重新连接一样，必须还是原来那个账号
        val wasSignedIn = auth.isSignedIn(p)
        val previousAuth = if (wasReconnect || wasSignedIn) auth.savedState(p) else null
        val previousId = if (wasReconnect || wasSignedIn) names.getString(KEY_ACCOUNT_ID + p.name, null) else null
        val previousLabel = names.getString(p.name, null)
        try {
            // 新令牌先不保存：用它确认登的是哪个账号，对了再存。核对期间正在跑的备份用的还是原来账号的令牌，
            // 不会把东西传进选错的账号
            val pending = auth.exchange(p, data)
            // 先确认登的是哪个账号：同一个账号不能占两个槽位，否则会往同一个网盘目录里互相覆盖。
            // 拿不到账号 id（比如网络不好）就不算登录成功，免得跳过这一步检查。
            val account = try {
                val token = pending.accessToken ?: throw IOException(tr(R.string.auth_token_failed))
                retrying { drive(p).accountWith(token) }
            } catch (e: IOException) {
                null
            }
            val id = account?.id
            val problem = when {
                id == null -> tr(R.string.account_unverified)
                // 授权时在 Google 的选择页里选了另一个账号：不接受，免得这个槽位悄悄换成别的账号（记录、密钥都对不上）
                previousId != null && id != previousId ->
                    tr(R.string.account_wrong_reauth, previousLabel ?: p.label, account?.name ?: "")
                // 旧格式没存 id 的：退一步按账号名比
                previousId == null && (wasSignedIn || wasReconnect) && previousLabel != null && account?.name != null &&
                    !account.name.equals(previousLabel, ignoreCase = true) ->
                    tr(R.string.account_wrong_reauth, previousLabel, account.name)
                duplicateOf(p, id) != null -> tr(R.string.account_already_signed_in)
                // 在授权页取消了主权限：这份令牌不能用，不保存
                !auth.pendingHasMainScope(p, pending) -> tr(R.string.auth_main_scope_missing)
                else -> null
            }
            if (problem != null) {
                if (wasSignedIn) {
                    // 原来登录得好好的：新令牌没保存，什么都不用放回（放回旧令牌反而可能盖掉后台刚刷新的）
                } else if (wasReconnect) {
                    // 重新连接时登错了账号或没联网：保留这个槽位原来的账号和记录，仍然是“需要重新连接”
                    auth.restoreState(p, previousAuth)
                    setDrive(p) { initialState(p).copy(error = problem) }
                } else {
                    // 这里不在锁里：可能正有一轮备份在往这个账号传，拿到锁再清，免得清完又被写回记录
                    exclusive { signOutLocked(p) }
                }
                _accountNotice.value = problem
                return
            }
            auth.commit(p, pending)
            if (wasReconnect) {
                // 存储位置变了，旧位置的记录作废，重新备份到新位置
                records.clear(p)
                forgetFolders(p)
                reloadRecords()
            }
            refreshAccount(p, locked = false)
            refreshPaused()
            // 同一个账号再授权一次（比如为分享功能多授权一项）：不是换账号，按账号记的标记（放弃过的旧备份、换密钥过渡期、
            // 防回滚序号等）都要留着。只有新登录、重新连接才当作新账号重新检查
            if (!wasSignedIn) resetAccount(p)
            // 登录后的检查做完之前，开关分享、搬存放位置都先不做（这期间账号的位置和加密状态还没确定）
            _accountChecks.update { it + p }
            refreshStorage()
            scope.launch {
                try {
                    lock.withLock {
                        // 库可能已经被别的手机或网页版放到了可见文件夹：先跟过去，再在那里找密钥包和备份目录
                        diag("completeSignIn ${p.name}: post-login checks start wasSignedIn=$wasSignedIn")
                        val before = storage.space(p)
                        runCatching { followIfMovedElsewhere(p) }
                        // 新登录、重新连接：换了手机或重装后，先看有没有以前的加密密钥包，再从备份目录找回以前备份过的照片。
                        // 同一个账号再授权：只有真的跟到了新位置（标记已在那里重置）才在新位置重新找
                        if (!wasSignedIn || storage.space(p) != before) {
                            runCatching { checkRemoteVault(p) }
                            runCatching { restoreIndex(p, vault.wrapKey()) }
                        }
                        diag("completeSignIn ${p.name}: post-login checks done space=${storage.space(p)}")
                    }
                } finally {
                    _accountChecks.update { it - p }
                    refreshStorage()
                }
                reloadRecords()
                // 开着分享功能时新登录的账号、为分享功能重新授权后：马上切到可见文件夹
                switchPending()
            }
            notifyPicker()
            schedule()
            refreshStorage()
            if (_settings.value.enabled) backupNow(manual = true)
        } catch (e: IOException) {
            if (p.slot > 0 && !auth.isSignedIn(p)) {
                removeSlot(p)
                _accountNotice.value = e.message
            } else if (wasSignedIn) {
                // 已登录的账号再授权没成功（取消、没联网）：账号本身还好好的，不在账号行上挂错误；原因交给界面
                _lastAuthError.value = e.message
            } else {
                setDrive(p) { it.copy(error = e.message) }
            }
        } finally {
            // 不管成没成功（包括用户在授权页按了取消）都通知界面：授权状态可能变了，要重新看
            _authEvents.update { it + 1 }
        }
    }

    private val _accountChecks = MutableStateFlow<Set<CloudProvider>>(emptySet())
    /** 刚登录 / 刚授权、后台还在检查（跟随存放位置、查密钥包、找回目录）的账号。这期间不开关分享、不搬存放位置。 */
    val accountChecks: StateFlow<Set<CloudProvider>> = _accountChecks.asStateFlow()

    private val _lastAuthError = MutableStateFlow<String?>(null)
    /** 已登录账号再授权失败时的原因（界面读完后清掉）。 */
    val lastAuthError: StateFlow<String?> = _lastAuthError.asStateFlow()
    fun clearLastAuthError() { _lastAuthError.value = null }

    /**
     * 现在为什么不能开关分享（给用户看的话）；null 表示可以。账号刚登录还在检查、加密的库还没解锁时都不行：
     * 这时还不知道库在哪、是不是加密的，判断会出错，没解锁也没法把目录写到新位置。
     */
    fun sharingToggleBlocker(): String? {
        if (_accountChecks.value.isNotEmpty()) return tr(R.string.sharing_wait_checking)
        val e = _encryption.value
        if (e.needsUnlock || e.conflict || e.keyChoice || e.keyBroken) return tr(R.string.sharing_unlock_first)
        // 还没就绪的账号算“正在检查”；暂停的（超出账号数，备份不碰它，不会有人重试）、缺授权等着强制授权框处理的不算
        val paused = pausedProviders()
        val needScope = accountsNeedingVisibleScope()
        if (googleAccounts().any { auth.isSignedIn(it) && it !in paused && it !in needScope && !isReady(it) && !_indexIssues.value.containsKey(it) && !wipeHeld(it) }) {
            return tr(R.string.sharing_wait_checking)
        }
        return null
    }

    private val _authEvents = MutableStateFlow(0)
    /** 每次登录 / 授权流程结束加一（成功、失败、取消都算）。界面据此重新判断授权状态。 */
    val authEvents: StateFlow<Int> = _authEvents.asStateFlow()

    /** 诊断日志：只记账号槽位、存放位置和结果，不记账号名、文件名或照片内容。 */
    private fun diag(msg: String) { android.util.Log.i("ZyllDiag", msg) }

    /** 已经有别的槽位登录了 [id] 这个账号时，返回那个槽位。 */
    private fun duplicateOf(p: CloudProvider, id: String): CloudProvider? = CloudProvider.entries.firstOrNull {
        it != p && it.kind == p.kind && auth.isSignedIn(it) && names.getString(KEY_ACCOUNT_ID + it.name, null) == id
    }

    /**
     * 断开时会从相册里消失的照片：只备份在这个账号里、手机上也没有的（别的账号里还有同一张的不算）。
     * 界面在确认框里告诉用户。
     */
    fun cloudOnlyCount(p: CloudProvider): Int {
        val all = records.entries()
        val elsewhere = all.filter { it.provider != p }.mapTo(HashSet()) { it.hash.ifEmpty { it.provider.name + it.remoteId } }
        val local = all.filter { it.mediaId > 0 }.mapTo(HashSet()) { it.hash.ifEmpty { it.provider.name + it.remoteId } }
        return all.filter { it.provider == p && it.mediaId <= 0 }.map { it.hash.ifEmpty { it.provider.name + it.remoteId } }
            .distinct().count { it !in elsewhere && it !in local }
    }

    /** 要花几秒的账号操作：断开、重建备份目录。 */
    enum class AccountOp { SigningOut, Rebuilding }

    private val _accountOps = MutableStateFlow<Map<CloudProvider, AccountOp>>(emptyMap())
    /** 正在进行的账号操作，界面据此显示“正在断开…”等，并禁用相关按钮。 */
    val accountOps: StateFlow<Map<CloudProvider, AccountOp>> = _accountOps.asStateFlow()

    private val _rebuildFailed = MutableStateFlow<Set<CloudProvider>>(emptySet())
    /** 上次“以这台手机为准重建”没成功的账号（多半是网络问题），界面提示后可以再试。 */
    val rebuildFailed: StateFlow<Set<CloudProvider>> = _rebuildFailed.asStateFlow()

    /**
     * 在 CloudBackup 自己的作用域里跑，不跟着页面走：用户点完就离开备份页，操作也会做完。
     * 同一个账号同时只跑一个。
     */
    private fun runAccountOp(p: CloudProvider, op: AccountOp, block: suspend () -> Unit) {
        var started = false
        _accountOps.update { started = p !in it; if (started) it + (p to op) else it }
        if (!started) return
        scope.launch {
            try {
                block()
            } finally {
                _accountOps.update { it - p }
            }
        }
    }

    /**
     * 界面用：断开账号（见 [signOut]），在后台做完。
     * [resetSettings]：断开的是最后一个账号、用户选了不保留设置时，备份设置（自动备份、仅 Wi‑Fi、视频、相簿规则等）恢复默认。
     * 默认保留，以后重新连接网盘时照旧备份。
     * [forgetKey]：同时清掉这台手机上的加密密钥（见 [signOutForgettingKey]），条件不满足时保留密钥并提示。
     */
    fun startSignOut(p: CloudProvider, resetSettings: Boolean = false, forgetKey: Boolean = false) = runAccountOp(p, AccountOp.SigningOut) {
        // keptBecause：null = 清了；非空 = 保留了，内容是原因
        val keptBecause: String? = if (forgetKey) signOutForgettingKey(p) else { signOut(p); null }
        if (resetSettings) update { cur -> BackupSettings(knownAlbums = cur.knownAlbums) }
        if (forgetKey) {
            if (keptBecause == null) {
                // 清了密钥：自动备份先关掉。否则以后连上一个空网盘会直接按不加密传，用户原来是加密的
                update { it.copy(enabled = false) }
                _accountNotice.value = tr(R.string.signout_key_forgotten)
            } else {
                _accountNotice.value = tr(R.string.signout_key_kept_because, keptBecause)
            }
        }
    }

    /** 这台手机上有没有加密密钥（断开最后一个账号时，界面据此决定要不要给“清掉密钥”这一项）。 */
    val hasLocalKey: Boolean get() = vault.bundleJson != null

    /**
     * 断开 [p]（最后一个账号）时能不能顺便清掉这台手机上的加密密钥（只看本机，不联网；界面用来决定这一项能不能勾）。
     * 清掉以后只能靠 [p] 里的密钥包 + 密码找回，所以要求：[p] 还登录着、记着已传过本机这套密钥包、没有待补传；
     * 以前存过这套密钥的只有 [p]（别的账号里的是断开时的旧版，清了以后只能靠旧密码）；没有在换密钥、
     * 没有暂存的另一套旧密钥、没有等着选保留哪套、没有“别的设备改了密码”还没看；新的恢复密钥已确认抄下；
     * 不在删除全部数据；共享相册没有没推上去的改动；外链没有做到一半的事、还有效的都在 [p] 里；没有别的账号连着。
     * 真正清之前还要联网核对 [p] 里的密钥包（见 [signOutForgettingKey]）。
     */
    fun canForgetKeyOnSignOut(p: CloudProvider): Boolean = forgetKeyUnavailableReason(p) == null

    /**
     * 断开最后一个账号时为什么不能清密钥（给用户看的一句话）；null 表示可以勾（真正清之前还会联网核对）。
     * 关键一条：以前存过这套密钥的账号，断开时都记下了当时网盘里核对过的密钥包版本（见 [syncBundleBeforeSignOut]）；
     * 只要有一个账号记下的版本不是现在这版（断开后又改过密码或换过恢复密钥），清掉后那里的照片就只能靠旧密码，所以不让清。
     */
    fun forgetKeyUnavailableReason(p: CloudProvider): String? {
        val why = forgetKeyBlocker(p) ?: return null
        // 只记是哪一条没满足（不记账号名），方便排查
        android.util.Log.i("ZyllBackup", "forget key unavailable: ${why.first}")
        return why.second
    }

    private fun localBundleRev(): Long? = vault.bundleJson?.let { runCatching { KeyBundle.revOf(KeyBundle.parse(it)) }.getOrNull() }

    /** 记在 [KEY_VAULT_REV] 里的值：哪一套密钥（vaultId）的第几版。换了一套密钥，旧记录自然对不上。 */
    private fun localBundleStamp(): String? {
        val id = vault.vaultId ?: return null
        val rev = localBundleRev() ?: return null
        return "$id:$rev"
    }

    /** 没满足的那一条：（日志用的代号，给用户看的话）。 */
    /** [final]：断开前同步过密钥包之后的最终判断，这时要求本账号确实记着已存好这套密钥；打开确认框时的预判不要求（断开时会先同步）。 */
    private fun forgetKeyBlocker(p: CloudProvider, final: Boolean = false): Pair<String, String>? {
        val busy = tr(R.string.forget_key_busy)
        if (vault.bundleJson == null) return "no-local-key" to busy
        if (!auth.isSignedIn(p)) return "not-signed-in" to tr(R.string.forget_key_reconnect)
        if (CloudProvider.entries.any { it != p && auth.isSignedIn(it) }) return "other-account" to busy
        val e = _encryption.value
        if (e.keyBroken || e.needsUnlock || e.conflict || e.keyChoice || e.keyChangedElsewhere) return "needs-attention" to tr(R.string.forget_key_attention)
        if (_pendingRecoveryKey.value != null) return "recovery-key-not-confirmed" to tr(R.string.forget_key_recovery)
        val tag = accountTag(p)
        val accounts = vaultAccounts()
        if (final && (tag !in accounts || !names.getBoolean(KEY_VAULT_UPLOADED_PREFIX + p.name, false))) return "bundle-not-in-this-account" to busy
        val stamp = localBundleStamp() ?: return "bad-local-bundle" to busy
        val stale = (accounts - tag).filter { names.getString(KEY_VAULT_REV + it, null) != stamp }
        if (stale.isNotEmpty()) return "older-bundle-elsewhere(${stale.size})" to tr(R.string.forget_key_stale, stale.joinToString(tr(R.string.list_separator)) { tagLabel(it) })
        if (names.getStringSet(KEY_BUNDLE_PENDING, emptySet())!!.isNotEmpty()) return "bundle-pending" to busy
        if (rekeyPending(p)) return "rekey-pending" to busy
        if (pendingKeyChoice() != null || names.contains(KEY_SWAP) || vault.oldKeyIds().isNotEmpty()) return "key-swap" to tr(R.string.forget_key_swap)
        if (_wipe.value.running || wipeUnfinished) return "wipe" to busy
        if (e.converting) return "converting" to busy
        if (sharedAlbums.hasUnpushedChanges()) return "shared-albums-unpushed" to busy
        if (shareLinks.hasUnfinishedWork() || !shareLinks.allActiveOn(p)) return "share-links" to busy
        return null
    }

    /**
     * 断开 [p] 并清掉这台手机上的加密密钥（用户在断开最后一个账号时勾了这一项）。整段拿着锁做，判断和清除之间状态不会变。
     * 只有本机条件都满足（[canForgetKeyOnSignOut]）、[p] 里的密钥包和本机这份逐字一致且校验通过、外链列表写回网盘成功时才清；
     * 否则照常断开、保留密钥。返回是否清了。
     */
    /** 断开 [p] 并尽量清掉本机密钥。返回 null 表示清了；否则保留了密钥，返回给用户看的原因（日志里记代号）。 */
    private suspend fun signOutForgettingKey(p: CloudProvider): String? = exclusive {
        // 先只看本机条件；这个账号里的密钥包在断开前同步、核对（[syncBundleBeforeSignOut]），之后再做最终判断
        var why: Pair<String, String>? = forgetKeyBlocker(p)
        val key = runCatching { vault.wrapKey() }.getOrNull()
        // 和 [signOut] 一样：断开前把还没传的备份目录传一次。传的过程中目录版本可能又变了（合并了网盘上的改动），
        // 没对上就再传一次
        repeat(2) {
            if (auth.isSignedIn(p) && !indexSynced(p) && isReady(p) && !wipeUnfinished) {
                runCatching { withTimeout(20_000) { uploadIndex(setOf(p), vault.wantEncrypted, key, force = true) } }
            }
        }
        // 传目录要一会儿：这期间可能建了新的外链或改了共享相册，再看一遍；目录也要确实传上去了（清密钥不可逆）。
        // 外链列表放到最后写回：写回之后到清掉之间不再有别的联网等待
        if (!wipeUnfinished) syncBundleBeforeSignOut(p)
        if (why == null) why = forgetKeyBlocker(p, final = true)
        if (why == null && !indexSynced(p)) why = "index-not-synced" to tr(R.string.forget_key_index)
        // 最后两步联网整体最多等 60 秒：网很慢时不一直占着锁，超时就保留密钥
        if (why == null) {
            var timedOut = true
            val r = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                val x: Pair<String, String>? = when {
                    !remoteBundleMatches(p) -> "bundle-mismatch" to tr(R.string.forget_key_bundle)
                    !shareLinks.flushListFor(p) -> "links-not-flushed" to tr(R.string.forget_key_links)
                    else -> null
                }
                timedOut = false
                x
            }
            why = if (timedOut) "online-timeout" to tr(R.string.forget_key_bundle) else r
        }
        signOutLocked(p)
        if (why == null && (CloudProvider.entries.any { auth.isSignedIn(it) } || shareLinks.hasUnfinishedWork())) why = "changed-meanwhile" to tr(R.string.forget_key_busy)
        if (why == null) {
            forgetLocalKeyData()
            null
        } else {
            android.util.Log.i("ZyllBackup", "forget key at sign-out skipped: ${why.first}")
            why.second
        }
    }

    /**
     * 断开任何一个存过这套密钥的账号之前：把本机最新的密钥包同步进去，再联网核对一遍。核对通过就记下这个账号里是第几版，
     * 以后断开最后一个账号、要清本机密钥时，靠这个确认以前断开的账号里也能用现在的密码解开（见 [forgetKeyBlocker]）。
     * 没网、核对不上就不记（以后清密钥时会因此不让清）；断开照常进行。调用方拿着锁。
     */
    private suspend fun syncBundleBeforeSignOut(p: CloudProvider) {
        val tag = accountTag(p)
        // 先清掉旧记录：下面任何一步没成功，都不能留着以前核对过的结果
        names.edit().remove(KEY_VAULT_REV + tag).apply()
        // 开着加密时，每个连着的账号本来就该存一份密钥包（备份时也会传）；还没传上的这里补传
        if (vault.bundleJson == null || !auth.isSignedIn(p) || (tag !in vaultAccounts() && !vault.wantEncrypted)) return
        val ok = try {
            withTimeout(20_000) {
                // 和 [syncKeyBundle] 一样：账号还没核对完、或存储位置正在搬时不往里写密钥包，只看现有的对不对
                if (!remoteBundleMatches(p) && isReady(p) && storageUsable(p) == StorageUse.Ok) {
                    // 强制重新核对、需要时覆盖上去（平时一天才看一次）
                    names.edit().remove(KEY_BUNDLE_CHECKED_AT + p.name).apply()
                    uploadKeyBundle(setOf(p))
                }
                remoteBundleMatches(p)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ZyllBackup", "keybundle sync before sign-out failed", e)
            false
        }
        val stamp = localBundleStamp()
        if (ok && stamp != null) names.edit().putString(KEY_VAULT_REV + tag, stamp).apply()
    }

    /** [p] 里的密钥包和本机这份逐字一致（同一套密钥、同一个版本），并且用本机的密钥校验通过。读不到、对不上都返回 false。 */
    private suspend fun remoteBundleMatches(p: CloudProvider): Boolean = try {
        val local = vault.bundleJson
        val drive = drive(p)
        val id = retrying { drive.findFile(KEYBUNDLE_PATH) }
        if (local == null || id == null) false else {
            val out = java.io.ByteArrayOutputStream()
            retrying { out.reset(); drive.download(id, out) }
            val text = String(out.toByteArray(), Charsets.UTF_8)
            val remote = KeyBundle.parse(text)
            remote.vaultId == vault.vaultId && KeyBundle.revOf(remote) == KeyBundle.revOf(KeyBundle.parse(local)) &&
                // 0.1.3 以前的密钥包没有 mac：和本机这份逐字一致、同一套密钥时也算对上（内容就是本机这份）
                (remote.mac == null || vault.wrapKey()?.let { KeyBundle.verify(remote, it) } == true) && text.trim() == local.trim()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("ZyllBackup", "keybundle check before forgetting key failed", e)
        false
    }

    /**
     * 清掉这台手机上的加密密钥和跟它绑在一起的本机数据（共享相册、外链列表、看过的解密副本），回到和新装一样。
     * 网盘上的密钥包和备份都不动：重新登录后会锁住那个账号，提示用密码或恢复密钥解锁。调用方拿着锁。
     */
    private fun forgetLocalKeyData() {
        forgetLocalBackupData()
        viewDir.listFiles()?.forEach { it.delete() }
        MotionPhotos.clearCache(app, MotionPhotos.cloudPrefix(null))
        _encryption.update { it.copy(enabled = false, converting = false, remaining = 0, waitingAccounts = emptyList(), waitingTags = emptyList()) }
    }

    /** 界面用：以这台手机为准重建备份目录（见 [rebuildIndex]），在后台做完；失败时记进 [rebuildFailed]。 */
    fun startRebuildIndex(p: CloudProvider) = runAccountOp(p, AccountOp.Rebuilding) {
        _rebuildFailed.update { it - p }
        try {
            rebuildIndex(p)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ZyllBackup", "rebuild index failed", e)
            _rebuildFailed.update { it + p }
        }
    }

    /**
     * 断开账号。先等正在跑的备份停下，再把还没传上去的备份目录传一次（最多等 20 秒），
     * 这样别的手机、以后重新连接时都能看到这台手机最近备份和删除的照片。
     */
    suspend fun signOut(p: CloudProvider) {
        exclusive {
            if (auth.isSignedIn(p) && !indexSynced(p) && isReady(p) && !wipeUnfinished) {
                runCatching {
                    withTimeout(20_000) { uploadIndex(setOf(p), vault.wantEncrypted, runCatching { vault.wrapKey() }.getOrNull(), force = true) }
                }
            }
            if (!wipeUnfinished) syncBundleBeforeSignOut(p)
            signOutLocked(p)
        }
    }

    /** 这个账号的缩略图和看过的原图副本（可能是解密后的）删掉；别的账号也有的同一张保留。 */
    private fun clearAccountCaches(p: CloudProvider) {
        val mine = records.entries().filter { it.provider == p }
        val keep = records.entries().filter { it.provider != p }.mapTo(HashSet()) { thumbKeyOf(it.provider, it.remoteId, it.hash) }
        mine.map { thumbKeyOf(it.provider, it.remoteId, it.hash) }.filter { it !in keep }.forEach { File(thumbDir, "$it.jpg").delete() }
        viewDir.listFiles()?.filter { it.name.startsWith(p.name + "-") }?.forEach { it.delete() }
        // 从这个账号的照片里切出来的动态视频（加密照片的是明文）
        MotionPhotos.clearCache(app, MotionPhotos.cloudPrefix(p.name))
    }

    /** 这个账号的备份记录和按账号记的标记清掉（登录状态和存储位置不动），下次当作新账号重新检查。 */
    private fun clearAccountState(p: CloudProvider) {
        markNeedsVisible(p, false)
        records.dropMeta("mig:${p.name}:")
        quotaFull.remove(p)
        records.clear(p)
        records.clearAccountTables(p)
        resetAccount(p)
        names.edit()
            .remove(KEY_PULLED_AT + p.name).remove(KEY_RECONCILED_AT + p.name).remove(KEY_INDEX_CORRUPT + p.name)
            .remove(KEY_INDEX_ROLLBACK + p.name).remove(KEY_INDEX_IGNORE + p.name).remove(KEY_REMOTE_MODE + p.name).remove(KEY_INDEX_OVERRIDE + p.name).apply()
        names.edit().putStringSet(KEY_BROKEN, brokenSet().filterNot { it.startsWith(p.name + "/") }.toSet()).apply()
        refreshIndexIssues()
        forgetFolders(p)
    }

    private fun signOutLocked(p: CloudProvider) {
        clearAccountCaches(p)
        auth.signOut(p)
        storage.clear(p)
        // 换账号后旧记录不再成立，下次连接时重新检查
        clearAccountState(p)
        names.edit().remove(p.name).remove(KEY_ACCOUNT_ID + p.name).apply()
        // 排序和相簿规则里也去掉：以后别的账号用这个槽位时不继承它的位置和规则
        val cur = _settings.value
        var reset = 0
        val rules = cur.albumRules.mapNotNull { (bucket, rule) ->
            if (rule is AlbumRule.To && p in rule.providers) {
                val left = rule.providers - p
                // 只剩不下任何账号：改回“跟随备份方式”
                if (left.isEmpty()) { reset++; null } else bucket to AlbumRule.To(left)
            } else bucket to rule
        }.toMap()
        if (p in cur.order || rules != cur.albumRules) {
            val next = cur.copy(order = cur.order - p, albumRules = rules)
            settingsStore.save(next)
            _settings.value = next
        }
        if (reset > 0) _accountNotice.value = tr(R.string.album_rules_reset, reset)
        setDrive(p) { initialState(p) }
        removeSlot(p)
        refreshPaused()
        notifyPicker()
        scope.launch { reloadRecords() }
        schedule()
        // 存放位置状态（比如“缺授权”的账号列表）也跟着更新，不然界面还会把断开的账号当成要授权
        refreshStorage()
    }

    suspend fun refreshAccounts() = CloudProvider.entries.forEach { refreshAccount(it, locked = false) }

    private val accountCheckedAt = java.util.concurrent.ConcurrentHashMap<CloudProvider, Long>()

    /** 30 分钟内查过网盘空间就直接用上次的结果，不用每轮都联网。 */
    private fun cachedAccount(p: CloudProvider): DriveAccount? {
        val at = accountCheckedAt[p] ?: return null
        if (android.os.SystemClock.elapsedRealtime() - at > ACCOUNT_TTL_MS || !auth.isSignedIn(p)) return null
        return _drives.value.firstOrNull { it.provider == p }?.account
    }

    /** [locked]：调用方已经拿着 [lock]（备份主循环里）；否则停用重复账号时要先拿锁。 */
    private suspend fun refreshAccount(p: CloudProvider, locked: Boolean): DriveAccount? {
        if (!auth.isSignedIn(p) || !CloudConfig.of(p).configured) {
            setDrive(p) { initialState(p) }
            return null
        }
        return try {
            retrying {
                try {
                    drive(p).account()
                } catch (e: HttpException) {
                    if (e.code == 401) auth.invalidate(p)
                    throw e
                }
            }.also { a ->
                // 以前的版本在没联网时可能让同一个账号占了两个槽位：发现后停用后加的那个
                val dup = a.id?.let { duplicateOf(p, it) }
                if (dup != null) {
                    val drop = if (p.slot >= dup.slot) p else dup
                    if (locked) signOutLocked(drop) else exclusive { signOutLocked(drop) }
                    _accountNotice.value = tr(R.string.account_already_signed_in)
                    if (drop == p) return null
                }
                rememberAccount(p, a)
                accountCheckedAt[p] = android.os.SystemClock.elapsedRealtime()
                setDrive(p) { it.copy(signedIn = true, needsReconnect = false, account = a, error = null) }
            }
        } catch (e: NotSignedInException) {
            setDrive(p) { it.copy(signedIn = false, needsReconnect = true, account = null, error = e.message) }
            null
        } catch (e: IOException) {
            // 网络一时不好：保留上次的账号信息，不当成断开
            setDrive(p) { it.copy(error = if (it.account == null) tr(R.string.cloud_network_retry) else null) }
            null
        }
    }

    /**
     * 记下账号名和稳定 id。以前没存 id（按账号名记的旧格式）时，把加密标记换成按 id 记；
     * 存着的 id 和这次的不一样，说明换了一个账号（比如“重新连接”时登了别的账号）：按换账号处理，清掉旧账号的记录和标记。
     */
    private fun rememberAccount(p: CloudProvider, a: DriveAccount) {
        val prevId = names.getString(KEY_ACCOUNT_ID + p.name, null)
        if (prevId != null && a.id != null && a.id != prevId) {
            records.clear(p)
            records.clearAccountTables(p)
            forgetFolders(p)
            resetAccount(p)
            // 存放位置和搬迁进度都是旧账号的：清掉，免得拿旧账号的进度去搬新账号、删错文件。新账号重新判断放在哪
            storage.clear(p)
            records.dropMeta(migPrefix(p))
            refreshStorage()
            scope.launch { reloadRecords() }
        }
        val oldTag = accountTag(p)
        val edit = names.edit()
        a.name?.let { edit.putString(p.name, it) }
        a.id?.let { edit.putString(KEY_ACCOUNT_ID + p.name, it) }
        edit.commit()
        val newTag = accountTag(p)
        val tags = vaultAccounts()
        val e2 = names.edit()
        if (prevId == null && newTag != oldTag && oldTag in tags) e2.putStringSet(KEY_VAULT_ACCOUNTS, tags - oldTag + newTag)
        a.name?.let { e2.putString(KEY_ACCOUNT_LABEL + newTag, it) }
        e2.apply()
    }

    private fun setDrive(p: CloudProvider, f: (DriveState) -> DriveState) =
        _drives.update { list -> list.map { if (it.provider == p) f(it) else it } }

    @Volatile private var pickerSig = Long.MIN_VALUE

    private suspend fun reloadRecords() = withContext(Dispatchers.IO) {
        val all = records.entries()
        _backedUp.value = all.mapTo(HashSet()) { it.mediaId }
        _entries.value = all.map {
            CloudEntry(it.provider, it.remoteId, it.encrypted, it.name, it.album, it.mime, it.takenAt, it.size, it.hash, it.mediaId, it.modified, it.thumb, it.keyId, it.motion)
        }
        // 网盘内容真的变了才让系统照片选择器重新同步
        val sig = all.fold(all.size.toLong()) { acc, r -> acc * 31 + (r.provider.name + r.remoteId + r.name + r.takenAt).hashCode() }
        // 系统照片选择器用的缓存跟着换掉；手机上还有哪些照片在后台重新查一次（不在选择器的调用里查，见 [pickerItems]）
        pickerCache = null
        if (localIdsCache == null || android.os.SystemClock.elapsedRealtime() - localIdsAt > 5_000) refreshLocalIds()
        if (sig != pickerSig) {
            pickerSig = sig
            notifyPicker()
        }
        _usage.value = all.distinctBy { it.provider to it.remoteId }.groupBy { it.provider }.mapValues { (_, v) -> v.sumOf { it.size } }
        val want = vault.wantEncrypted
        val cleanup = records.pendingDeleteCount()
        val stale = staleRecords(all, want).size
        val bad = brokenSet()
        val broken = all.distinctBy { it.provider to it.remoteId }.count { brokenKey(it.provider, it.remoteId) in bad }
        _encryption.update { it.copy(enabled = want, remaining = stale, pendingCleanup = cleanup, broken = broken) }
        refreshModeConflict()
    }

    // ---------- 分享功能的存储位置（Google：隐藏区 ↔ 可见文件夹，见 spec/sharing.md 第 7 节） ----------

    // 下面的 init 里 refreshStorage() 就会读这两个，必须声明在它前面（Kotlin 按书写顺序初始化）
    /** 别的手机挂着“正在搬”、但超过 [MIGRATION_STALE_MS] 没有进展的账号。 */
    private val staleElsewhere: MutableSet<CloudProvider> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 这台手机在搬、但读不到旧位置备份目录的账号（搬不下去，要用户处理）。 */
    private val stuckMoving: MutableSet<CloudProvider> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val _storage = MutableStateFlow(StorageState())
    val storageState: StateFlow<StorageState> = _storage.asStateFlow()
    private val storageLock = Any()

    init {
        refreshStorage()
    }

    private fun googleAccounts() = CloudProvider.entries.filter { it.kind == DriveKind.GoogleDrive && auth.isSignedIn(it) }

    private fun targetSpace() = if (storage.wantVisible) DriveSpace.Visible else DriveSpace.Hidden

    private fun refreshStorage() {
        val google = googleAccounts()
        val target = targetSpace()
        _storage.update {
            val moving = google.filter { p -> storage.moving(p) != null }.toSet()
            it.copy(
                wantVisible = storage.wantVisible,
                moving = moving,
                // 旧位置只剩密钥包这些小文件、没有照片要搬（全新或清空过的网盘）：后台顺手清掉，界面上不算“待搬”
                pending = google.filter { p -> storage.moving(p) == null && (storage.space(p) != target || (storage.cleanup(p) != null && storage.leftover(p) > 0)) }.toSet(),
                // 只算库确实在可见文件夹、这台手机却还没授权读它的账号（库还在隐藏区的账号不因整机开着分享被逼授权）
                needsScope = accountsNeedingVisibleScope().toSet(),
                staleElsewhere = staleElsewhere.toSet(),
                stuck = stuckMoving.toSet(),
                done = if (moving.isEmpty()) 0 else it.done,
                total = if (moving.isEmpty()) 0 else it.total,
                leftover = google.filter { p -> storage.cleanup(p) != null }.sumOf { p -> storage.leftover(p) },
            )
        }
    }

    /**
     * 打开 / 关闭分享功能（= 库放在可见文件夹 / 隐藏区）。夜里已经开始搬旧备份时不能改，返回 false。
     * 其余情况马上切换（见 [switchLocked]）：新照片直接传到新位置，旧位置剩下的备份等夜里再搬（剩得少就马上搬）。
     */
    fun setSharingWanted(on: Boolean): Boolean {
        diag("setSharingWanted on=$on spaces=${googleAccounts().joinToString { it.name + ":" + storage.space(it) }} wantVisible=${storage.wantVisible}")
        // 自己建的共享相册、还有效的外链还在：搬回隐藏区后就打不开了，要先结束共享、撤销外链
        // （界面先用 [ownedSharedAlbums]、[activeGoogleShareLinks] 提示）
        if (!on && (ownedSharedAlbums() > 0 || activeGoogleShareLinks() > 0)) return false
        // 有 Google 账号还没授权读写可见文件夹：开、关都搬不了（搬回隐藏区也要先读可见文件夹里的备份），先授权
        if (googleAccountsLackingSharingScope(turningOn = on).isNotEmpty()) return false
        // 账号还在检查、还没解锁：先不改（见 [sharingToggleBlocker]）
        if (sharingToggleBlocker() != null) { diag("setSharingWanted on=$on refused: busy"); return false }
        synchronized(storageLock) {
            refreshStorage()
            if (_storage.value.locked) return false
            storage.wantVisible = on
        }
        refreshStorage()
        scope.launch { switchPending() }
        return true
    }

    /**
     * 强制授权框要处理的账号：库确实在可见文件夹里（已经在那里，或者标记说搬过去了但这台手机还没授权跟过去），
     * 这台手机却没有读写它的授权。库还在隐藏区的账号不算（不会因为整机开着分享就被逼着授权）。
     */
    fun accountsNeedingVisibleScope(): List<CloudProvider> = googleAccounts().filter { p ->
        auth.isSignedIn(p) && !auth.hasScope(p, CloudConfig.GOOGLE_FILE_SCOPE) &&
            (storage.space(p) == DriveSpace.Visible || storage.moving(p) != null || names.getBoolean(KEY_NEEDS_VISIBLE + p.name, false))
    }

    /** 这个账号现在是否登录着（界面判断要不要提示授权用）。 */
    fun isSignedIn(p: CloudProvider): Boolean = auth.isSignedIn(p)

    /**
     * 开关分享前要先授权 drive.file（读写可见文件夹）的 Google 账号。
     * 开启（[turningOn]）：所有还没授权的都要，不然搬不过去。
     * 关闭、撤销：只拦确实有东西在可见文件夹里的（库已经在可见区、正在搬、或者可见区还有没搬完 / 没清理的），
     * 一直留在隐藏区的账号不用读可见文件夹，不拦。
     */
    fun googleAccountsLackingSharingScope(turningOn: Boolean): List<CloudProvider> =
        googleAccounts().filter { p ->
            auth.isSignedIn(p) && !auth.hasScope(p, CloudConfig.GOOGLE_FILE_SCOPE) && (
                turningOn || storage.space(p) == DriveSpace.Visible || storage.moving(p) != null ||
                    storage.cleanup(p) == DriveSpace.Visible || (storage.leftover(p) > 0 && storage.space(p) != DriveSpace.Hidden)
                )
        }

    /** 这个账号的存放位置和用户的选择不一致、而且现在能切换（没在搬、不缺授权、没在删除全部数据）。 */
    private fun needsSwitch(p: CloudProvider): Boolean {
        val target = targetSpace()
        return p.kind == DriveKind.GoogleDrive && auth.isSignedIn(p) && storage.moving(p) == null && storage.space(p) != target &&
            (target == DriveSpace.Hidden || auth.hasScope(p, CloudConfig.GOOGLE_FILE_SCOPE)) &&
            !_wipe.value.running && !wipeUnfinished && !wipeHeld(p) &&
            // 账号检查完、加密的库已经解锁才搬：没解锁就只能往新位置写空的或不全的目录
            p !in _accountChecks.value && isReady(p) &&
            // 自动搬回隐藏区（比如别的设备关了分享）：这个账号里还有自己建的共享相册、还有效的外链时不搬，不然它们会失效
            !(target == DriveSpace.Hidden && (sharedAlbums.albums.value.any { it.provider == p && it.myRole == null } || shareLinks.activeOn(p) > 0))
    }

    /** 不在 [lock] 里的地方用：把需要切换的账号切过去，再按旧位置剩下多少排搬迁。失败（没联网等）下一轮备份时再试。 */
    private suspend fun switchPending() {
        if (googleAccounts().any(::needsSwitch)) {
            exclusive { switchAllLocked() }
            // 让出锁时可能打断了一轮备份：接着跑
            if (_settings.value.enabled) backupNow()
        }
        refreshStorage()
        scheduleMigration()
    }

    /** 要在 [lock] 里调用。 */
    private suspend fun switchAllLocked() {
        diag("switchAllLocked: target=${targetSpace()} spaces=${googleAccounts().joinToString { it.name + ":" + storage.space(it) }}")
        for (p in googleAccounts()) {
            if (!needsSwitch(p)) continue
            try {
                switchLocked(p)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ZyllStorage", "switch failed for $p", e)
            }
        }
        refreshStorage()
    }

    /**
     * 按各账号旧位置剩下多少安排搬迁：剩得少（少于 [StorageMigrationWorker.SMALL_LIMIT] 张）的连上 Wi‑Fi 就搬，
     * 其余等夜里。夜里已经开始、中途停下的，接着等夜里。
     */
    fun scheduleMigration(retryLater: Boolean = false, fromWorker: MigrationKind? = null) {
        val left = googleAccounts().filter { storage.cleanup(it) != null || storage.moving(it) != null }
        // 已经排着（或正在跑）的不替换：替换会打断正在搬的那个。只有任务自己收尾时才替换自己那一种
        val small = left.any { isSmallMove(it) }
        val big = left.any { !isSmallMove(it) }
        when {
            small -> StorageMigrationWorker.scheduleSmall(app, retryLater, replace = fromWorker == MigrationKind.Small)
            fromWorker == null -> StorageMigrationWorker.cancelSmall(app)
        }
        when {
            big -> StorageMigrationWorker.schedule(app, replace = fromWorker == MigrationKind.Night)
            fromWorker == null -> StorageMigrationWorker.cancel(app)
        }
    }

    enum class MigrationKind { Small, Night }

    /** 新流程里切换后剩得少的账号（旧版本留下的“复制到一半”不算，照旧等夜里）。 */
    private fun isSmallMove(p: CloudProvider): Boolean =
        storage.cleanup(p) != null && storage.leftover(p) < StorageMigrationWorker.SMALL_LIMIT

    /**
     * 马上把这个账号切到用户选的位置（要在 [lock] 里调用）：
     * 1. 旧位置的目录合进本机记录（别的设备、网页版最近写进去的照片也带上）；
     * 2. 固定名字的小文件（密钥包、身份、共享相册列表、AI 整理结果……）复制过去，新位置已有更新的就不覆盖；
     * 3. 改用新位置，在新位置写目录。目录里旧位置的条目照样能用：Google 按文件 id 读写，和文件在哪个位置无关；
     * 4. 新位置的旧标记删掉，旧位置挂上“已搬走”，别的设备和网页版跟着去新位置。
     * 旧位置剩下的照片记在 [StoragePrefs.cleanup]，夜里（剩得少就马上）由 [cleanupOldSpace] 搬过去。
     * 读写失败时抛 IOException，本机状态不变，下次再试。
     */
    private suspend fun switchLocked(p: CloudProvider) {
        val from = storage.space(p)
        val to = targetSpace()
        if (from == to || storage.moving(p) != null) return
        val src = GoogleDriveApi(auth, app, p) { from }
        val dst = GoogleDriveApi(auth, app, p) { to }
        val marker = readMarker(src)
        if (marker != null && marker.device != deviceId) {
            when (marker.state) {
                // 别的设备（旧版本）正在搬：等它搬完，除非用户选了不再等
                StorageMarker.MIGRATING -> if (storage.ignoredMarker(p) != marker.at) return
                // 别的设备已经把库搬到了用户想要的那边：跟过去就行
                StorageMarker.MOVED -> if (marker.to == to && storage.cleanup(p) == null) {
                    followMoved(p, marker)
                    return
                }
            }
        }
        val key = runCatching { vault.wrapKey() }.getOrNull()
        if (vault.wantEncrypted && key == null) throw IOException("no key")
        // 上次切换后还没搬的旧文件（搬到一半是锁着的，这里只可能是还没开始搬）：这次是切回那边，那些文件就地不动。
        // 失败时要原样还原，不然那边剩下的文件就没人管了
        val prevCleanup = storage.cleanup(p)
        val prevLeftover = storage.leftover(p)
        val prevSwitchedAt = storage.switchedAt(p)
        // 上次带过去的固定文件映射（那边 id → 这边 id）这次用不上了，留着会和这次的混在一起
        if (prevCleanup != null) records.dropMeta(migPrefix(p))
        // 1. 这时 drive(p) 还是旧位置
        mergePulled(p, pullIndex(p, key))
        // 2. 固定名字的小文件
        val srcTree = retrying { src.listTree() }.filter { (path, _) -> path != StorageMarker.PATH && !isSharedPath(path) }
        carryFixedFiles(p, src, dst, srcTree)
        // 旧位置剩下要搬的：除了固定文件和目录以外的全部文件；照片张数给界面显示
        val rest = srcTree.filter { (path, _) -> !isFixedName(path) && !isLiveIndex(path) }
        val photos = rest.count { (path, f) -> isPhotoFile(f) && path.firstOrNull() != THUMB_FOLDER }
        // 3. 改用新位置。目录用新位置的（有的话先合并进来：比如这台手机以前搬过去、后来重新连接过）
        val switchedAt = System.currentTimeMillis()
        synchronized(storageLock) {
            storage.setSpace(p, to)
            storage.setSwitchedAt(p, switchedAt)
            // 旧位置什么都没有（全新的网盘）：不用清理
            storage.setCleanup(p, if (srcTree.isEmpty()) null else from)
            storage.setLeftover(p, photos)
        }
        // 新位置的目录序号可能比旧位置的小（不是被还原）：这一次不当作回滚
        names.edit().remove(KEY_INDEX_ID + p.name).remove(KEY_INDEX_PUSHED + p.name).putBoolean(KEY_INDEX_OVERRIDE + p.name, true).apply()
        markIndexDirty()
        var ok = false
        try {
            retrying { dst.findFile(StorageMarker.PATH) }?.let { id -> retrying { dst.delete(id) } }
            if (key != null || !vault.wantEncrypted) uploadIndex(setOf(p), vault.wantEncrypted, key, force = true)
            ok = names.getLong(KEY_INDEX_PUSHED + p.name, -1) == names.getLong(KEY_INDEX_VERSION, 0)
            if (!ok) throw IOException("index upload failed")
            // 4. 新位置的目录写好了才挂标记：前面失败时别的设备不会被指到一个空位置
            putMarker(src, StorageMarker.Marker(StorageMarker.MOVED, to, System.currentTimeMillis(), deviceId))
        } finally {
            if (!ok) {
                // 回到切换前：目录还在旧位置，下次再试
                synchronized(storageLock) {
                    storage.setSpace(p, from)
                    storage.setSwitchedAt(p, prevSwitchedAt)
                    storage.setCleanup(p, prevCleanup)
                    storage.setLeftover(p, prevLeftover)
                }
                // 映射丢了不要紧：清理时没映射的固定文件会重新带一次
                records.dropMeta(migPrefix(p))
                names.edit().remove(KEY_INDEX_ID + p.name).remove(KEY_INDEX_PUSHED + p.name).remove(KEY_INDEX_OVERRIDE + p.name).apply()
                markIndexDirty()
            }
        }
        reloadRecords()
        notifyPicker()
        refreshStorage()
    }

    /**
     * 把旧位置固定名字的小文件（密钥包、身份、共享相册列表、AI 整理结果、已清空标记）带到新位置，并记进 `mig:` 映射
     * （夜里清理时不再当作普通文件复制）。新位置已有一份更新的就不覆盖；密钥包只在两份是同一个库时才比新旧。
     */
    private suspend fun carryFixedFiles(p: CloudProvider, src: CloudDrive, dst: GoogleDriveApi, srcTree: List<Pair<List<String>, RemoteFile>>) {
        val fixed = srcTree.filter { (path, _) -> path != StorageMarker.PATH && isFixedName(path) }
        if (fixed.isEmpty()) return
        val dstTree = retrying { dst.listTree() }.toMap()
        val tmp = File(app.cacheDir, "storage-move").apply { mkdirs() }
        for ((path, f) in fixed) {
            val there = dstTree[path]
            val keep = there != null && (there.modifiedAt >= f.modifiedAt || (path == KEYBUNDLE_PATH && !sameVaultBundle(src, f.id, dst, there.id)))
            val newId = if (keep) there!!.id else {
                val file = File(tmp, "fixed-${p.name}")
                try {
                    retrying { file.outputStream().use { src.download(f.id, it) } }
                    val up = UploadFile(path, "application/octet-stream", file.length(), System.currentTimeMillis(), replace = true) { file.inputStream().buffered() }
                    retrying { dst.upload(up) }
                } finally {
                    file.delete()
                }
            }
            records.putMeta(migPrefix(p) + f.id, newId)
        }
    }

    /** 两份密钥包是不是同一个库的（不同库的密钥包不能互相覆盖）。读不出来时当作不是。 */
    private suspend fun sameVaultBundle(a: CloudDrive, aId: String, b: CloudDrive, bId: String): Boolean {
        val x = bundleVaultId(a, aId) ?: return false
        return x.isNotEmpty() && x == bundleVaultId(b, bId)
    }

    private suspend fun bundleVaultId(d: CloudDrive, id: String): String? = try {
        val out = java.io.ByteArrayOutputStream()
        retrying { out.reset(); d.download(id, out) }
        JSONObject(String(out.toByteArray())).optString("vaultId")
    } catch (e: IOException) {
        null
    } catch (e: org.json.JSONException) {
        null
    }

    /** 这台手机上、Google 账号里自己建的（我是主人的）共享相册个数。 */
    fun ownedSharedAlbums(): Int =
        sharedAlbums.albums.value.count { it.myRole == null && it.provider.kind == DriveKind.GoogleDrive }

    /**
     * 关分享功能前用：先和网盘同步共享相册列表，再数自己建的。同步失败（读不到网盘）返回 null，界面按“可能还有”处理。
     */
    suspend fun checkOwnedSharedAlbums(): Int? = if (sharedAlbums.sync()) ownedSharedAlbums() else null

    /** 共享相册所在的目录：搬存储位置时不复制、不删（它们只在可见区有意义，成员靠它打开相册）。 */
    private fun isSharedPath(path: List<String>) = path.firstOrNull() == SharedAlbums.SHARED_FOLDER

    /** 为分享功能重新连接 Google 账号（申请 drive.file）。结果照常交给 [completeSignIn]。 */
    fun sharingSignInIntent(p: CloudProvider): Intent =
        auth.signInIntent(p, listOf(CloudConfig.GOOGLE_FILE_SCOPE), loginHint = names.getString(p.name, null))

    private fun isLiveIndex(path: List<String>) = path == INDEX_PLAIN || path == INDEX_ENCRYPTED

    private fun isIndexPath(path: List<String>) =
        path == INDEX_PLAIN || path == INDEX_ENCRYPTED || path.last().startsWith("index-broken")

    /** 固定名字的文件：新位置已有同名的就覆盖。 */
    private fun isFixedName(path: List<String>) =
        path == KEYBUNDLE_PATH || path == StorageMarker.PATH || (path.size == 2 && path[0] == CloudConfig.ENCRYPTED_FOLDER && path[1] in FIXED_VAULT_FILES)

    private suspend fun putMarker(drive: CloudDrive, m: StorageMarker.Marker) {
        val bytes = StorageMarker.encode(m)
        retrying { drive.upload(UploadFile(StorageMarker.PATH, "application/json", bytes.size.toLong(), System.currentTimeMillis(), replace = true) { bytes.inputStream() }) }
    }

    private suspend fun readMarker(drive: CloudDrive): StorageMarker.Marker? {
        val id = retrying { drive.findFile(StorageMarker.PATH) } ?: return null
        val out = java.io.ByteArrayOutputStream()
        retrying { out.reset(); drive.download(id, out) }
        return StorageMarker.decode(out.toByteArray())
    }

    /**
     * 这一轮备份能不能用这个账号：别的手机正在搬它的存储位置时不能；别的手机已经搬走了，就跟着切到新位置，
     * 清掉旧位置的记录，接下来按“换手机”的流程从新位置的备份目录找回。读标记失败时照常用（不因为网络抖动停备份）。
     */
    private enum class StorageUse { Ok, IndexOnly, No }

    private suspend fun storageUsable(p: CloudProvider): StorageUse {
        if (p.kind != DriveKind.GoogleDrive || storage.moving(p) != null) return StorageUse.Ok
        val m = try {
            readMarker(drive(p))
        } catch (_: IOException) {
            null
        }
        if (m == null || m.device == deviceId || m.state != StorageMarker.MIGRATING) {
            if (staleElsewhere.remove(p)) refreshStorage()
        }
        if (m == null) return StorageUse.Ok
        // 这台手机自己以前搬走了、后来断开又重新连接（位置被重置回去了）：回到搬过去的那边
        if (m.device == deviceId) return if (m.state == StorageMarker.MOVED && m.to != storage.space(p)) adoptOwnMove(p, m) else StorageUse.Ok
        return when (m.state) {
            StorageMarker.MIGRATING -> {
                // 那台手机很久没有进展（卸载了、一直搬不动）：提示用户；用户选了不再等它，就照常备份
                val stale = System.currentTimeMillis() - m.at > MIGRATION_STALE_MS
                if (stale != (p in staleElsewhere)) {
                    if (stale) staleElsewhere += p else staleElsewhere -= p
                    refreshStorage()
                }
                when {
                    stale && storage.ignoredMarker(p) == m.at -> StorageUse.Ok
                    // 不开始新上传；已经传完的文件照常写进旧位置的目录，那台手机合并时就能带过去
                    else -> StorageUse.IndexOnly
                }
            }
            StorageMarker.MOVED -> followMoved(p, m)
            else -> StorageUse.Ok
        }
    }

    /**
     * 别的设备（另一台手机或网页版）已经把这个 Google 账号的库搬到 [m].to：跟着切过去，清掉旧位置的记录，
     * 接下来按“换手机”的流程从新位置找回。缺可见文件夹的授权时先不切，返回 [StorageUse.No]。要在 [lock] 里调用。
     */
    /** 这个账号的库在可见文件夹里（标记说的），这台手机却还没授权读它：强制授权框据此只拦这种账号。 */
    private fun markNeedsVisible(p: CloudProvider, on: Boolean) {
        names.edit().apply { if (on) putBoolean(KEY_NEEDS_VISIBLE + p.name, true) else remove(KEY_NEEDS_VISIBLE + p.name) }.apply()
    }

    private suspend fun followMoved(p: CloudProvider, m: StorageMarker.Marker): StorageUse {
        diag("followMoved ${p.name}: marker ${m.state}->${m.to} space=${storage.space(p)} fileScope=${auth.hasScope(p, CloudConfig.GOOGLE_FILE_SCOPE)}")
        if (m.to == storage.space(p)) return StorageUse.Ok
        if (m.to == DriveSpace.Visible && !auth.hasScope(p, CloudConfig.GOOGLE_FILE_SCOPE)) {
            synchronized(storageLock) { storage.wantVisible = true }
            markNeedsVisible(p, true)
            refreshStorage()
            return StorageUse.No
        }
        markNeedsVisible(p, false)
        synchronized(storageLock) {
            storage.setSpace(p, m.to)
            // 跟着别的设备开分享：整机打开。跟着关：只有别的 Google 账号都不在可见文件夹时才整机关掉，
            // 不然那些账号会被自动搬回隐藏区（它们的共享相册、外链会失效）
            storage.wantVisible = m.to == DriveSpace.Visible ||
                googleAccounts().any { it != p && auth.isSignedIn(it) && storage.space(it) == DriveSpace.Visible }
            // 这台手机切过去后还没搬的旧备份：库已经被别的设备搬回去了，由它负责
            storage.setCleanup(p, null)
            storage.setLeftover(p, 0)
        }
        records.dropMeta(migPrefix(p))
        records.clear(p)
        records.clearAccountTables(p)
        resetAccount(p)
        reloadRecords()
        refreshStorage()
        return StorageUse.Ok
    }

    /**
     * 登录时、开始搬之前用：当前位置挂着别的设备写的“已搬走”标记就跟过去（见 [followMoved]）。
     * 没有这种标记、或这台手机自己正在搬/清理时返回 null。读不到标记时：[strict] 为 true 抛出 IOException
     * （开始搬之前必须确认没有别人的标记），否则当作没有。要在 [lock] 里调用。
     */
    private suspend fun followIfMovedElsewhere(p: CloudProvider, strict: Boolean = false): StorageUse? {
        if (p.kind != DriveKind.GoogleDrive || storage.moving(p) != null) return null
        diag("followIfMoved ${p.name}: space=${storage.space(p)} wantVisible=${storage.wantVisible} fileScope=${auth.hasScope(p, CloudConfig.GOOGLE_FILE_SCOPE)}")
        var readOk = true
        val m = try {
            readMarker(drive(p))
        } catch (e: IOException) {
            if (strict) throw e
            readOk = false
            null
        }
        // 读到了、而且标记没说库在别处（没有标记、不是“已搬走”、或者搬去的就是这里）：以前记的“库在可见文件夹”作废，
        // 不然这个账号会一直等授权、一直不就绪。读失败时不动
        if (readOk && (m == null || m.state != StorageMarker.MOVED || m.to == storage.space(p))) markNeedsVisible(p, false)
        if (m == null || m.state != StorageMarker.MOVED) return null
        if (m.device == deviceId) return if (m.to != storage.space(p)) adoptOwnMove(p, m) else null
        return followMoved(p, m)
    }

    /**
     * 当前位置挂着这台手机自己写的“已搬走”标记，本机却还在用这个位置：库以前被这台手机搬走了，
     * 之后断开、重新连接过（本机的位置记录被重置成了隐藏区）。以前的版本不认自己写的标记，会一直留在旧位置，
     * 看不到新位置里的全部备份，新照片也传回了旧位置。
     *
     * 修复：回到新位置，但不清本机记录（里面可能有这段时间传到旧位置的照片，按 id 照样能读），
     * 和新位置的目录合并后重写；旧位置这段时间多出来的文件按正常流程搬过去。要在 [lock] 里调用。
     */
    private suspend fun adoptOwnMove(p: CloudProvider, m: StorageMarker.Marker): StorageUse {
        if (m.to == DriveSpace.Visible && !auth.hasScope(p, CloudConfig.GOOGLE_FILE_SCOPE)) {
            synchronized(storageLock) { storage.wantVisible = true }
            markNeedsVisible(p, true)
            refreshStorage()
            return StorageUse.No
        }
        markNeedsVisible(p, false)
        val from = storage.space(p)
        val src = GoogleDriveApi(auth, app, p) { from }
        // 旧位置这段时间写的密钥包、身份这些：比新位置的新才带过去（读不到就先不修，下一轮再来）
        val srcTree = try {
            retrying { src.listTree() }.filter { (path, _) -> path != StorageMarker.PATH && !isSharedPath(path) }
        } catch (e: IOException) {
            return StorageUse.No
        }
        try {
            carryFixedFiles(p, src, GoogleDriveApi(auth, app, p) { m.to }, srcTree)
        } catch (e: IOException) {
            records.dropMeta(migPrefix(p))
            return StorageUse.No
        }
        val photos = srcTree.count { (path, f) -> !isFixedName(path) && !isLiveIndex(path) && isPhotoFile(f) }
        synchronized(storageLock) {
            storage.setSpace(p, m.to)
            storage.setSwitchedAt(p, System.currentTimeMillis())
            storage.setCleanup(p, from)
            storage.setLeftover(p, photos)
            storage.wantVisible = m.to == DriveSpace.Visible
        }
        names.edit().remove(KEY_INDEX_ID + p.name).remove(KEY_INDEX_PUSHED + p.name).putBoolean(KEY_INDEX_OVERRIDE + p.name, true).apply()
        markIndexDirty()
        runCatching { uploadIndex(setOf(p), vault.wantEncrypted, runCatching { vault.wrapKey() }.getOrNull(), force = true) }
        reloadRecords()
        notifyPicker()
        refreshStorage()
        scope.launch { scheduleMigration() }
        return StorageUse.Ok
    }

    /** 用户选择不再等别的手机搬完：记下那份标记的时间。那台手机又有进展（时间变了）就重新等它。 */
    fun stopWaitingForOtherDevice(p: CloudProvider) {
        scope.launch {
            val m = runCatching { readMarker(drive(p)) }.getOrNull() ?: return@launch
            if (m.state != StorageMarker.MIGRATING) return@launch
            storage.setIgnoredMarker(p, m.at)
            staleElsewhere -= p
            refreshStorage()
        }
    }

    /**
     * 后台任务调用：把旧位置剩下的备份搬到新位置。[onlySmall] 为 true 时只搬剩得少的账号（白天、连着 Wi‑Fi 就跑）。
     * [stop] 为 true 时（条件不满足了）停下，下次接着搬。全部搬完返回 true。
     * 一个账号一旦开始搬就锁住开关（[StoragePrefs.moving]），直到搬完。
     */
    internal suspend fun runStorageMigration(onlySmall: Boolean = false, stop: () -> Boolean): Boolean {
        // 白天（剩得少）和夜里两个任务可能同时被系统启动：同一时间只让一个搬。白天那个碰上就隔一段再来，夜里的等它搬完接着搬
        if (onlySmall) {
            if (!migrationMutex.tryLock()) return false
        } else {
            migrationMutex.lock()
        }
        try {
            return runStorageMigrationLocked(onlySmall, stop)
        } finally {
            migrationMutex.unlock()
        }
    }

    private val migrationMutex = Mutex()

    private suspend fun runStorageMigrationLocked(onlySmall: Boolean, stop: () -> Boolean): Boolean {
        // 删除全部备份数据还没做完：先不搬
        if (wipeUnfinished) return false
        // 还没切过去的先切（比如开关打开时没联网）
        if (googleAccounts().any(::needsSwitch)) runCatching { exclusive { switchAllLocked() } }
        var allDone = true
        for (p in googleAccounts()) {
            if (stop()) return false
            val leftover = storage.cleanup(p)
            if (leftover != null) {
                if (onlySmall && !isSmallMove(p)) {
                    allDone = false
                    continue
                }
                // 从这一刻起开关锁住，直到搬完
                if (storage.moving(p) == null) synchronized(storageLock) { storage.setMoving(p, storage.space(p)) }
                refreshStorage()
                if (!cleanupOldSpace(p, leftover, stop)) allDone = false
                continue
            }
            if (onlySmall) continue
            // 旧版本开始的、还没切换的搬迁（先复制全部再切换）：照旧做完
            val to = storage.moving(p) ?: continue
            if (to == storage.space(p)) {
                // 新流程的清理收尾时中断留下的锁：旧位置已经清完了
                synchronized(storageLock) { storage.setMoving(p, null) }
                continue
            }
            if (to == DriveSpace.Visible && !auth.hasScope(p, CloudConfig.GOOGLE_FILE_SCOPE)) {
                allDone = false
                continue
            }
            val moved = try {
                beginMove(p, storage.space(p), to) && waitForOthers(p, stop) &&
                    lock.withLock { moveLocked(p, storage.space(p), to, stop) }
            } catch (e: IOException) {
                android.util.Log.w("ZyllStorage", "move failed for $p", e)
                false
            }
            if (!moved) {
                allDone = false
                continue
            }
            storage.cleanup(p)?.let { from -> if (!cleanupOldSpace(p, from, stop)) allDone = false }
        }
        refreshStorage()
        return allDone
    }

    /** 开始搬：锁住开关，旧位置挂上“正在搬”。已经开始过的直接返回 true；用户已经改回去了返回 false。 */
    private suspend fun beginMove(p: CloudProvider, from: DriveSpace, to: DriveSpace): Boolean {
        if (storage.moving(p) != null) return true
        // 开始前再确认一次用户没改回去；从这一刻起开关锁住
        synchronized(storageLock) {
            if (targetSpace() != to) return false
            storage.setMoving(p, to)
            storage.setMovingSince(p, System.currentTimeMillis())
        }
        refreshStorage()
        val src = GoogleDriveApi(auth, app, p) { from }
        val dst = GoogleDriveApi(auth, app, p) { to }
        // 新位置可能留着以前搬走时写的旧标记：先删掉，免得别的手机读到后又搬回去
        runCatching { retrying { dst.findFile(StorageMarker.PATH) }?.let { id -> retrying { dst.delete(id) } } }
        // 旧位置挂上“正在搬”：别的手机和网页版看到就不往这个账号写
        putMarker(src, StorageMarker.Marker(StorageMarker.MIGRATING, to, System.currentTimeMillis(), deviceId))
        return true
    }

    /** 挂上标记后先等一会儿，让别的手机、网页版正在传的文件和目录写完。被打断返回 false，下次从剩下的时间接着等。 */
    private suspend fun waitForOthers(p: CloudProvider, stop: () -> Boolean): Boolean {
        val since = storage.movingSince(p)
        while (System.currentTimeMillis() - since < MIGRATION_GRACE_MS) {
            if (stop()) return false
            kotlinx.coroutines.delay(15_000)
        }
        return true
    }

    /**
     * 把 [src] 里还没复制过的文件（备份目录和标记文件除外）复制到 [dst]，复制过的记在 `mig:` 映射里。
     * 复制完再列一遍，直到没有新文件。被打断返回 false。
     */
    private suspend fun copyMissing(
        p: CloudProvider, src: GoogleDriveApi, dst: CloudDrive, mapped: MutableMap<String, String>, stop: () -> Boolean,
        beat: (suspend () -> Unit)? = null,
    ): Boolean {
        val prefix = migPrefix(p)
        val tmp = File(app.cacheDir, "storage-move").apply { mkdirs() }
        repeat(MIGRATION_ROUNDS) {
            // 另存的坏目录（index-broken-*）也复制：用户可能还要拿它找回照片
            val tree = retrying { src.listTree() }.filter { (path, _) -> path != StorageMarker.PATH && !isLiveIndex(path) && !isSharedPath(path) }
            val missing = tree.filter { it.second.id !in mapped }
            if (missing.isEmpty()) return true
            var done = tree.size - missing.size
            _storage.update { it.copy(done = done, total = tree.size) }
            for ((path, f) in missing) {
                if (stop()) return false
                val file = File(tmp, "blob-${p.name}")
                try {
                    retrying { file.outputStream().use { src.download(f.id, it) } }
                    val up = UploadFile(path, "application/octet-stream", file.length(), if (f.createdAt > 0) f.createdAt else System.currentTimeMillis(), replace = isFixedName(path)) {
                        file.inputStream().buffered()
                    }
                    val newId = retrying { dst.upload(up) }
                    records.putMeta(prefix + f.id, newId)
                    mapped[f.id] = newId
                    beat?.invoke()
                } finally {
                    file.delete()
                }
                done++
                _storage.update { it.copy(done = done, total = tree.size) }
            }
        }
        // 一直有新文件进来（标记挂着，正常不会这样）：先不切换，下次再来
        return false
    }

    private fun migPrefix(p: CloudProvider) = "mig:${p.name}:"

    /** 复制全部密文到新位置（不解密），然后切换。返回 false 表示这次没搬完（被打断或读不到旧目录）。 */
    private suspend fun moveLocked(p: CloudProvider, from: DriveSpace, to: DriveSpace, stop: () -> Boolean): Boolean {
        val src = GoogleDriveApi(auth, app, p) { from }
        val dst = GoogleDriveApi(auth, app, p) { to }
        val key = runCatching { vault.wrapKey() }.getOrNull()
        // 先把旧位置的目录合进本机记录：上次合并之后网页版、别的手机写进去的照片也要带过去。
        // 这时 drive(p) 还是旧位置。读不到就先不搬，免得新目录里漏掉别人的照片
        try {
            mergePulled(p, pullIndex(p, key))
            if (stuckMoving.remove(p)) refreshStorage()
        } catch (e: IOException) {
            stuckMoving += p
            refreshStorage()
            throw e
        }
        // 心跳：告诉别的手机这边还在搬
        putMarker(src, StorageMarker.Marker(StorageMarker.MIGRATING, to, System.currentTimeMillis(), deviceId))
        val mapped = records.metaWithPrefix(migPrefix(p)).toMutableMap()
        var lastBeat = System.currentTimeMillis()
        val heartbeat: suspend () -> Unit = {
            if (System.currentTimeMillis() - lastBeat > MIGRATION_BEAT_MS) {
                lastBeat = System.currentTimeMillis()
                runCatching { putMarker(src, StorageMarker.Marker(StorageMarker.MIGRATING, to, lastBeat, deviceId)) }
            }
        }
        if (!copyMissing(p, src, dst, mapped, stop, heartbeat)) return false
        if (stop()) return false
        // 搬的过程中删掉的照片：新位置那份也删掉，不留没人用的文件
        records.pendingDeletesOf(p, Int.MAX_VALUE).mapNotNull { mapped[it] }.forEach { id -> runCatching { retrying { dst.delete(id) } } }
        // 切换：记录改成新 id，之后 drive(p) 读写新位置。映射表留到旧位置清理完再删
        records.remapProvider(p, mapped)
        storage.setSpace(p, to)
        storage.setSwitchedAt(p, System.currentTimeMillis())
        storage.setCleanup(p, from)
        names.edit().remove(KEY_INDEX_ID + p.name).remove(KEY_INDEX_PUSHED + p.name).apply()
        markIndexDirty()
        reloadRecords()
        notifyPicker()
        // 新位置的备份目录：失败也没关系，清理旧位置前会再确认一次
        runCatching { uploadIndex(setOf(p), vault.wantEncrypted, key, force = true) }
        putMarker(src, StorageMarker.Marker(StorageMarker.MOVED, to, System.currentTimeMillis(), deviceId))
        refreshStorage()
        return true
    }

    /**
     * 旧位置的目录再读一次，把里面的条目换成新 id 合进本机记录（换不了 id 的说明文件不在了，跳过），
     * 用来带上别的设备在切换前后写进旧位置的照片。要在 [lock] 里调用。
     */
    private suspend fun mergeOldIndexLocked(p: CloudProvider, src: GoogleDriveApi, mapped: Map<String, String>, key: ByteArray?): Boolean {
        val old = try {
            pullIndex(p, key, from = src)
        } catch (e: IndexCorruptException) {
            return false
        }
        if (!old.exists) return false
        fun id(r: String) = mapped[r]
        val items = old.items.mapNotNull { o ->
            val r = id(o.r) ?: return@mapNotNull null
            val th = if (o.th.isEmpty()) "" else id(o.th).orEmpty()
            IndexItem(r, o.e, o.n, o.a, o.m, o.t, o.s, o.h, th, o.d, o.k, o.mo)
        }
        val remapped = PulledIndex(
            items = items,
            deleted = old.deleted.mapNotNullTo(HashSet()) { id(it) },
            settings = null, seq = 0, encId = null, plainId = null, encMerged = old.encMerged,
            devices = old.devices.mapNotNull { (r, d) -> id(r)?.let { it to d } }.toMap(),
            foreign = old.foreign.mapNotNullTo(HashSet()) { id(it) },
            mode = null,
            // 旧位置的待删文件跟着旧位置一起删掉，不带过去
            trash = emptySet(),
            plainMerged = old.plainMerged, unmergedRefs = emptySet(), unmergedForeign = emptySet(), complete = false,
        )
        return mergePulled(p, remapped)
    }

    /**
     * 把旧位置剩下的文件搬到新位置，然后删掉旧位置的（保留标记文件，告诉别的设备去新位置找）。
     * 1. 不拿 [lock]：复制还没复制过的文件（只往新位置加文件、记映射，不碰记录），备份、删除、释放空间照常；
     * 2. 拿 [lock]：复制期间被删掉的照片，新位置那份也删掉；记录里旧 id 换成新 id；合并旧目录；重写新位置的目录；
     * 3. 再列一遍旧位置，有没复制过的新文件就先不删，下次再来；否则删掉复制过的文件和旧目录，解锁开关、丢掉映射表。
     */
    private suspend fun cleanupOldSpace(p: CloudProvider, from: DriveSpace, stop: () -> Boolean): Boolean {
        val src = GoogleDriveApi(auth, app, p) { from }
        val mapped = records.metaWithPrefix(migPrefix(p)).toMutableMap()
        try {
            // 1. 固定名字的小文件：切换之后旧位置那份又改过（或者是新出现的），按新旧再带一次；其余的照常复制
            val switched = storage.switchedAt(p)
            val tree = retrying { src.listTree() }.filter { (path, _) -> path != StorageMarker.PATH && !isSharedPath(path) }
            val fixedAgain = tree.filter { (path, f) -> isFixedName(path) && (f.id !in mapped || f.modifiedAt > switched) }
            if (fixedAgain.isNotEmpty()) {
                carryFixedFiles(p, src, GoogleDriveApi(auth, app, p) { storage.space(p) }, fixedAgain)
                mapped.putAll(records.metaWithPrefix(migPrefix(p)))
            }
            if (!copyMissing(p, src, drive(p), mapped, stop)) return false
            // 按路径找的文件（固定名字的、备份目录）：不进 id 对照表
            val byPath = tree.filter { (path, _) -> isFixedName(path) || isLiveIndex(path) }.mapTo(HashSet()) { it.second.id }
            if (stop()) return false
            // 2.
            val indexed = lock.withLock {
                val key = runCatching { vault.wrapKey() }.getOrNull()
                val gone = tombstones(p) + records.pendingDeletesOf(p, Int.MAX_VALUE)
                val referenced = HashSet<String>()
                records.entries().filter { it.provider == p }.forEach { referenced += it.remoteId; if (it.thumb.isNotEmpty()) referenced += it.thumb }
                val cur = drive(p)
                for ((old, new) in mapped) {
                    if (old in gone && old !in referenced) runCatching { retrying { cur.delete(new) } }
                }
                // 换 id 之前记下对照表，写进目录：别的设备（和网页）手里还是旧 id，拉目录时按表换掉。
                // 固定名字的小文件和目录本身各设备按路径找，不用记；其余的都记（包括下面从旧目录合进来的条目）
                val now = System.currentTimeMillis()
                records.addIdMoves(p, mapped.filterKeys { it !in byPath }.mapValues { it.value to now })
                records.remapIds(p, mapped)
                runCatching { mergeOldIndexLocked(p, src, mapped, key) }
                markIndexDirty()
                reloadRecords()
                notifyPicker()
                runCatching { uploadIndex(setOf(p), vault.wantEncrypted, key, force = true) }
                retrying { cur.findFile(INDEX_ENCRYPTED) ?: cur.findFile(INDEX_PLAIN) } != null
            }
            // 新位置的目录还没写成：旧位置的先不删
            if (!indexed) return false
            // 3. 删之前确认库还在这边：别的设备这期间把库切回了旧位置（新位置挂着它写的“已搬走”、或者旧位置的标记不是这台手机写的了），
            // 就不删，下一轮备份时跟过去
            val home = readMarker(drive(p))
            val there = readMarker(src)
            if (home != null && home.device != deviceId && home.state == StorageMarker.MOVED) return false
            if (there != null && (there.state != StorageMarker.MOVED || there.to != storage.space(p))) return false
            // 标记丢了（当时没写成）：补上，别的设备才知道去新位置
            if (there == null) putMarker(src, StorageMarker.Marker(StorageMarker.MOVED, storage.space(p), System.currentTimeMillis(), deviceId))
            val all = retrying { src.listTree() }.filter { (path, _) -> path != StorageMarker.PATH && !isSharedPath(path) }
            if (all.any { (path, f) -> !isLiveIndex(path) && f.id !in mapped }) return false
            for ((path, f) in all) {
                if (stop()) return false
                if (f.id in mapped || isLiveIndex(path)) runCatching { retrying { src.delete(f.id) } }
            }
        } catch (_: IOException) {
            return false
        }
        records.dropMeta(migPrefix(p))
        // 先解锁再清“要清理”：中途被杀掉时，下次看到“要清理”会再收一遍尾（不会出现锁着却没事可做）
        synchronized(storageLock) {
            storage.setMoving(p, null)
            storage.setCleanup(p, null)
            storage.setLeftover(p, 0)
        }
        refreshStorage()
        return true
    }

    // ---------- 共享相册 ----------

    /** 共享相册（见 [SharedAlbums]）。 */
    val sharedAlbums: SharedAlbums by lazy {
        SharedAlbums(app, object : SharedAlbums.Host {
            override fun sharingDrive(p: CloudProvider) = drive(p) as SharingDrive
            override fun cloudDrive(p: CloudProvider) = drive(p)
            override fun wrapKey() = runCatching { vault.wrapKey() }.getOrNull()
            override fun accountEmail(p: CloudProvider) = _drives.value.firstOrNull { it.provider == p }?.account?.name ?: names.getString(p.name, null)
            override fun canShare(p: CloudProvider) = p.kind != DriveKind.GoogleDrive || storage.space(p) == DriveSpace.Visible
            override fun oldWrapKeys() = vault.oldKeyIds().mapNotNull { runCatching { vault.oldKey(it) }.getOrNull() }
            override fun signedIn() = CloudProvider.entries.filter { auth.isSignedIn(it) && CloudConfig.of(it).configured }
            override suspend fun vaultMayBeEncrypted(p: CloudProvider): Boolean =
                // 账号还在检查、或者库其实在别处（这台手机还没跟过去）：当前位置看不全，按“可能加密过”处理
                (p in _accountChecks.value || names.getBoolean(KEY_NEEDS_VISIBLE + p.name, false) ||
                    vault.wantEncrypted || vault.bundleJson != null ||
                    runCatching { retrying { drive(p).findFile(KEYBUNDLE_PATH) } != null }.getOrDefault(true))
                    .also { diag("vaultMayBeEncrypted ${p.name}: $it space=${storage.space(p)}") }
            // 正在删除全部备份数据、删到一半，或者这个账号在别的设备上删除过：共享相册不往网盘写任何东西
            override fun writesBlocked(p: CloudProvider) = _wipe.value.running || wipeUnfinished || wipeHeld(p)
            override suspend fun awaitingMove(p: CloudProvider): Set<String> = idsAwaitingMove(p)
        })
    }

    // ---------- 外链分享 ----------

    /** 外链分享（spec/share-link.md）。 */
    val shareLinks: ShareLinks by lazy {
        ShareLinks(app, object : ShareLinks.Host {
            override fun sharingDrive(p: CloudProvider) = drive(p) as SharingDrive
            override fun cloudDrive(p: CloudProvider) = drive(p)
            override fun keyFor(keyId: String) = runCatching { this@CloudBackup.keyFor(keyId) }.getOrNull()
            override fun wrapKey() = runCatching { vault.wrapKey() }.getOrNull()
            override fun canShare(p: CloudProvider) = p.kind != DriveKind.GoogleDrive || storage.space(p) == DriveSpace.Visible
            override fun writesBlocked(p: CloudProvider) = _wipe.value.running || wipeUnfinished || wipeHeld(p)
            override fun signedIn() = CloudProvider.entries.filter { auth.isSignedIn(it) && CloudConfig.of(it).configured }
            override fun scheduleExpiry(at: Long?) = ShareLinkWorker.schedule(app, at)
        })
    }

    /** Google 账号里还有效的外链条数（关分享功能前提示：库搬回隐藏区后链接就打不开了，要先撤销）。 */
    fun activeGoogleShareLinks(): Int = googleAccounts().sumOf { shareLinks.activeOn(it) }

    /** 现在不能用这些备份建外链的原因；null 表示可以。只看本机记录，不联网。 */
    fun shareLinkUnavailableReason(entries: List<CloudEntry>): String? = shareLinks.unavailableReason(entries)

    /** 建外链前预览：几张能放进去、几张没备份、几张还在等搬（见 [ShareLinkPreview]）。要联网，在后台调用。 */
    suspend fun previewShareLink(photos: List<List<CloudEntry>>): ShareLinkPreview = shareLinks.preview(photos)

    /**
     * 建外链。[photos]：每张照片在各个账号里的备份（组内任选一份能用的）。失败时抛 [ShareLinkException]，
     * 它的 message 可以直接给用户看。返回的 [ShareLinkSummary.url] 不含密码。
     * 每张照片要另存一份副本（下载再上传），几百张、大视频会花好几分钟：[onProgress] 报告做完几张 / 共几张。
     * 调用方被取消时，已经传上去的副本会删掉，链接不生效。
     */
    suspend fun createShareLink(
        photos: List<List<CloudEntry>>, title: String, password: String, expiresAt: Long?, allowOriginals: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ShareLinkSummary = shareLinks.create(photos, title, password, expiresAt, allowOriginals, onProgress)

    /** 撤销外链：先删清单（链接马上打不开），再删这条链接的照片副本；删不完的联网后接着删。 */
    suspend fun revokeShareLink(id: String) = shareLinks.revoke(id)

    private var linksSweptAt = 0L

    /** 打开 App 时（最多 6 小时一次）：合并别的设备的链接列表，撤掉到期的。 */
    private fun sweepShareLinksIfDue() {
        val now = System.currentTimeMillis()
        if (now - linksSweptAt < 6 * 3600_000L) return
        linksSweptAt = now
        scope.launch { runCatching { shareLinks.sweep() } }
    }

    /** 还在旧位置、等着搬过去的文件 id（Google 账号切换了存放位置、旧位置还没搬完时）。 */
    private suspend fun idsAwaitingMove(p: CloudProvider): Set<String> {
        if (p.kind != DriveKind.GoogleDrive) return emptySet()
        storage.cleanup(p)?.let { from -> return retrying { GoogleDriveApi(auth, app, p) { from }.listAll() }.mapTo(HashSet()) { it.id } }
        // 这台设备是跟着别的设备过来的（不记“旧位置待清理”）：旧位置还有东西，就是那台设备还没搬完的，同样不能公开
        if (storage.space(p) != DriveSpace.Visible) return emptySet()
        return try {
            retrying { GoogleDriveApi(auth, app, p) { DriveSpace.Hidden }.listAll() }.mapTo(HashSet()) { it.id }
        } catch (e: HttpException) {
            if (e.code == 401 || e.code == 403) emptySet() else throw e
        }
    }

    /**
     * 这些网盘照片里有几张还在等搬到可见文件夹（搬完之前不能放进共享相册）。界面在建共享相册、加照片前用来提示。
     * 读不到网盘时返回 null。
     */
    suspend fun awaitingMoveCount(entries: List<CloudEntry>): Int? = withContext(Dispatchers.IO) {
        try {
            entries.groupBy { it.provider }.entries.sumOf { (p, list) ->
                val waiting = idsAwaitingMove(p)
                list.count { it.remoteId in waiting }
            }
        } catch (e: IOException) {
            null
        }
    }

    // ---------- 端到端加密 ----------

    /** 本机还留着加密密钥（开着加密，或刚关闭、旧备份还没全部转换完）。 */
    val hasEncryptionKey: Boolean get() = vault.bundleJson != null

    /** 解密一份备份要用的密钥：[keyId] 空 = 当前这套，否则是暂存的以前那套。拿不到时为 null。 */
    private fun keyFor(keyId: String): ByteArray? = if (keyId.isEmpty()) vault.wrapKey() else vault.oldKey(keyId)

    /** 用户选过“不保留”的那些密钥（vaultId）。 */
    private fun retiredKeys(): Set<String> = names.getStringSet(KEY_RETIRED_KEYS, emptySet())!!.toSet()

    /** 正在等用户选“保留哪套密钥”的那套旧密钥（vaultId）；没有时为 null。 */
    private fun pendingKeyChoice(): String? = names.getString(KEY_KEY_CHOICE, null)

    /**
     * 这一轮要转换的旧备份：加密状态和开关不一致的，以及还用着以前那套密钥的。
     * 等用户选密钥期间、或者这台手机根本没有那套旧密钥的（别的手机还没换完）不算。
     */
    private fun staleRecords(all: List<RecordEntry>, want: Boolean): List<RecordEntry> {
        val choice = pendingKeyChoice()
        val olds = vault.oldKeyIds()
        val bad = brokenSet()
        return all.filter { r ->
            when {
                // 坏掉的备份转换不了，不算“还没转换”
                brokenKey(r.provider, r.remoteId) in bad -> false
                r.encrypted != want -> r.keyId.isEmpty() || (r.keyId in olds && r.keyId != choice)
                r.encrypted && r.keyId.isNotEmpty() -> r.keyId in olds && r.keyId != choice
                else -> false
            }
        }
    }

    private val _pendingRecoveryKey = MutableStateFlow(vault.pendingRecovery())

    /**
     * 刚生成、用户还没确认“已经抄下”的恢复密钥。放在这里而不是界面里：界面被关掉或重建时也不会丢，
     * 下次回到 App 还会再显示，直到用户确认（[recoveryKeySaved]）。
     * 本机用硬件密钥封装后另存一份（见 [Vault.setPendingRecovery]），App 在用户抄下之前被系统杀掉也还在；确认后马上删掉。
     */
    val pendingRecoveryKey: StateFlow<String?> = _pendingRecoveryKey.asStateFlow()

    fun recoveryKeySaved() {
        _pendingRecoveryKey.value = null
        runCatching { vault.setPendingRecovery(null) }
    }

    private fun showRecoveryKey(display: String) {
        runCatching { vault.setPendingRecovery(display) }
        _pendingRecoveryKey.value = display
    }

    /** 开启加密：生成密钥，之后把所有已有备份换成加密版本。返回要让用户抄下的恢复密钥。 */
    suspend fun enableEncryption(password: String): String? = withContext(Dispatchers.Default) {
        exclusive {
            if (wipeUnfinished) throw IllegalStateException(tr(R.string.wipe_finish_first))
            // 先确认每个已连接的网盘里有没有以前的密钥包：有的话不能再新建一套，否则以前的加密备份就永远解不开了
            for (p in CloudProvider.entries.filter { auth.isSignedIn(it) }) {
                try {
                    checkRemoteVault(p)
                } catch (e: IOException) {
                    throw IOException(tr(R.string.e2ee_cannot_check_remote), e)
                }
            }
            if (_encryption.value.needsUnlock) throw IllegalStateException(tr(R.string.e2ee_remote_needs_unlock))
            // 关闭加密后还没转换完又重新打开：沿用原来的密钥（否则已加密的那部分就解不开了），这时不产生新的恢复密钥
            val recovery = if (vault.bundleJson != null) {
                checkPasswordFresh(password)
                vault.wantEncrypted = true
                null
            } else {
                // 新密码必须够强（至少 10 位、字母加数字、不是常见弱口令）
                PasswordPolicy.problem(password)?.let { throw IllegalArgumentException(it) }
                // 新的一套密钥：以前记的“密钥包已上传”都不算数了
                val edit = names.edit()
                CloudProvider.entries.forEach { edit.remove(KEY_VAULT_UPLOADED_PREFIX + it.name) }
                edit.putStringSet(KEY_VAULT_ACCOUNTS, emptySet()).apply()
                // 生成后马上记下：后面的步骤或界面中途被取消，用户也还能看到它
                vault.create(password).also { showRecoveryKey(it) }
            }
            // 记下这次改加密开关的时间：别的手机据此判断谁的选择更新（见 [modeConflicts]）
            names.edit().putLong(KEY_MODE_AT, System.currentTimeMillis()).apply()
            markIndexDirty()
            refreshUnlockState()
            reloadRecords()
            backupNow(manual = true)
            recovery
        }
    }

    /**
     * 用原来的密码（[recoveryKey] 为 false）或恢复密钥解锁：
     * - 换手机后：解开网盘里的密钥包，把原来的密钥存到这台手机上，再读取加密的备份目录；
     * - 本机硬件密钥失效时：用本机存着的密钥包修复。
     * 密码不对会抛异常。
     */
    suspend fun unlockRemoteVault(secret: String, recoveryKey: Boolean) = withContext(Dispatchers.Default) {
        diag("unlockRemoteVault: spaces=${CloudProvider.entries.filter { auth.isSignedIn(it) }.joinToString { it.name + ":" + storage.space(it) + " checked=" + names.getBoolean(KEY_INDEX_CHECKED + it.name, false) }}")
        exclusive {
            fun unlockWith(json: String) = if (recoveryKey) KeyBundle.unlockWithRecoveryKey(json, secret.trim()) else KeyBundle.unlockWithPassword(json, secret)
            val local = vault.bundleJson
            if (local != null && _encryption.value.keyBroken) {
                // 本机的密钥包可能比网盘上的旧（别的设备后来改过密码或换过恢复密钥）：本机的解不开时再试网盘上同一套的
                val localId = KeyBundle.parse(local).vaultId
                val (json, unlocked) = runCatching { local to unlockWith(local) }.getOrNull()
                    ?: newestFirst(sameVaultRemoteBundles(localId).values).firstNotNullOfOrNull { j -> runCatching { j to unlockWith(j) }.getOrNull() }
                    ?: throw IllegalArgumentException(if (recoveryKey) tr(R.string.e2ee_wrong_recovery_key) else tr(R.string.e2ee_wrong_password))
                // 只是修好本机的密钥，加密开关保持原样
                vault.adopt(json, unlocked.wrapKey, want = vault.wantEncrypted)
                // 暂存的以前那套也是用坏掉的硬件密钥封装的，现在解不开了：丢掉它，
                // 把它的密钥包放回原来的账号，让用户用那套的密码重新解锁（用它加密的记录在那之前不转换）
                val edit = names.edit()
                for (id in vault.oldKeyIds()) {
                    if (runCatching { vault.oldKey(id) }.isSuccess) continue
                    val oldBundle = vault.oldBundle(id)
                    CloudProvider.entries.filter { names.getString(KEY_REKEY_ACCOUNT + it.name, null) == id }.forEach { p ->
                        edit.remove(KEY_REKEY_ACCOUNT + p.name).remove(KEY_INDEX_CHECKED + p.name)
                        if (oldBundle != null) edit.putString(KEY_REMOTE_BUNDLE + p.name, oldBundle)
                    }
                    if (pendingKeyChoice() == id) edit.remove(KEY_KEY_CHOICE)
                    vault.dropOldKey(id)
                }
                edit.apply()
            } else if (local != null) {
                // 手机上已经有一套密钥，某个账号里是另一套（比如清过数据后用新密码备份过，又接回了以前的账号）：
                // 用那套的密码解开、暂存起来，那个账号里的照片马上能看；再让用户选保留哪一套，没选上的那套在后台换掉
                val bundles = remoteBundles()
                if (bundles.isEmpty()) throw IllegalStateException(tr(R.string.e2ee_no_remote_backup))
                val (json, unlocked) = newestFirst(bundles.values).firstNotNullOfOrNull { json -> runCatching { json to unlockWith(json) }.getOrNull() }
                    ?: throw IllegalArgumentException(if (recoveryKey) tr(R.string.e2ee_wrong_recovery_key) else tr(R.string.e2ee_wrong_password))
                val edit = names.edit()
                if (unlocked.vaultId != vault.vaultId) {
                    vault.addOldKey(json, unlocked.vaultId, unlocked.wrapKey)
                    // 用户以前已经决定换掉这套（比如硬件密钥失效后要重新解锁它）：不再问一遍，直接接着换
                    if (unlocked.vaultId !in retiredKeys()) edit.putString(KEY_KEY_CHOICE, unlocked.vaultId)
                }
                val same = unlocked.vaultId == vault.vaultId
                bundles.forEach { (p, b) ->
                    if (runCatching { KeyBundle.parse(b).vaultId }.getOrNull() == unlocked.vaultId) {
                        // 这个账号的目录要用旧密钥重新读一遍；解开的恰好就是当前这套时不用记“要换密钥”
                        edit.remove(KEY_REMOTE_BUNDLE + p.name).remove(KEY_INDEX_CHECKED + p.name).remove(KEY_INDEX_LOCKED + p.name)
                        if (!same) edit.putString(KEY_REKEY_ACCOUNT + p.name, unlocked.vaultId)
                    }
                }
                edit.apply()
            } else {
                val bundles = remoteBundles()
                if (bundles.isEmpty()) throw IllegalStateException(tr(R.string.e2ee_no_remote_backup))
                val (json, unlocked) = newestFirst(bundles.values).firstNotNullOfOrNull { json -> runCatching { json to unlockWith(json) }.getOrNull() }
                    ?: throw IllegalArgumentException(if (recoveryKey) tr(R.string.e2ee_wrong_recovery_key) else tr(R.string.e2ee_wrong_password))
                vault.adopt(json, unlocked.wrapKey)
                val edit = names.edit()
                var tags = vaultAccounts()
                bundles.forEach { (p, b) ->
                    // 同一套密钥的网盘都解锁了；密钥包本来就在网盘上，不用再传
                    if (runCatching { KeyBundle.parse(b).vaultId }.getOrNull() == unlocked.vaultId) {
                        edit.remove(KEY_REMOTE_BUNDLE + p.name).putBoolean(KEY_VAULT_UPLOADED_PREFIX + p.name, true)
                        tags = tags + accountTag(p)
                    }
                }
                // 加密目录现在能读了，重新读一次
                CloudProvider.entries.forEach { edit.remove(KEY_INDEX_CHECKED + it.name).remove(KEY_INDEX_LOCKED + it.name) }
                edit.putStringSet(KEY_VAULT_ACCOUNTS, tags).apply()
            }
            refreshUnlockState()
            val key = vault.wrapKey()
            for (p in CloudProvider.entries.filter { auth.isSignedIn(it) }) runCatching { restoreIndex(p, key) }
            reloadRecords()
        }
        backupNow(manual = true)
    }

    /**
     * 解开另一套密钥以后，用户选保留哪一套：[keepPrevious] = 保留刚解开的那套（以前的），否则保留手机上现在这套。
     * 没选上的那套暂存着，后台把用它加密的照片逐个换成选中的这套（只在满足备份网络条件时进行），换完后删掉，
     * 网盘上的密钥包也换成选中的这套。以后只需要记选中这套的密码和恢复密钥。
     */
    suspend fun chooseKey(keepPrevious: Boolean) = withContext(Dispatchers.Default) {
        exclusive {
            val id = pendingKeyChoice() ?: return@exclusive
            val cur = vault.vaultId.orEmpty()
            if (keepPrevious) {
                val current = vault.vaultId ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
                // 换密钥分好几步（本机密钥、备份记录、各账号的标记）。先记下要做什么，
                // 中途被杀掉的话下次启动接着做完（见 [recoverKeySwap]），不会出现密钥和记录对不上
                val plan = JSONObject().put("t", java.util.UUID.randomUUID().toString()).put("kept", id).put("retired", current)
                    .put("holders", org.json.JSONArray(vaultAccounts().toList()))
                if (!names.edit().putString(KEY_SWAP, plan.toString()).commit()) throw IOException(tr(R.string.e2ee_save_key_failed))
                vault.swapWithOld(id)
                finishKeySwap()
                // 换下来的那套要等目录用新密钥重传后才能删：不等备份条件，有网就去做
                runCatching { scheduleKeySync() }
            } else {
                // 记下没选上的那套：以后再解锁到它时不用再问
                names.edit().putStringSet(KEY_RETIRED_KEYS, retiredKeys() + id - cur - "").remove(KEY_KEY_CHOICE).apply()
            }
            markIndexDirty()
            refreshUnlockState()
            reloadRecords()
        }
        backupNow(manual = true)
    }

    /** 把记下的换密钥计划做完。本机密钥还没换过去（计划刚写下就被打断）时什么都不做，等用户重新选。 */
    private fun finishKeySwap() {
        val plan = names.getString(KEY_SWAP, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: run {
            names.edit().remove(KEY_SWAP).apply()
            return
        }
        val kept = plan.optString("kept")
        val retired = plan.optString("retired")
        if (vault.vaultId != kept) {
            names.edit().remove(KEY_SWAP).apply()
            return
        }
        records.swapKeysOnce(plan.optString("t"), kept = kept, retired = retired)
        val holders = plan.optJSONArray("holders")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        val edit = names.edit()
        edit.putStringSet(KEY_RETIRED_KEYS, retiredKeys() + retired - kept - "")
        CloudProvider.entries.forEach { p ->
            val marker = names.getString(KEY_REKEY_ACCOUNT + p.name, null)
            when {
                // 刚解开的那几个账号：现在用的就是它们的密钥包
                marker == kept -> edit.remove(KEY_REKEY_ACCOUNT + p.name)
                // 原来放着当前密钥包的账号：之后要换成保留的这套
                accountTag(p) in holders -> edit.putString(KEY_REKEY_ACCOUNT + p.name, retired)
            }
            // 重新确认每个账号里的密钥包，按新的“当前”来记
            edit.remove(KEY_VAULT_UPLOADED_PREFIX + p.name).remove(KEY_REMOTE_CHECKED + p.name)
        }
        edit.putStringSet(KEY_VAULT_ACCOUNTS, emptySet()).remove(KEY_KEY_CHOICE).commit()
        // 计划确实从磁盘上删掉了才清进度标记：commit 失败时计划还在，进度标记也得留着，
        // 不然下次启动会把记录再换一遍（当前这套的记录被标成已经退役的那套，照片打不开）
        if (names.edit().remove(KEY_SWAP).commit()) records.dropMeta("swap:")
    }

    /** 启动时：上次换密钥做到一半就退出了，接着做完。 */
    private fun recoverKeySwap() {
        if (!names.contains(KEY_SWAP)) {
            // 计划已经删了、进度标记还在（上次删完计划后没来得及清）：现在清掉
            runCatching { records.dropMeta("swap:") }
            return
        }
        runCatching { finishKeySwap() }
        markIndexDirty()
    }

    /**
     * 没选上的旧密钥：用它加密的照片都换完了、放着它的密钥包的账号也都换成当前这套了，就从手机上删掉。
     * 在锁里调用。
     */
    /** 有换下来的旧密钥只等目录用当前密钥重新传上去才能删：这时不管上次什么时候传过，都要马上传一次。 */
    private fun oldKeyWaitsForIndex(): Boolean = vault.oldKeyIds().isNotEmpty() && names.getBoolean(KEY_INDEX_DIRTY, false)

    private fun cleanupOldKeys() {
        val choice = pendingKeyChoice()
        val all = records.entries()
        for (id in vault.oldKeyIds()) {
            // 目录还没用当前密钥重新传过时也先留着：网盘上那份目录可能还是用它加密的
            if (id == choice || all.any { it.keyId == id } || names.getBoolean(KEY_INDEX_DIRTY, false)) continue
            val holders = CloudProvider.entries.filter { names.getString(KEY_REKEY_ACCOUNT + it.name, null) == id }
            // 还有已登录的账号放着这套的密钥包没换掉：先留着
            if (holders.any { auth.isSignedIn(it) && !names.getBoolean(KEY_VAULT_UPLOADED_PREFIX + it.name, false) }) continue
            vault.dropOldKey(id)
            val edit = names.edit()
            holders.forEach { edit.remove(KEY_REKEY_ACCOUNT + it.name) }
            edit.apply()
        }
    }

    /**
     * 不恢复网盘里以前的加密备份，从头开始：不再提示解锁，照常备份。
     * 以前加密的照片还留在网盘里，但这台手机打不开它们；之后如果在这里开启加密，会用新的密钥（覆盖网盘里旧的密钥包）。
     */
    fun abandonRemoteVault() {
        scope.launch {
            exclusive {
                val edit = names.edit()
                remoteBundles().forEach { (p, b) ->
                    edit.putString(KEY_REMOTE_ABANDONED + p.name, runCatching { KeyBundle.parse(b).vaultId }.getOrDefault(""))
                        .remove(KEY_REMOTE_BUNDLE + p.name).remove(KEY_INDEX_LOCKED + p.name)
                }
                edit.apply()
                refreshUnlockState()
            }
            backupNow(manual = true)
        }
    }

    /**
     * 关闭加密时，某个账号再也不会连接了：不再等它。那个账号里的加密照片以后就打不开了（这台手机删掉密钥之后）。
     * [account] 是 [EncryptionState.waitingTags] 里的一项。
     */
    fun giveUpVaultAccount(account: String) {
        scope.launch {
            exclusive {
                // 按账号标识放弃；老界面传的是显示名时，也只放弃第一个同名的
                val tags = vaultAccounts()
                val tag = account.takeIf { it in tags } ?: tags.firstOrNull { t -> tagLabel(t) == account } ?: return@exclusive
                val left = tags - tag
                names.edit().putStringSet(KEY_VAULT_ACCOUNTS, left).apply()
                _encryption.update { st ->
                    val i = st.waitingTags.indexOf(tag)
                    if (i < 0) st else st.copy(waitingAccounts = st.waitingAccounts.filterIndexed { j, _ -> j != i }, waitingTags = st.waitingTags - tag)
                }
            }
            backupNow(manual = true)
        }
    }

    // ---------- 换恢复密钥、改密码 ----------

    /** 按 rev 从新到旧排（同一套密钥的几份里先试最新的）；读不出来的排最后。 */
    private fun newestFirst(bundles: Collection<String>): List<String> =
        bundles.distinct().sortedByDescending { b -> runCatching { KeyBundle.revOf(KeyBundle.parse(b)) }.getOrDefault(-1) }

    /** 读出一个账号网盘上的密钥包；没有时为 null。 */
    private suspend fun downloadBundle(p: CloudProvider): String? {
        val drive = drive(p)
        val id = retrying { drive.findFile(KEYBUNDLE_PATH) } ?: return null
        val out = java.io.ByteArrayOutputStream()
        retrying { out.reset(); drive.download(id, out) }
        return String(out.toByteArray())
    }

    /**
     * 已登录账号里和 [vaultId] 同一套的密钥包（按账号）。
     * [strict] 为 false 时读不到的账号跳过；为 true 时有一个账号读不到就抛 IOException（换密钥前要看到每个账号里最新的那份）。
     */
    private suspend fun sameVaultRemoteBundles(vaultId: String, strict: Boolean = false): Map<CloudProvider, String> {
        val out = LinkedHashMap<CloudProvider, String>()
        for (p in CloudProvider.entries.filter { auth.isSignedIn(it) && CloudConfig.of(it).configured && !wipeHeld(it) }) {
            val b = try {
                downloadBundle(p)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (strict) throw IOException(tr(R.string.e2ee_rewrap_cannot_read, accountLabel(p)), e)
                null
            }
            if (b != null && runCatching { KeyBundle.parse(b).vaultId }.getOrNull() == vaultId) out[p] = b
        }
        return out
    }

    /**
     * [remote] 里比本机新（rev 更大）、mac 也对得上的那份密钥包：有就换上，返回 true。在锁里调用。
     * 别的设备改了密码或换了恢复密钥以后，这台手机据此跟上。
     */
    private fun adoptNewest(remote: Collection<String>): Boolean {
        val local = vault.bundleJson ?: return false
        val key = runCatching { vault.wrapKey() }.getOrNull() ?: return false
        val mine = KeyBundle.parse(local)
        val best = remote.mapNotNull { b -> runCatching { KeyBundle.parse(b) }.getOrNull()?.let { it to b } }
            .filter { (f, _) -> f.vaultId == mine.vaultId && KeyBundle.revOf(f) > KeyBundle.revOf(mine) && KeyBundle.verify(f, key) }
            .maxByOrNull { KeyBundle.revOf(it.first) } ?: return false
        adoptBundleFromElsewhere(best.second)
        return true
    }

    private suspend fun adoptNewerRemoteBundle(): Boolean {
        val local = vault.bundleJson ?: return false
        return adoptNewest(sameVaultRemoteBundles(KeyBundle.parse(local).vaultId).values)
    }

    /**
     * 换上别的设备写的新版密钥包，并提醒用户（分开记密码变了、恢复密钥变了）。
     * 只有恢复密钥真的变了，这台手机上还没抄下的那个才作废、不再显示；只改了密码时它照样有效，继续显示。
     */
    private fun adoptBundleFromElsewhere(json: String) {
        val old = vault.bundleJson?.let { runCatching { KeyBundle.parse(it) }.getOrNull() }
        val new = KeyBundle.parse(json)
        fun slot(f: app.zyll.crypto.KeyBundleFile?, type: String) = f?.slots?.firstOrNull { it.type == type }
        val passwordChanged = slot(old, KeyBundle.SLOT_PASSWORD) != slot(new, KeyBundle.SLOT_PASSWORD)
        val recoveryChanged = slot(old, KeyBundle.SLOT_RECOVERY) != slot(new, KeyBundle.SLOT_RECOVERY)
        vault.replaceBundle(json)
        if (recoveryChanged) recoveryKeySaved()
        val edit = names.edit()
        if (passwordChanged) edit.putBoolean(KEY_BUNDLE_CHANGED, true)
        if (recoveryChanged) edit.putBoolean(KEY_BUNDLE_CHANGED_RK, true)
        edit.apply()
        refreshUnlockState()
    }

    /** 用户看过“密码或恢复密钥在别的设备上改过”的提醒了。 */
    fun dismissKeyChangedNotice() {
        names.edit().remove(KEY_BUNDLE_CHANGED).remove(KEY_BUNDLE_CHANGED_RK).apply()
        refreshUnlockState()
    }

    /** 改了密码或换了恢复密钥以后，还没传上新密钥包的账号（还登录着的）。 */
    private fun keysUnsynced(): List<CloudProvider> {
        val pending = names.getStringSet(KEY_BUNDLE_PENDING, emptySet())!!
        if (pending.isEmpty()) return emptyList()
        return CloudProvider.entries.filter { auth.isSignedIn(it) && CloudConfig.of(it).configured && accountTag(it) in pending }
    }

    /** 这个账号里已经是本机这份（或更新的）密钥包了。 */
    private fun bundleSynced(p: CloudProvider) {
        val pending = names.getStringSet(KEY_BUNDLE_PENDING, emptySet())!!
        val tag = accountTag(p)
        if (tag !in pending) return
        names.edit().putStringSet(KEY_BUNDLE_PENDING, pending - tag).apply()
        refreshUnlockState()
    }

    /** 安排一次补传新密钥包：联网就跑，失败了隔一段再试（和自动备份开没开无关）。 */
    private fun scheduleKeySync() {
        val request = OneTimeWorkRequestBuilder<KeySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(KEY_SYNC, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 密钥相关的收尾（[KeySyncWorker] 调用，只要有网就跑，不受备份的“仅 Wi‑Fi / 仅充电”限制）：
     * 补传新密钥包；换密钥后，把目录用当前密钥重新传上去，再删掉换下来的旧密钥。
     * 用旧密钥加密的照片要逐张换成新密钥（是真正的上传），那部分仍然交给备份按条件做。
     * 还有能做的没做成时返回 false，稍后重试。
     */
    internal suspend fun syncKeyBundle(): Boolean {
        val oldWaiting = vault.oldKeyIds().isNotEmpty()
        if ((keysUnsynced().isEmpty() && !oldWaiting) || wipeUnfinished || vault.bundleJson == null) return true
        return lock.withLock {
            val key = runCatching { vault.wrapKey() }.getOrNull() ?: return@withLock true
            // 还没就绪的账号（刚登录、等着解锁或处理目录）交给下一轮备份，这里只补传就绪的
            val ready = keysUnsynced().filter { isReady(it) && storageUsable(it) == StorageUse.Ok }.toSet()
            if (ready.isNotEmpty()) runCatching { uploadKeyBundle(ready) }
            var indexOk = true
            if (oldWaiting) {
                if (names.getBoolean(KEY_INDEX_DIRTY, false)) {
                    // 和备份一样先看存储位置：别的设备搬走了就先跟过去，读不了的账号这次不写，免得把目录写回旧位置
                    val connected = CloudProvider.entries.filter { p ->
                        auth.isSignedIn(p) && CloudConfig.of(p).configured && _drives.value.none { it.provider == p && it.paused } &&
                            storageUsable(p) != StorageUse.No
                    }.toSet()
                    if (connected.isNotEmpty()) runCatching { uploadIndex(connected, vault.wantEncrypted, key, force = true) }
                    indexOk = !names.getBoolean(KEY_INDEX_DIRTY, false)
                }
                runCatching { cleanupOldKeys() }
            }
            keysUnsynced().none { isReady(it) } && indexOk
        }
    }

    /** 核对密码；本机的密钥包对不上时先看网盘上有没有更新的（别的设备改过密码），换上后再核对一次。在锁里调用。 */
    private suspend fun checkPasswordFresh(password: String) {
        try {
            vault.checkPassword(password)
        } catch (e: Exception) {
            if (!adoptNewerRemoteBundle()) throw e
            vault.checkPassword(password)
        }
    }

    /**
     * 换新的恢复密钥（[newPassword] 为 null），或者改密码（[newPassword] 是新密码）。
     * [secret] 用来解开主密钥：现在的密码，或者（[withRecoveryKey] 为 true 时）现在的恢复密钥。
     * 只是用新的密码或恢复密钥把同一把主密钥重新包一次、更新本机和网盘上的密钥包，照片一张都不用动。
     * 旧的那个随即失效（别的设备在下次核对密钥包时跟上，见 [uploadKeyBundle]）。
     * 换恢复密钥时新的恢复密钥放进 [pendingRecoveryKey]，在主界面上弹出让用户抄下。
     * 返回是否已经传到了每个放着这套密钥的账号；没传完时界面提示，联网后自动补传。
     * 有账号读不到（断网、要重新连接）时不改，抛 IOException。
     */
    suspend fun rewrapKeys(secret: String, withRecoveryKey: Boolean, newPassword: String?) = withContext(Dispatchers.Default) {
        exclusive {
            val enc = _encryption.value
            if (wipeUnfinished) throw IllegalStateException(tr(R.string.wipe_finish_first))
            if (vault.bundleJson == null || !vault.wantEncrypted) throw IllegalStateException(tr(R.string.e2ee_never_enabled))
            if (enc.keyBroken) throw IllegalStateException(tr(R.string.e2ee_local_key_broken))
            if (enc.keyChoice) throw IllegalStateException(tr(R.string.e2ee_rewrap_finish_choice))
            newPassword?.let { pw -> PasswordPolicy.problem(pw)?.let { throw IllegalArgumentException(it) } }
            if (newPassword != null && !withRecoveryKey && newPassword == secret) throw IllegalArgumentException(tr(R.string.e2ee_password_same))
            val key = vault.wrapKey() ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
            // 别的设备可能已经改过：每个账号里的都要读到（读不到就停下，免得悄悄盖掉那边刚改的），先换上最新的那份，再拿它核对
            val remote = sameVaultRemoteBundles(KeyBundle.parse(vault.bundleJson!!).vaultId, strict = true)
            adoptNewest(remote.values)
            val local = vault.bundleJson ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
            val wrong = if (withRecoveryKey) tr(R.string.e2ee_wrong_recovery_key) else tr(R.string.e2ee_wrong_password)
            val unlocked = try {
                if (withRecoveryKey) KeyBundle.unlockWithRecoveryKey(local, secret.trim()) else KeyBundle.unlockWithPassword(local, secret)
            } catch (e: Exception) {
                throw IllegalArgumentException(wrong, e)
            }
            try {
                // 解开的必须就是这台手机正在用的那把主密钥
                if (!java.security.MessageDigest.isEqual(unlocked.wrapKey, key)) throw IllegalArgumentException(wrong)
                val recovery = if (newPassword == null) KeyBundle.newRecoveryKey() else null
                // rev 比读到的每一份都大：别的设备时钟快、写的 rev 更大时，也不会在下次核对时被那份换掉
                val minRev = remote.values.maxOfOrNull { runCatching { KeyBundle.revOf(KeyBundle.parse(it)) }.getOrDefault(0) } ?: 0
                val json = KeyBundle.rewrap(local, unlocked, newPassword = newPassword, newRecoveryKey = recovery, minRev = minRev)
                val display = recovery?.let { RecoveryKey.format(it) }
                recovery?.fill(0)
                vault.replaceBundle(json)
                // 网盘里放着这套密钥的账号都还是旧的那份，传上去之前在加密页提示（见 [keysUnsynced]）
                names.edit().remove(KEY_BUNDLE_CHANGED).remove(KEY_BUNDLE_CHANGED_RK)
                    .putStringSet(KEY_BUNDLE_PENDING, remote.keys.map { accountTag(it) }.toSet()).commit()
                display?.let { showRecoveryKey(it) }
            } finally {
                unlocked.masterKey.fill(0)
            }
            // 网盘上的马上换掉；这次没传上的账号联网后自动补传（[scheduleKeySync]），备份时也会传（见 [uploadKeyBundle]）
            val edit = names.edit()
            CloudProvider.entries.forEach { edit.remove(KEY_BUNDLE_CHECKED_AT + it.name) }
            edit.apply()
            refreshUnlockState()
            val ready = CloudProvider.entries.filter { auth.isSignedIn(it) && CloudConfig.of(it).configured && isReady(it) }.toSet()
            if (ready.isNotEmpty()) runCatching { uploadKeyBundle(ready) }
            if (keysUnsynced().isNotEmpty()) scheduleKeySync()
            keysUnsynced().isEmpty()
        }
    }

    /** 已经开了加密、可以换恢复密钥或改密码（不在解锁、修复或选密钥的过程中）。 */
    val canRewrapKeys: Boolean get() = vault.bundleJson != null && vault.wantEncrypted

    /** 关闭加密：核对密码后，把所有加密备份换回普通文件。密码不对会抛异常。 */
    suspend fun disableEncryption(password: String) = withContext(Dispatchers.Default) {
        exclusive {
            checkPasswordFresh(password)
            vault.wantEncrypted = false
            names.edit().putLong(KEY_MODE_AT, System.currentTimeMillis()).apply()
            markIndexDirty()
            reloadRecords()
        }
        backupNow(manual = true)
    }

    // ---------- 删除全部备份数据 ----------

    /**
     * 删除前给用户看的概况。[photos] 是原图和视频的份数（不算缩略图、目录、密钥包这些）。
     * [cloudOnly] 是手机上已经没有、只剩网盘里那一份的照片张数（释放空间或本机删掉的），删了就彻底没了。
     */
    data class WipeSummary(val accounts: List<String>, val files: Int, val photos: Int, val bytes: Long, val hasOneDrive: Boolean, val cloudOnly: Int)

    /**
     * 删除全部备份数据的进度。[error] 不为空表示中途失败，可以再点一次接着删。
     * [skipped] 是没有权限删、留在网盘里的文件数（做完时告诉用户）。
     */
    data class WipeState(
        val running: Boolean = false, val done: Int = 0, val total: Int = 0, val error: String? = null, val finished: Boolean = false, val skipped: Int = 0,
    )

    /** 在别的设备上删除过全部备份的账号（这台手机已经停止往里写）。[notice] 为 true 时还没提醒过用户。 */
    data class WipedElsewhere(val accounts: List<String> = emptyList(), val notice: Boolean = false)

    private val _wipe = MutableStateFlow(WipeState())
    val wipe: StateFlow<WipeState> = _wipe.asStateFlow()

    /** 上次删到一半（断网、App 被关掉）还没删完的账号。不为空时备份暂停，不往网盘写任何东西。 */
    private fun wipeLeft(): Set<String> = names.getStringSet(KEY_WIPE, emptySet())!!.toSet()

    /** 删除全部备份数据还没做完。 */
    val wipeUnfinished: Boolean get() = wipeLeft().isNotEmpty()

    fun clearWipeResult() { _wipe.update { if (it.running) it else WipeState() } }

    private val _wipedElsewhere = MutableStateFlow(computeWipedElsewhere())
    val wipedElsewhere: StateFlow<WipedElsewhere> = _wipedElsewhere.asStateFlow()

    private fun wipeHeldTags(): Set<String> = names.getStringSet(KEY_WIPE_HOLD, emptySet())!!.toSet()

    /** 这个账号的备份在别的设备上删除过，用户还没在这台手机上重新开始。 */
    private fun wipeHeld(p: CloudProvider): Boolean = wipeHeldTags().let { it.isNotEmpty() && accountTag(p) in it }

    private fun computeWipedElsewhere(): WipedElsewhere {
        val held = CloudProvider.entries.filter { auth.isSignedIn(it) && wipeHeld(it) }.map { accountLabel(it) }
        return WipedElsewhere(held, held.isNotEmpty() && names.getBoolean(KEY_WIPED_NOTICE, false))
    }

    private fun refreshWipeHeld() {
        _wipedElsewhere.value = computeWipedElsewhere()
    }

    /** 用户看过“在别的设备上删除过”的提醒了（账号照样停着，备份页上可以重新开始）。 */
    fun dismissWipedNotice() {
        names.edit().remove(KEY_WIPED_NOTICE).apply()
        refreshWipeHeld()
    }

    /** 在这台手机上重新往那些账号备份：当作空账号从头开始。 */
    fun resumeAfterWipe() {
        names.edit().remove(KEY_WIPE_HOLD).remove(KEY_WIPED_NOTICE).apply()
        refreshWipeHeld()
        if (_settings.value.enabled) backupNow(manual = true)
    }

    /** 删除全部备份数据还没做完、或者这个账号在别的设备上删除过时，不往网盘写，告诉用户先去处理。 */
    private fun checkWritable(p: CloudProvider) {
        if (wipeUnfinished || _wipe.value.running) throw IllegalStateException(tr(R.string.wipe_finish_first))
        if (wipeHeld(p)) throw IllegalStateException(tr(R.string.wipe_held_blocked, accountLabel(p)))
    }

    /** 读一个账号的“已清空”标记（vault/wiped.json）：返回 (时间, 写它的设备)；没有或格式不对时为 null。 */
    private suspend fun readWipedMarker(drive: CloudDrive): Pair<Long, String>? {
        val id = retrying { drive.findFile(WIPED_PATH) } ?: return null
        val out = java.io.ByteArrayOutputStream()
        retrying { out.reset(); drive.download(id, out) }
        val o = runCatching { JSONObject(String(out.toByteArray())) }.getOrNull() ?: return null
        if (o.optString("format") != "zyll-wiped") return null
        val at = o.optLong("at", 0)
        return if (at > 0) at to o.optString("device") else null
    }

    private val wipeCheckedAt = java.util.concurrent.ConcurrentHashMap<CloudProvider, Long>()
    @Volatile private var wipeRecheckAt = 0L

    /**
     * 别的设备在这台手机上次看过之后，把这个账号的备份全部删除过（见 spec/wipe.md）：
     * 停止往这个账号写、清掉本机这个账号的记录（[onWipedElsewhere]），返回 true。已经停着的账号直接返回 true。
     * [maxAgeMs] 之内看过的不再读。读不到时抛 IOException。在锁里调用。
     */
    private suspend fun wipedElsewhere(p: CloudProvider, maxAgeMs: Long = 0): Boolean {
        if (wipeHeld(p)) return true
        val now = System.currentTimeMillis()
        if (maxAgeMs > 0 && now - (wipeCheckedAt[p] ?: 0) < maxAgeMs) return false
        val m = readWipedMarker(drive(p))
        wipeCheckedAt[p] = now
        if (m == null) return false
        val (at, device) = m
        val seenKey = KEY_WIPE_SEEN + accountTag(p)
        val seen = names.getLong(seenKey, -1)
        if (at <= seen) return false
        // 自己写的；或者这台手机从没往这个账号传过东西（刚连上，或者删除以后才开始用）：记下见过，照常
        val fresh = seen < 0 && records.entries().none { it.provider == p } && accountTag(p) !in vaultAccounts()
        if (device == deviceId || fresh) {
            names.edit().putLong(seenKey, at).apply()
            return false
        }
        onWipedElsewhere(p, at)
        return true
    }

    /**
     * 别的设备删除了这个账号的全部备份：这台手机在删除前后不久传上去的文件（那边列文件时还没有、没删到的）删掉，
     * 再清掉本机这个账号的记录和缓存，停止往这个账号写，等用户在这台手机上重新开始（[resumeAfterWipe]）。
     * 已经没有别的账号可备份时，本机的密钥和共享相册也清掉、关掉自动备份，和那台设备删除后一样（旧密码从此作废）。
     */
    private suspend fun onWipedElsewhere(p: CloudProvider, at: Long) {
        android.util.Log.w("ZyllBackup", "backup in ${p.name} was wiped on another device")
        val drive = drive(p)
        records.entries().filter { it.provider == p && it.uploadedAt >= at - WIPE_RACE_MS }
            .flatMap { listOf(it.remoteId, it.thumb) }.filter { it.isNotEmpty() }.distinct()
            .forEach { id -> runCatching { retrying { drive.delete(id) } } }
        val tag = accountTag(p)
        clearAccountCaches(p)
        clearAccountState(p)
        names.edit()
            .putLong(KEY_WIPE_SEEN + tag, at)
            .putStringSet(KEY_WIPE_HOLD, wipeHeldTags() + tag)
            .putStringSet(KEY_VAULT_ACCOUNTS, vaultAccounts() - tag)
            .putBoolean(KEY_WIPED_NOTICE, true)
            .commit()
        val left = CloudProvider.entries.filter { auth.isSignedIn(it) && CloudConfig.of(it).configured && !wipeHeld(it) }
        if (left.isEmpty()) {
            if (_settings.value.enabled) update { it.copy(enabled = false) }
            forgetLocalBackupData()
        }
        refreshWipeHeld()
        reloadRecords()
    }

    /** 要删的账号：所有已登录的（包括超出限额暂停的）。 */
    private fun wipeAccounts(): List<CloudProvider> = CloudProvider.entries.filter { auth.isSignedIn(it) && CloudConfig.of(it).configured }

    /** 现在不能删的原因；能删时为 null。接着上次没删完的（[wipeUnfinished]）时不检查“有没有东西可删”。 */
    private fun wipeBlocker(): String? {
        val reconnect = CloudProvider.entries.filter { auth.needsReconnect(it) }
        if (reconnect.isNotEmpty()) return tr(R.string.wipe_blocked_reconnect, reconnect.joinToString(tr(R.string.list_separator)) { accountLabel(it) })
        val moving = googleAccounts().filter { storage.moving(it) != null }
        if (moving.isNotEmpty()) return tr(R.string.wipe_blocked_moving, moving.joinToString(tr(R.string.list_separator)) { accountLabel(it) })
        if (!wipeUnfinished && wipeAccounts().isEmpty() && vault.bundleJson == null && records.entries().isEmpty()) return tr(R.string.wipe_blocked_nothing)
        return null
    }

    /**
     * 一个账号里 Zyll 能看到的全部文件（文件夹不算）。Google 的库现在在可见文件夹时，隐藏区里留下的也算，
     * 但隐藏区的 storage.json 标记留着：它告诉别的设备去可见文件夹找。
     * Google 可见文件夹里只列“Zyll 相册”文件夹下面的（[GoogleDriveApi.listTree]），不会碰到别人共享给你的文件。
     */
    private suspend fun wipeFiles(p: CloudProvider): List<Pair<CloudDrive, RemoteFile>> = when (p.kind) {
        DriveKind.OneDrive -> {
            val d = drive(p)
            // “已清空”标记留着（见 [writeWipedMarker]）
            retrying { d.listAll() }.filter { it.name != WIPED_NAME }.map { d to it }
        }
        DriveKind.GoogleDrive -> {
            val cur = storage.space(p)
            val d = GoogleDriveApi(auth, app, p) { cur }
            val out = retrying { d.listTree() }.filter { it.first != WIPED_PATH }.map { d as CloudDrive to it.second }.toMutableList()
            if (cur == DriveSpace.Visible) {
                val h = GoogleDriveApi(auth, app, p) { DriveSpace.Hidden }
                out += retrying { h.listTree() }.filter { it.first != StorageMarker.PATH }.map { h to it.second }
            } else if (storage.cleanup(p) == DriveSpace.Visible) {
                // 从可见文件夹切回了隐藏区、那边还有没搬回来的：一起删（共享相册的文件夹不算，它们按共享相册的规则处理）
                val v = GoogleDriveApi(auth, app, p) { DriveSpace.Visible }
                out += retrying { v.listTree() }.filter { it.first != StorageMarker.PATH && !isSharedPath(it.first) }.map { v to it.second }
            }
            out
        }
    }

    /** 手机上已经没有的备份照片张数（同一张在几个账号里只算一次）。 */
    private fun cloudOnlyCount(): Int {
        val all = records.entries()
        val present = existingMediaIds(all.map { it.mediaId }.filter { it > 0 }.distinct())
        return all.groupBy { it.hash.ifEmpty { "${it.provider.name}/${it.remoteId}" } }.values
            .count { group -> group.none { it.mediaId > 0 && it.mediaId in present } }
    }

    /** 原图和视频（不是缩略图、目录、密钥包、标记这些小文件）。 */
    private fun isPhotoFile(f: RemoteFile): Boolean {
        val n = f.name
        return !(n in FIXED_VAULT_FILES || n.startsWith("index") || n == "storage.json" || n.startsWith("t-") || THUMB_NAME.matches(n))
    }

    /** 删的顺序：先删原图和缩略图，再删目录，最后删密钥包和标记。中途停下时剩下的加密文件仍然能用密钥包解开。 */
    private fun wipeRank(f: RemoteFile): Int = when {
        f.name == "storage.json" -> 4
        f.name == "keybundle.json" -> 3
        f.name in FIXED_VAULT_FILES -> 2
        f.name.startsWith("index") -> 1
        else -> 0
    }

    /** 列出所有已登录账号里要删的文件，给确认框显示。读不到某个账号时抛 IOException。 */
    suspend fun scanBackupData(): WipeSummary = withContext(Dispatchers.IO) {
        wipeBlocker()?.let { throw IllegalStateException(it) }
        val accounts = wipeAccounts()
        var files = 0
        var photos = 0
        var bytes = 0L
        for (p in accounts) {
            val list = try {
                wipeFiles(p)
            } catch (e: IOException) {
                throw IOException(tr(R.string.wipe_scan_failed, accountLabel(p)), e)
            }
            files += list.size
            photos += list.count { isPhotoFile(it.second) }
            bytes += list.sumOf { it.second.size.coerceAtLeast(0) }
        }
        WipeSummary(accounts.map { accountLabel(it) }, files, photos, bytes, accounts.any { it.kind == DriveKind.OneDrive }, cloudOnlyCount())
    }

    /**
     * 删除全部备份数据：所有已登录网盘账号里 Zyll 的文件（照片、缩略图、备份目录、密钥包、共享相册、AI 整理结果）全部删掉，
     * 本机的备份记录、加密密钥、共享相册列表也清掉，自动备份关掉。手机里的照片不动，网盘账号保持登录。
     * 在后台跑，离开页面也会做完，进度见 [wipe]。中途失败时记着还剩哪些账号，再调用一次接着删；删完之前备份一直暂停。
     */
    fun deleteAllBackupData() {
        if (_wipe.value.running) return
        _wipe.value = WipeState(running = true)
        scope.launch {
            try {
                exclusive { wipeLocked() }
                _wipe.update { WipeState(finished = true, skipped = it.skipped) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _wipe.value = WipeState()
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ZyllBackup", "wipe failed", e)
                _wipe.update { WipeState(done = it.done, total = it.total, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** 在账号里写下“已清空”标记：别的设备（新版本）看到后停止往这个账号写，见 spec/wipe.md。删完以后也留着。 */
    private suspend fun writeWipedMarker(p: CloudProvider, at: Long) {
        val bytes = JSONObject().put("format", "zyll-wiped").put("version", 1).put("at", at).put("device", deviceId).toString().toByteArray()
        val d = drive(p)
        retrying { d.upload(UploadFile(WIPED_PATH, "application/json", bytes.size.toLong(), System.currentTimeMillis(), replace = true) { bytes.inputStream() }) }
    }

    private suspend fun wipeLocked() {
        wipeBlocker()?.let { throw IllegalStateException(it) }
        // 先关掉自动备份、记下要删哪些账号（commit 写到磁盘）：中途断网或者 App 被关掉，下次接着删，删完之前不再往网盘写
        if (wipeLeft().isEmpty()) {
            // 标记时间要比网盘上已有的（以前删除过一次）新：这台手机时钟慢时，别的设备也不会以为这次已经看过
            var start = System.currentTimeMillis()
            for (p in wipeAccounts()) {
                val old = try {
                    readWipedMarker(drive(p))
                } catch (e: IOException) {
                    throw IOException(tr(R.string.wipe_scan_failed, accountLabel(p)), e)
                }
                if (old != null) start = maxOf(start, old.first + 1)
            }
            if (!names.edit().putStringSet(KEY_WIPE, wipeAccounts().map { it.name }.toSet() + WIPE_LOCAL).putLong(KEY_WIPE_AT, start).commit()) {
                throw IOException(tr(R.string.e2ee_save_key_failed))
            }
        }
        val at = names.getLong(KEY_WIPE_AT, 0).takeIf { it > 0 } ?: System.currentTimeMillis().also { names.edit().putLong(KEY_WIPE_AT, it).commit() }
        if (_settings.value.enabled) update { it.copy(enabled = false) }
        names.edit().remove(KEY_FORCED).apply()
        // 共享相册排着的推送取消掉、正在推的等它停下：之后它看到 writesBlocked 就不再写
        sharedAlbums.pauseWrites()
        // 已经断开的账号不用再删（断开时本机的记录也清掉了）
        val todo = wipeLeft().mapNotNull { CloudProvider.fromName(it) }.filter { auth.isSignedIn(it) && CloudConfig.of(it).configured }
        // 先在每个账号写下“已清空”标记，再列文件：别的设备看到标记就停手，不会边删边往里传
        for (p in todo) {
            try {
                writeWipedMarker(p, at)
            } catch (e: IOException) {
                throw IOException(tr(R.string.wipe_delete_failed, accountLabel(p)), e)
            }
        }
        val lists = todo.associateWith { p ->
            try {
                wipeFiles(p)
            } catch (e: IOException) {
                throw IOException(tr(R.string.wipe_scan_failed, accountLabel(p)), e)
            }
        }
        val total = lists.values.sumOf { it.size }
        _wipe.update { it.copy(running = true, done = 0, total = total, skipped = 0) }
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val skipped = java.util.concurrent.atomic.AtomicInteger(0)
        suspend fun deleteAll(p: CloudProvider, files: List<Pair<CloudDrive, RemoteFile>>) {
            for (group in files.groupBy { wipeRank(it.second) }.toSortedMap().values) {
                // 一次删 4 个，几千个文件也不用等太久；网盘限流时 retrying 会自己等
                for (chunk in group.chunked(WIPE_PARALLEL)) {
                    kotlinx.coroutines.coroutineScope {
                        chunk.map { (d, f) ->
                            async {
                                try {
                                    retrying { d.delete(f.id) }
                                } catch (e: HttpException) {
                                    // 没有权限删的（别人放进你共享相册里的文件）跳过，做完时告诉用户还剩几个
                                    if (e.code != 403 || e.rateLimited) throw IOException(tr(R.string.wipe_delete_failed, accountLabel(p)), e)
                                    skipped.incrementAndGet()
                                } catch (e: IOException) {
                                    throw IOException(tr(R.string.wipe_delete_failed, accountLabel(p)), e)
                                }
                                val n = done.incrementAndGet()
                                _wipe.update { it.copy(done = n, skipped = skipped.get()) }
                            }
                        }.forEach { it.await() }
                    }
                }
            }
        }
        for ((p, files) in lists) {
            deleteAll(p, files)
            // 再列一遍：标记写下之前就开始的上传（旧版本的设备、刚好在传的）可能又放进来了几个
            val listed = files.mapTo(HashSet()) { it.second.id }
            val late = try {
                wipeFiles(p).filter { (_, f) -> f.id !in listed }
            } catch (e: IOException) {
                throw IOException(tr(R.string.wipe_scan_failed, accountLabel(p)), e)
            }
            if (late.isNotEmpty()) {
                _wipe.update { it.copy(total = it.total + late.size) }
                deleteAll(p, late)
            }
            // 这个账号删完了：本机按账号记的记录、缓存和标记也清掉，下次备份时当作一个空账号重新检查
            clearAccountCaches(p)
            clearAccountState(p)
            // 旧位置也删干净了，不用再搬
            synchronized(storageLock) {
                storage.setCleanup(p, null)
                storage.setLeftover(p, 0)
            }
            names.edit().putStringSet(KEY_WIPE, wipeLeft() - p.name).putLong(KEY_WIPE_SEEN + accountTag(p), at).commit()
            reloadRecords()
        }
        // 已经断开、不用删的账号也从列表里去掉
        names.edit().putStringSet(KEY_WIPE, wipeLeft().filter { it == WIPE_LOCAL }.toSet()).commit()
        // 所有账号都删完了，最后才删本机的密钥：中途失败时，剩下的加密照片还能解开
        forgetLocalBackupData()
        names.edit().remove(KEY_WIPE).remove(KEY_WIPE_AT).remove(KEY_WIPE_HOLD).remove(KEY_WIPED_NOTICE).commit()
        refreshWipeHeld()
        _wipe.update { it.copy(skipped = skipped.get()) }
    }

    /** 清掉本机的密钥、共享相册列表和全局的备份标记（每个账号的记录已经清过）。 */
    private fun forgetLocalBackupData() {
        names.edit().apply { names.all.keys.filter { it.startsWith(KEY_VAULT_REV) }.forEach { remove(it) } }.commit()
        sharedAlbums.forgetAll()
        shareLinks.clearLocal()
        vault.forget()
        _pendingRecoveryKey.value = null
        records.dropMeta("swap:")
        names.edit()
            .remove(KEY_VAULT_ACCOUNTS).remove(KEY_RETIRED_KEYS).remove(KEY_KEY_CHOICE).remove(KEY_SWAP).remove(KEY_MODE_AT)
            .remove(KEY_INDEX_DIRTY).remove(KEY_INDEX_PENDING).remove(KEY_REMOTE_SETTINGS).remove(KEY_SETTINGS_DECIDED)
            .remove(KEY_BUNDLE_CHANGED).remove(KEY_BUNDLE_CHANGED_RK).remove(KEY_BUNDLE_PENDING).remove(KEY_BROKEN).remove(KEY_FORCED)
            .commit()
        refreshUnlockState()
        refreshIndexIssues()
        scope.launch { reloadRecords() }
        notifyPicker()
    }

    // ---------- 后台任务 ----------

    /** 按当前设置安排（或取消）定期备份和“有新照片就备份”。 */
    /** 用户没同意使用条款（或点了不同意）：停掉定时备份和“有新照片就备份”，不在后台处理数据。 */
    fun stopBackgroundWork() {
        // 只停备份相关的（定时、新照片触发、补跑、手动选的）；密钥同步、外链到期撤销保留，它们不处理照片，撤销外链还是保护隐私的
        val wm = WorkManager.getInstance(app)
        listOf(PERIODIC, NEW_MEDIA, NOW, FORCED_WORK).forEach { wm.cancelUniqueWork(it) }
    }

    fun schedule() {
        sweepShareLinksIfDue()
        val wm = WorkManager.getInstance(app)
        val s = _settings.value
        if (!s.enabled || CloudProvider.entries.none { auth.isSignedIn(it) }) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(NEW_MEDIA)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints(s, background = true))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        armNewMediaTrigger(ExistingWorkPolicy.REPLACE)
    }

    /** 系统媒体库有变化时唤醒一次（App 没开着也行）。 */
    internal fun armNewMediaTrigger(policy: ExistingWorkPolicy) {
        val c = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            // 合并一段时间内的变化再唤醒一次（连拍、截图、别的 App 批量存图都只算一次）
            .setTriggerContentUpdateDelay(1, TimeUnit.MINUTES)
            .setTriggerContentMaxDelay(10, TimeUnit.MINUTES)
            .build()
        val request = OneTimeWorkRequestBuilder<NewMediaWorker>().setConstraints(c).build()
        WorkManager.getInstance(app).enqueueUniqueWork(NEW_MEDIA, policy, request)
    }

    /** 立即跑一轮（用户点了“立即备份”、有新照片、改了设置）。 */
    @Volatile private var rerun = false

    /** 跑的过程中又有新照片：这一轮结束后接着再跑一轮。 */
    internal fun consumeRerun(): Boolean = rerun.also { rerun = false }

    /** 用户手动选的照片来了：正在跑的这一轮传完手上这张就停，下一轮先传它们。 */
    @Volatile private var urgent = false

    /**
     * 立即跑一轮。[manual] 表示是用户点的：马上开始（加急），不满足 Wi‑Fi 条件时直接告诉用户。
     * 正在跑时只做标记，这一轮结束后接着再跑，不会打断正在上传的文件。
     */
    fun backupNow(manual: Boolean = false) {
        val s = _settings.value
        if (_status.value.running) {
            rerun = true
            if (manual) urgent = true
            return
        }
        enqueueNow(s, manual)
    }

    private fun enqueueNow(s: BackupSettings, manual: Boolean) {
        if (manual) {
            names.edit().putBoolean(KEY_MANUAL_QUEUED, true).apply()
            val hint = when {
                !isOnline() -> tr(R.string.backup_offline)
                s.wifiOnly && isMetered() -> tr(R.string.backup_wifi_wait)
                else -> null
            }
            _status.update { it.copy(message = hint, manual = true) }
        }
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints(s, background = false))
            .setInputData(androidx.work.workDataOf(BackupWorker.KEY_MANUAL to manual))
            .apply { if (manual) setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        // 手动点的：之前那一轮如果失败了在“等待重试”，用新的替换掉，不然这次点击会被直接忽略。
        // 自动触发的：已经有任务在排队（可能是用户点的）就不动它
        WorkManager.getInstance(app).enqueueUniqueWork(NOW, if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** 设置改了、或者传的过程中条件没了（比如连上了流量）：这一轮停下以后，按现在的设置重新排一轮。 */
    @Volatile private var requeue = false

    internal fun pendingRequeue(): Boolean = requeue

    /** 后台任务结束时调用：需要的话按现在的设置和条件重新排一轮（替换掉排着的那份）。 */
    internal fun requeueIfNeeded(fromWorker: Boolean = true) {
        if (!requeue) return
        requeue = false
        rerun = false
        val s = _settings.value
        // 用户点的“立即备份”还排着没开始（比如在等网络）时改了设置：照样按“立即”重排，不然那次点击就没了
        if (!fromWorker && names.getBoolean(KEY_MANUAL_QUEUED, false)) {
            enqueueNow(s, manual = true)
            return
        }
        if (s.enabled || names.getStringSet(KEY_FORCED, emptySet())!!.isNotEmpty()) {
            // 条件都写进任务（含“仅充电”）：系统等条件满足了才启动，不会一启动又因为条件不满足停下、再排、再启动，空转耗电。
            // 系统判断的“不计流量”和 App 读到的偶尔不一致（比如开着 VPN）：任务刚因为“在用流量”停下时隔十分钟再看，
            // 免得系统一直启动、App 一直停；用户改设置时不等
            val c = Constraints.Builder()
                .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(s.chargingOnly)
                .build()
            val request = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(c)
                .apply { if (fromWorker && s.wifiOnly && isMetered()) setInitialDelay(10, java.util.concurrent.TimeUnit.MINUTES) }
                .build()
            // 排着的那一轮被换成了自动的
            names.edit().remove(KEY_MANUAL_QUEUED).apply()
            WorkManager.getInstance(app).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }

    /**
     * 现在满不满足自动备份的条件：设了仅 Wi‑Fi 时不能是计流量的网络；设了仅充电时要在充电（[manual] 是用户点的“立即备份”，不看充电）。
     * 只读系统的网络和电池状态，不耗电、不费流量，每传一张都可以查一次。
     */
    private fun conditionsMet(s: BackupSettings, manual: Boolean): Boolean =
        !(s.wifiOnly && isMetered()) && (manual || !s.chargingOnly || isCharging())

    /** 离开 App 时：还没传的备份目录补传一次。 */
    fun flushIndex() {
        if (!names.getBoolean(KEY_INDEX_DIRTY, false)) return
        // 设了仅 Wi‑Fi 时不用流量传，留给下一轮备份
        if (_settings.value.wifiOnly && isMetered()) return
        scope.launch { uploadIndexNow() }
    }

    /** 立刻把备份目录传上去（备份任务结束时、离开 App 时）。旧手机突然丢了，新手机也能看到最近备份的照片。 */
    internal suspend fun uploadIndexNow() {
        if (!names.getBoolean(KEY_INDEX_DIRTY, false) || wipeUnfinished) return
        runCatching {
            lock.withLock {
                val connected = _drives.value.filter { it.signedIn && it.account != null && !it.paused }.map { it.provider }.toSet()
                if (connected.isEmpty()) return@withLock
                uploadIndex(connected, vault.wantEncrypted, runCatching { vault.wrapKey() }.getOrNull(), force = true)
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 现在用的网络是不是计流量的（手机流量、热点）。界面在手动备份大文件前用来提示。 */
    fun isMetered(): Boolean =
        app.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    /** 系统开着省电模式。 */
    internal fun isPowerSave(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true

    private fun constraints(s: BackupSettings, background: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(background && s.chargingOnly)
        .setRequiresBatteryNotLow(background)
        .build()

    /**
     * 跑一轮：先把形式不对的旧备份转换掉（加密 ↔ 不加密），再上传新照片。
     * 返回 false 表示网络等原因没跑完，稍后重试。[progress] 用来更新通知栏。
     */
    internal suspend fun runBackup(isStopped: () -> Boolean, manual: Boolean = false, picked: Boolean = false, progress: suspend (BackupStatus) -> Unit): Boolean {
        // 排着的“立即备份”开始跑了（手动选照片的任务是另一个，不算）
        if (manual && !picked) names.edit().remove(KEY_MANUAL_QUEUED).apply()
        val result = runBackupLocked(isStopped, manual, picked, progress)
        // AI 整理结果的同步要传十几 MB，放在锁外面做：期间删除、恢复、改信息不用等它
        aiSyncPending.getAndSet(null)?.let { job -> if (!isStopped()) runCatching { syncAiResults(job) } }
        return result
    }

    /** 一轮备份结束时记下的 AI 结果同步任务（备份记录的快照），锁外执行。 */
    private class AiSyncJob(val connected: Set<CloudProvider>, val key: ByteArray, val records: List<RecordEntry>)
    private val aiSyncPending = java.util.concurrent.atomic.AtomicReference<AiSyncJob?>(null)
    private val aiSyncLock = Mutex()

    private suspend fun runBackupLocked(isStopped: () -> Boolean, manual: Boolean, picked: Boolean, progress: suspend (BackupStatus) -> Unit): Boolean = lock.withLock {
        val s = _settings.value
        urgent = false
        // 删除全部备份数据还没做完：什么都不传，等用户在备份页接着删
        if (wipeUnfinished) {
            setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.wipe_unfinished_status)) }
            return@withLock true
        }
        // 系统媒体库重建过（MediaStore id 从头编号）：以前记的本机 id 都不可信了，先全部放回“只在网盘里”，再按内容重新配对
        runCatching { checkMediaVersion() }
        val want = vault.wantEncrypted
        val stale = staleRecords(records.entries(), want)
        val forced = names.getStringSet(KEY_FORCED, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        if (!s.enabled && stale.isEmpty() && forced.isEmpty()) return@withLock true
        // 现在不满足自动备份的条件（比如设了仅 Wi‑Fi、却在用流量；手动选的照片的任务不看这些条件）：
        // 只传用户手动选的照片，不转换、不补传、不对账；其余的按设置等条件满足了再传
        // 手动选照片的任务（[picked]）只是替那几张不看条件，其余照片照样要满足“仅充电”：不当作“立即备份”
        val byButton = manual && !picked
        val restricted = !conditionsMet(s, byButton)
        if (restricted && forced.isEmpty()) {
            setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = if (manual && s.wifiOnly && isMetered()) tr(R.string.backup_wifi_wait) else it.message) }
            requeue = true
            return@withLock true
        }
        // 分享功能的存放位置还没切过去的（开关打开时没联网、刚授权）：先切，新照片直接传到新位置
        if (googleAccounts().any(::needsSwitch)) {
            runCatching { switchAllLocked() }
            scheduleMigration()
        }
        setStatus(progress) { it.copy(phase = BackupPhase.Checking, message = null, manual = manual, total = 0, done = 0) }
        // 中途退出（断网、系统停任务、转换失败、让锁）时也要把备份目录传一次：已经传上去的照片换手机后才找得回来
        var indexCtx: Pair<Set<CloudProvider>, ByteArray?>? = null
        try {
            val paused = pausedProviders()
            val statuses = CloudProvider.entries.map { p ->
                val account = if (p in paused) null else cachedAccount(p) ?: refreshAccount(p, locked = true)
                // 上传时网盘说过“满了”的账号：一段时间内按 0 剩余空间算（读不到剩余空间的账号也会这样）
                val free = if (quotaFullRecently(p)) 0L else account?.freeBytes
                DriveStatus(p, connected = account != null, freeBytes = free)
            }
            // 别的手机正在搬这个 Google 账号的存储位置：这一轮先不碰它；已经搬走了就跟着切换过去
            val use = statuses.filter { it.connected }.associate { it.provider to storageUsable(it.provider) }
            // 别的设备把账号的备份全部删除过（vault/wiped.json，见 spec/wipe.md）：这台手机不再往那里写，本机这个账号的记录也清掉。
            // 读不到这个标记的账号这一轮先不碰
            val skip = use.filterValues { it != StorageUse.No }.keys.filter { p -> runCatching { wipedElsewhere(p) }.getOrDefault(true) }.toSet()
            wipeRecheckAt = System.currentTimeMillis()
            val online = use.filterValues { it == StorageUse.Ok }.keys - skip
            // 别的手机正在搬的账号：不传新照片，但这台手机已经传完、还没写进目录的照片照常写进旧位置的目录
            val indexOnly = use.filterValues { it == StorageUse.IndexOnly }.keys - skip
            if (indexOnly.isNotEmpty()) {
                val k = runCatching { vault.wrapKey() }.getOrNull()
                val ready = indexOnly.filter { isReady(it) }.toSet()
                if (ready.isNotEmpty() && (k != null || !want)) runCatching { uploadIndex(ready, want, k) }
            }
            if (online.isEmpty() && skip.isNotEmpty() && skip.all { wipeHeld(it) }) {
                setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.wipe_held_status)) }
                return@withLock true
            }
            if (online.isEmpty()) {
                val signedIn = CloudProvider.entries.filter { auth.isSignedIn(it) && CloudConfig.of(it).configured }
                return@withLock if (signedIn.isNotEmpty()) {
                    // 登录都好好的，只是这会儿连不上（网络切换、省电模式限制联网）：自动的安静地等会儿重试，不报“断开”；
                    // 用户点的要告诉他
                    setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = if (manual) tr(R.string.backup_cloud_unreachable) else null) }
                    false
                } else {
                    setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = if (CloudProvider.entries.any { auth.needsReconnect(it) }) tr(R.string.cloud_needs_reconnect) else null) }
                    true
                }
            }
            val key = try {
                if (want || stale.any { it.encrypted }) {
                    vault.wrapKey() ?: run {
                        setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.e2ee_key_missing_reset)) }
                        return@withLock true
                    }
                } else {
                    // 关闭加密后、清理还没做完时，读加密目录还要用原来的密钥
                    runCatching { vault.wrapKey() }.getOrNull()
                }
            } catch (e: VaultBrokenException) {
                _encryption.update { it.copy(keyBroken = true) }
                setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = e.message) }
                return@withLock true
            }

            // 先确认网盘里的情况（有没有以前的加密备份、以前的备份目录），确认完之前不往这个网盘传任何东西
            for (p in online) runCatching { checkRemoteVault(p) }
            for (p in online) runCatching { restoreIndex(p, key) }
            if (_encryption.value.needsUnlock && vault.bundleJson == null) {
                // 以前的加密备份还没解锁、这台手机也还没有密钥：先不传新照片，免得同一个账号里一半加密一半不加密
                setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.e2ee_unlock_in_settings)) }
                return@withLock true
            }
            // 目录损坏或被还原过的账号整个暂停着：每隔几小时试读一次，读成功（比如另一台手机后来传了一份好的）就自动恢复
            retryIndexIssues(online, key)
            // 手机上已经有一套密钥、某个账号里是另一套（解锁前）：只有那个账号先停着（isReady 排除了它），其他账号照常备份
            var connected = online.filter { isReady(it) }.toSet()
            // 等用户处理的账号（解锁另一套密钥、选保留哪套、处理备份目录）不算“还在读取”，不用反复重试
            val waiting = online.filter { waitingForKey(it) || _indexIssues.value.containsKey(it) }.toSet()
            val notReady = online - connected - waiting
            if (connected.isEmpty()) {
                if (notReady.isEmpty() && online.any { _indexIssues.value.containsKey(it) }) {
                    setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.backup_index_needs_action)) }
                    return@withLock true
                }
                setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.backup_reading_remote)) }
                return@withLock notReady.isEmpty()
            }
            indexCtx = connected to key
            // 很久没读网盘目录了（这台手机一直没有新照片时不会去读）：读一次，拿到别的手机删掉的照片
            pullIfStale(connected, key)
            // 上次没删掉的旧文件，这次接着删
            processDeletes(connected)
            // 加密的文件上传之前，先保证网盘里有这套密钥的密钥包（换手机时靠它解开）
            if (want && key != null) {
                uploadKeyBundle(connected)
                // 这一轮才发现另一套密钥的账号：先把它排除，其他账号照常
                connected = connected.filter { isReady(it) }.toSet()
                if (connected.isEmpty()) {
                    setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.e2ee_conflict_in_settings)) }
                    return@withLock true
                }
                indexCtx = connected to key
            }
            val items = MediaRepository(app).loadAll().filter { it.sizeBytes > 0 }
            // 换手机后恢复出来的记录配上本机照片（在锁里做，不会和这一轮的上传同时改记录）
            if (runCatching { linkRestored(items) }.getOrDefault(false)) reloadRecords()
            registerAlbums(items.map { it.bucketId }.distinct())
            val local = items.associateBy { it.id }
            val dead = HashSet<CloudProvider>()
            var convertFailed = 0

            // ---- 1. 转换旧备份：一律先拿到明文（本机有就用本机的，没有就从网盘下载），再按新形式上传，最后删旧的 ----
            // 别的手机最近把这个账号的加密改成了另一种：先不转换，等用户决定跟不跟（见 [modeConflicts]），免得两台手机来回转换
            val conflicted = modeConflicts().keys
            val todo = if (restricted) emptyList() else staleRecords(records.entries(), want).filter { it.provider in connected && it.provider !in conflicted }
            if (todo.isNotEmpty()) {
                val phase = if (want) BackupPhase.Encrypting else BackupPhase.Decrypting
                setStatus(progress) { it.copy(phase = phase, total = todo.size, done = 0) }
                _encryption.update { it.copy(converting = true) }
                try {
                    // 重复照片共用一个网盘文件：同一个文件只转换一次，所有指向它的记录一起更新
                    val groups = todo.groupBy { it.provider to it.remoteId }.values.toList()
                    var doneCount = 0
                    var skippedSpace = 0
                    for (group in groups) {
                        val e = group.first()
                        doneCount += group.size
                        val i = doneCount - 1
                        if (isStopped()) return@withLock false
                        if (!conditionsMet(_settings.value, byButton)) {
                            // 条件没了（比如断了 Wi‑Fi 用上了流量）：停下，按设置等条件满足了再接着转换
                            requeue = true
                            break
                        }
                        if (urgent) {
                            // 有人在等这把锁（用户在删除、恢复、改照片信息）：先让出来，马上接着转换
                            rerun = true
                            markIndexDirty()
                            reloadRecords()
                            return@withLock true
                        }
                        if (e.provider in dead) continue
                        try {
                            val src = group.firstNotNullOfOrNull { g -> local[g.mediaId]?.takeIf { it.modifiedAt == g.modified } }
                            if (!convert(group, src, want, key)) skippedSpace++
                        } catch (ex: NotSignedInException) {
                            dead += e.provider
                            convertFailed++
                        } catch (ex: VaultBrokenException) {
                            // 以前那套密钥在本机解不开了（硬件密钥失效）：先跳过，等用户重新解锁，不挡住新照片的备份
                            _encryption.update { it.copy(keyBroken = true) }
                        } catch (ex: CorruptBackupException) {
                            // 坏掉的备份（解不开、被截断、内容不对）：重试也没用，单独记下来，不再挡后面的
                            markBroken(group, local)
                        } catch (ex: HttpException) {
                            if (ex.gone) {
                                // 网盘上的文件已经不在了（用户在网盘里删了、清了应用数据）：这些记录作废
                                dropMissing(group)
                            } else {
                                convertFailed++
                                if (convertFailed >= 5) break
                            }
                        } catch (ex: IOException) {
                            convertFailed++
                            if (convertFailed >= 5) break
                        }
                        setStatus(progress) { it.copy(done = i + 1, current = e.name) }
                        _encryption.update { it.copy(remaining = (it.remaining - group.size).coerceAtLeast(0)) }
                        // 转换很久时也隔一段把目录传上去（30 分钟或 200 处改动），中途换机也找得回已经换好的那些
                        runCatching { uploadIndex(connected, want, key) }
                    }
                    if (skippedSpace > 0) _status.update { it.copy(message = tr(R.string.backup_convert_no_space, skippedSpace)) }
                } finally {
                    _encryption.update { it.copy(converting = false) }
                }
                markIndexDirty()
                reloadRecords()
                // 没转换完的下一轮接着转，不挡住新照片：新照片按现在的形式传，不会多出新的“形式不对”的备份
            }
            if (!want && vault.bundleJson != null && convertFailed == 0) finishDisable(connected, key)
            runCatching { cleanupOldKeys() }
            if (!s.enabled && forced.isEmpty()) {
                runCatching { uploadIndex(connected, want, key, force = todo.isNotEmpty() || oldKeyWaitsForIndex()) }
                // 换密钥后目录要先用当前密钥传上去，旧密钥才能删（见 [cleanupOldKeys]）：传完再清一次，
                // 否则自动备份关着时旧密钥会一直留在手机上
                runCatching { cleanupOldKeys() }
                setStatus(progress) {
                    it.copy(
                        phase = BackupPhase.Idle,
                        lastFinishedAt = System.currentTimeMillis(),
                        message = if (convertFailed > 0) tr(R.string.backup_convert_pending, convertFailed) else it.message,
                    )
                }
                return@withLock convertFailed == 0
            }

            // ---- 2. 上传新照片 ----
            // 这一轮的记录只读一遍，后面都用它
            val snapshot = records.entries()
            val recs = snapshot.groupBy { it.mediaId }
            val byRemote = snapshot.associateBy { it.provider to it.remoteId }
            val router = BackupRouter(_settings.value, statuses.map { it.copy(connected = it.provider in connected) })
            val jobs = ArrayList<Pair<MediaItem, List<CloudProvider>>>()
            var eligible = 0
            var backedUp = 0
            var full = false
            val fullDrives = LinkedHashSet<CloudProvider>()
            // 手动点了“备份”的照片：不管相簿规则和自动备份开没开，都备份到能用的网盘
            val fallback = listOfNotNull(s.ranked(connected).firstOrNull())
            fun candidate(item: MediaItem) = BackupCandidate(item.id, item.bucketId, item.sizeBytes, item.isVideo)
            for (item in items) {
                if ((!s.enabled || restricted) && item.id !in forced) continue
                val mine = recs[item.id].orEmpty()
                val already = mine.filter { it.modified == item.modifiedAt }.map { it.provider }.toSet()
                // 换手机后按名字配上、还在等核对的（见 [linkRestored]）：先不往那个账号再传一份，核对完再说
                val verifying = mine.filter { it.modified == 0L }.map { it.provider }.toSet()
                if (item.id in forced && already.isEmpty()) {
                    val r = router.route(candidate(item), already + verifying)
                    eligible++
                    jobs.add(item to ((r as? Route.Upload)?.providers?.takeIf { it.isNotEmpty() } ?: fallback.filter { it !in verifying }))
                    continue
                }
                when (val r = router.route(candidate(item), already + verifying)) {
                    is Route.Upload -> {
                        eligible++
                        fullDrives += r.full
                        if (r.providers.isEmpty()) {
                            if (already.isNotEmpty()) backedUp++
                        } else {
                            jobs.add(item to r.providers)
                        }
                    }
                    is Route.Full -> {
                        eligible++
                        if (already.isNotEmpty()) {
                            backedUp++
                        }
                        full = true
                        fullDrives += r.providers
                    }
                    Route.NoDrive -> {
                        eligible++
                    }
                    Route.Skipped -> {}
                }
            }
            // 手动选的照片排在最前面；上一轮传失败过的大文件排到最后，不让它每轮都先占着网络
            val slow = names.getStringSet(KEY_SLOW, emptySet())!!
            jobs.sortBy { if (it.first.id in forced) 0 else if (it.first.id.toString() in slow) 2 else 1 }
            setStatus(progress) {
                it.copy(phase = if (jobs.isEmpty()) BackupPhase.Checking else BackupPhase.Uploading, total = jobs.size, done = 0, backedUp = backedUp, eligible = eligible)
            }
            // 只有网盘里已有同样大小的文件时才算内容指纹（用来去重），大部分照片不用多读一遍
            val knownSizes = snapshot.mapTo(HashSet()) { it.size }

            // 内容指纹 → 各网盘上已有的文件：完全相同的照片只传一份
            val byHash = HashMap<String, MutableMap<CloudProvider, String>>()
            snapshot.forEach { e -> if (e.hash.isNotEmpty()) byHash.getOrPut(e.hash) { HashMap() }[e.provider] = e.remoteId }
            /** 能不能直接复用这个网盘文件：这一轮里被当成旧版本删掉的（进了墓碑或待删除队列）不能再用。 */
            fun reusable(p: CloudProvider, id: String) = !records.isTombstoned(p, id) && !records.inTrash(p, id)
            fun forgetRemote(p: CloudProvider, id: String) = byHash.values.forEach { m -> if (m[p] == id) m.remove(p) }
            var failures = 0
            var uploaded = 0
            for ((index, job) in jobs.withIndex()) {
                if (isStopped()) return@withLock false
                if (urgent) {
                    // 用户又手动选了照片：这一轮先到这里，马上开下一轮先传那些
                    rerun = true
                    break
                }
                // 每传一张看一眼条件（只读系统状态，不耗电）：刚打开“仅 Wi‑Fi”、或者断了 Wi‑Fi 用上了流量，就停下；
                // 手动选的照片不受影响
                if (job.first.id !in forced && !conditionsMet(_settings.value, byButton)) {
                    requeue = true
                    if (jobs.drop(index).none { it.first.id in forced }) break
                    continue
                }
                // 传很久时隔一段看一眼“已清空”标记：别的设备这时删除了全部备份，这边马上停手
                if (System.currentTimeMillis() - wipeRecheckAt > WIPE_RECHECK_MS) {
                    wipeRecheckAt = System.currentTimeMillis()
                    for (p in connected) runCatching { wipedElsewhere(p) }
                }
                val item = job.first
                var providers = job.second
                // 这一轮里发现满了的账号：改放下一个能放的
                if (providers.any { it in fullDrives && quotaFullRecently(it) }) {
                    val again = router.route(candidate(item), recs[item.id].orEmpty().filter { it.modified == item.modifiedAt }.map { it.provider }.toSet())
                    providers = (again as? Route.Upload)?.providers.orEmpty()
                    if (again is Route.Full) full = true
                }
                var allOk = providers.isNotEmpty()
                // 文件实际大小和系统登记的不一样（登记过时、正在被写）：这次先跳过，下次再传
                val real = realSize(item)
                if (real != null && real != item.sizeBytes) {
                    setStatus(progress) { it.copy(done = index + 1, current = item.displayName) }
                    continue
                }
                // 网盘里已有同样大小的文件、或者要续传的大文件，先算指纹：能复用就不传；大文件靠它保证续传时内容没变
                var hash = if (item.sizeBytes !in knownSizes && item.sizeBytes < RESUME_MIN_BYTES) "" else try {
                    sha256(item)
                } catch (e: IOException) {
                    ""
                }
                var i = 0
                val queue = ArrayDeque(providers)
                while (queue.isNotEmpty()) {
                    val p = queue.removeFirst()
                    if (i++ > 8) break
                    if (p in dead || wipeHeld(p)) continue
                    // 同样内容的照片已经在同一种网盘的另一个账号里了（比如换手机后没按名字配上）：
                    // 直接记到那个账号上，不再往这个账号传第二份（超出限额暂停中的账号不算，照常传到这个账号）
                    val sibling = if (hash.isEmpty()) null else byHash[hash]?.takeIf { p !in it }?.entries
                        ?.firstOrNull { (q, id) -> q != p && q.kind == p.kind && q !in paused && reusable(q, id) }
                    if (sibling != null) {
                        val ref = byRemote[sibling.key to sibling.value] ?: records.byRemote(sibling.key, sibling.value).firstOrNull()
                        if (ref != null && records.get(item.id, sibling.key) == null) {
                            records.put(ref.copy(mediaId = item.id, modified = item.modifiedAt, size = item.sizeBytes))
                            markIndexDirty()
                        }
                        _backedUp.update { it + item.id }
                        continue
                    }
                    try {
                        val existing = if (hash.isNotEmpty()) byHash[hash]?.get(p)?.takeIf { reusable(p, it) } else null
                        val remoteId = existing ?: run {
                            var reader: HashingInputStream? = null
                            val resume = resumeKeyFor(p, hash, item.sizeBytes, want, key)
                            val upload = uploadFile(item.displayName, item.bucketName, item.mimeType, item.sizeBytes, item.takenAt, want, key, resume) {
                                HashingInputStream(open(item), item.sizeBytes).also { reader = it }
                            }
                            // 失败时留着这组随机数（续传要用，会话也和它绑着），只在传成功以后丢掉
                            val id = journaled(p, upload) { f -> retrying { drive(p).upload(f) } }
                            resume?.let { resumeCrypto.remove(it) }
                            if (reader == null) {
                                // 上次的续传会话其实已经传完（查进度时网盘直接说完成了），这次没再读本机文件：
                                // 核对网盘上的大小，对得上就接受，不用从头重传
                                val f = runCatching { retrying { drive(p).stat(id) } }.getOrNull()
                                if (f == null || (f.size >= 0 && f.size != upload.sizeBytes)) {
                                    records.enqueueDelete(p, id)
                                    throw ChangedWhileReading()
                                }
                            } else if (reader?.lengthOk != true) {
                                // 读到的长度和登记的对不上：这份备份不完整，删掉，下次重传
                                resume?.let { resumeCrypto.remove(it) }
                                records.enqueueDelete(p, id)
                                throw ChangedWhileReading()
                            }
                            val read = reader?.hexIfComplete()
                            if (hash.isNotEmpty() && read != null && read != hash) {
                                // 算完指纹到上传之间文件被改了：这份作废，下次重传
                                records.enqueueDelete(p, id)
                                throw ChangedWhileReading()
                            }
                            if (hash.isEmpty()) {
                                hash = read ?: runCatching { sha256(item) }.getOrDefault("")
                            }
                            id
                        }
                        knownSizes += item.sizeBytes
                        if (hash.isNotEmpty()) byHash.getOrPut(hash) { HashMap() }[p] = remoteId
                        // 存一张小缩略图：本机删掉原图后，相册里还能看到它；也传一份到网盘，换手机后不用下载原图
                        val thumbFile = runCatching { saveThumb(item, hash.ifEmpty { p.name + "-" + remoteId }) }.getOrNull()
                        val thumbId = if (existing != null) {
                            byRemote[p to existing]?.thumb ?: records.byRemote(p, existing).firstOrNull()?.thumb.orEmpty()
                        } else {
                            thumbFile?.let { f -> runCatching { uploadThumb(p, f, want, key) }.getOrNull() }.orEmpty()
                        }
                        // 动态照片：文件原样传上去，视频跟着走；这里只是在目录里标一下（mo），网页和别的手机好显示标记
                        val motion = if (existing != null) {
                            (byRemote[p to existing] ?: records.byRemote(p, existing).firstOrNull())?.motion == true || isMotion(item)
                        } else isMotion(item)
                        replaceRecord(entryFor(item, p, remoteId, want, hash).copy(thumb = thumbId, motion = motion))?.let { gone -> forgetRemote(p, gone) }
                        markIndexDirty()
                        if (existing == null) uploaded++
                        _backedUp.update { it + item.id }
                        failures = 0
                    } catch (e: NotSignedInException) {
                        dead += p
                        setDrive(p) { it.copy(signedIn = false, needsReconnect = true, error = e.message) }
                        allOk = false
                    } catch (e: ChangedWhileReading) {
                        allOk = false
                    } catch (e: HttpException) {
                        if (e.quotaFull) {
                            // 网盘满了：记下来，这一轮后面的照片改放下一个账号；这一张也马上换一个账号试
                            markQuotaFull(p)
                            router.markFull(p)
                            fullDrives += p
                            val again = router.route(candidate(item), recs[item.id].orEmpty().filter { it.modified == item.modifiedAt }.map { it.provider }.toSet() + p)
                            val next = (again as? Route.Upload)?.providers.orEmpty().filter { it != p && it !in queue }
                            if (next.isEmpty()) {
                                full = true
                                allOk = false
                            } else {
                                queue.addAll(next)
                            }
                            continue
                        }
                        allOk = false
                        if (++failures >= 5) {
                            markSlow(item)
                            setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.backup_network_continue)) }
                            return@withLock false
                        }
                    } catch (e: IOException) {
                        allOk = false
                        // 连续失败多半是网络断了，停下来等 WorkManager 重试
                        if (++failures >= 5) {
                            markSlow(item)
                            setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.backup_network_continue)) }
                            return@withLock false
                        }
                    }
                }
                if (allOk) clearSlow(item) else if (item.sizeBytes >= RESUME_MIN_BYTES) markSlow(item)
                if (allOk && item.id in forced) removeForced(item.id)
                setStatus(progress) { it.copy(done = index + 1, backedUp = it.backedUp + if (allOk) 1 else 0, current = item.displayName) }
                // 首次备份几万张时也隔一段就把目录传上去（30 分钟或 200 处改动），中途丢了手机也找得回来
                runCatching { uploadIndex(connected, want, key) }
            }
            // 本机已经不在的照片也不用再强制备份了
            forced.filter { it !in local }.forEach(::removeForced)
            if (restricted) requeue = true
            if (!isStopped() && !urgent && !restricted) runCatching { backfill(local, want, key) { isStopped() || urgent } }
            // 充电、不计流量时偶尔和网盘对一次账：网盘里已经没了的照片改回未备份，没人引用的旧文件清掉
            // 已经写进记录的上传不用再记着（上传日志只留没写进记录的，见 journaled）
            runCatching { records.pruneUploads() }
            if (!isStopped() && !urgent && !restricted && isCharging() && !isMetered()) runCatching { reconcile(connected, key) { isStopped() || urgent } }
            runCatching { uploadIndex(connected, want, key, force = todo.isNotEmpty() || oldKeyWaitsForIndex()) }
            // 同上：目录传上去以后再清一次旧密钥
            runCatching { cleanupOldKeys() }
            // 只在端到端加密开着、本机拿着密钥时同步
            if (want && key != null && aiResults != null) aiSyncPending.set(AiSyncJob(connected, key, records.entries()))
            reloadRecords()
            // 传了东西，剩余空间变了，下一轮重新查
            if (uploaded > 0) accountCheckedAt.clear()
            val conflictLabels = modeConflicts().keys.filter { it in online }
            setStatus(progress) {
                it.copy(
                    phase = BackupPhase.Idle,
                    current = null,
                    lastFinishedAt = System.currentTimeMillis(),
                    lastRunUploaded = uploaded,
                    message = when {
                        dead.isNotEmpty() -> dead.joinToString(tr(R.string.list_separator)) { p -> p.label } + tr(R.string.backup_needs_sign_in_suffix)
                        fullDrives.isNotEmpty() -> tr(R.string.backup_drives_full, fullDrives.joinToString(tr(R.string.list_separator)) { p -> accountLabel(p) })
                        full -> tr(R.string.backup_cloud_full)
                        conflictLabels.isNotEmpty() -> tr(R.string.e2ee_mode_conflict_in_settings)
                        convertFailed > 0 -> tr(R.string.backup_convert_pending, convertFailed)
                        notReady.isNotEmpty() -> notReady.joinToString(tr(R.string.list_separator)) { p -> p.label } + tr(R.string.backup_still_reading_suffix)
                        online.any(::waitingForKey) -> tr(R.string.e2ee_conflict_in_settings)
                        else -> null
                    },
                )
            }
            // 有网盘还没确认完、或者有旧备份没转换完：稍后再来一轮
            notReady.isEmpty() && convertFailed == 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 兜底：任何没想到的错误都不能让后台任务永久失败、界面一直卡在“正在转换”
            // 给用户看通用的说法，技术细节写进日志
            android.util.Log.w("ZyllBackup", "backup round failed", e)
            setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.backup_unexpected_error)) }
            false
        } finally {
            _status.update { if (it.running) it.copy(phase = BackupPhase.Idle) else it }
            _encryption.update { if (it.converting) it.copy(converting = false) else it }
            indexCtx?.let { (c, k) ->
                if (names.getBoolean(KEY_INDEX_DIRTY, false)) {
                    // 已经被取消（系统停了任务）也要试一次，最多等 20 秒；失败没关系，“有改动”的标记还在，下次接着传
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { uploadIndex(c, vault.wantEncrypted, k, force = true) } }
                    }
                }
            }
        }
    }

    /**
     * 给以前的备份补上缺的东西：充电时补算内容指纹（换手机后靠它认出同一张照片），
     * 每轮再给几十张补传网盘缩略图。都有数量上限，不会一次跑很久。
     */
    private suspend fun backfill(local: Map<Long, MediaItem>, want: Boolean, key: ByteArray?, isStopped: () -> Boolean) {
        var changed = false
        // 换手机后按名字配上、还没核对指纹的：核对一下，对上了才算已备份，对不上就放回“只在网盘里”
        // 充电时一次多核对一些
        val batch = if (isCharging()) VERIFY_BATCH * 5 else VERIFY_BATCH
        // 先筛掉本机已经没有的再取一批，不然它们一直占着名额，后面的永远轮不到
        records.entries().filter { it.mediaId > 0 && it.modified == 0L && it.hash.isNotEmpty() && it.mediaId in local }.take(batch).forEach { r ->
            if (isStopped()) return
            val item = local[r.mediaId] ?: return@forEach
            val h = runCatching { sha256(item) }.getOrNull() ?: return@forEach
            if (h == r.hash) {
                records.put(r.copy(modified = item.modifiedAt, size = item.sizeBytes))
            } else {
                records.remove(r.mediaId, r.provider)
                records.put(r.copy(mediaId = syntheticId(r.provider, r.remoteId)))
            }
            changed = true
        }
        // 以前的备份没有指纹、换手机后只按名字和大小配上的：不用流量时把网盘那份下回来算一下指纹再核对。
        // 很大的文件不下载，名字、大小、拍摄时间都对上就认了（这时用本机算出的指纹补上）
        if (!isMetered()) {
            val now = System.currentTimeMillis()
            records.entries().filter { it.mediaId > 0 && it.modified == 0L && it.hash.isEmpty() && it.mediaId in local }
                .distinctBy { it.provider to it.remoteId }
                // 下载一直失败的（坏文件、没密钥）一天内不再试，不占名额、也不每轮重新下载
                .filter { now - (hashlessFailedAt[it.provider to it.remoteId] ?: 0L) > HASHLESS_RETRY_MS }
                .take(VERIFY_HASHLESS).forEach { r ->
                    if (isStopped()) return
                    val item = local[r.mediaId] ?: return@forEach
                    val h = runCatching { sha256(item) }.getOrNull() ?: return@forEach
                    val same = if (r.size > VERIFY_DOWNLOAD_MAX) {
                        item.sizeBytes == r.size && kotlin.math.abs(item.takenAt - r.takenAt) < 60_000
                    } else {
                        val f = runCatching { original(entryOf(r)) }.getOrNull()
                            ?: run { hashlessFailedAt[r.provider to r.remoteId] = now; return@forEach }
                        runCatching { fileSha(f) }.getOrNull() == h
                    }
                    val group = records.byRemote(r.provider, r.remoteId)
                    records.transaction {
                        if (same) {
                            group.forEach { g -> records.put(g.copy(hash = h, modified = if (g.mediaId == r.mediaId) item.modifiedAt else g.modified, size = item.sizeBytes)) }
                        } else {
                            records.remove(r.mediaId, r.provider)
                            if (records.byRemote(r.provider, r.remoteId).isEmpty()) records.put(r.copy(mediaId = syntheticId(r.provider, r.remoteId)))
                        }
                    }
                    changed = true
                }
        }
        if (changed) reloadRecords()
        val all = records.entries()
        if (isCharging()) {
            all.filter { it.hash.isEmpty() && it.mediaId > 0 }
                .mapNotNull { r -> local[r.mediaId]?.takeIf { it.modifiedAt == r.modified }?.let { r to it } }
                .take(BACKFILL_HASH)
                .forEach { (r, item) ->
                    if (isStopped()) return
                    val h = runCatching { sha256(item) }.getOrNull() ?: return@forEach
                    // 同一个网盘文件的所有记录一起更新
                    all.filter { it.provider == r.provider && it.remoteId == r.remoteId }.forEach { records.put(it.copy(hash = h)) }
                    changed = true
                }
        }
        // 这个版本以前备份的动态照片：按 MediaStore id 从小到大一批批补上 mo 标记（只看文件写明的位置，读得很少），
        // 记住补到哪了，每张只看一次。之后新传的照片上传时就会标
        val motionCursor = names.getLong(KEY_MOTION_BACKFILL, 0)
        val motionBatch = all.asSequence()
            .filter { it.mediaId > motionCursor && it.mediaId in local }
            .sortedBy { it.mediaId }
            .distinctBy { it.mediaId }
            .take(BACKFILL_MOTION)
            .toList()
        if (motionBatch.isNotEmpty()) {
            for (r in motionBatch) {
                if (isStopped()) break
                val item = local[r.mediaId] ?: continue
                if (!r.motion && item.modifiedAt == r.modified && MotionPhoto.mayHaveMotion(item.mimeType, item.displayName) &&
                    MotionPhotos.find(app, item.uri, scan = false) != null
                ) {
                    // 重新读当前的记录（前面同一轮可能刚补过指纹），只改 motion
                    CloudProvider.entries.mapNotNull { records.get(r.mediaId, it) }.forEach { records.put(it.copy(motion = true)) }
                    changed = true
                }
                names.edit().putLong(KEY_MOTION_BACKFILL, r.mediaId).apply()
            }
        }
        val fresh = if (changed) records.entries() else all
        fun thumbSource(r: RecordEntry) = File(thumbDir, r.hash.ifEmpty { r.provider.name + "-" + r.remoteId } + ".jpg")
        fresh.filter { it.thumb.isEmpty() && it.encrypted == want }
            .distinctBy { it.provider to it.remoteId }
            // 先筛掉没有来源的（只在网盘、本机没原图也没缓存），再取一批，不然它们一直排在前面，后面的永远轮不到
            .filter { r -> r.mediaId in local || thumbSource(r).isFile }
            .take(BACKFILL_THUMB)
            .forEach { r ->
                if (isStopped()) return
                val f = File(thumbDir, r.hash.ifEmpty { r.provider.name + "-" + r.remoteId } + ".jpg").takeIf { it.isFile }
                    ?: local[r.mediaId]?.let { item -> runCatching { saveThumb(item, r.hash.ifEmpty { r.provider.name + "-" + r.remoteId }) }.getOrNull() }
                    ?: return@forEach
                val id = runCatching { uploadThumb(r.provider, f, want, key) }.getOrNull() ?: return@forEach
                fresh.filter { it.provider == r.provider && it.remoteId == r.remoteId }.forEach { records.put(it.copy(thumb = id)) }
                changed = true
            }
        if (changed) markIndexDirty()
    }

    /** 本机这张照片是不是动态照片（会把文件扫一遍：上传时文件本来就要整个读一遍）。 */
    private suspend fun isMotion(item: MediaItem): Boolean =
        !item.isVideo && MotionPhoto.mayHaveMotion(item.mimeType, item.displayName) && MotionPhotos.find(app, item.uri, scan = true) != null

    private fun isCharging(): Boolean =
        app.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true

    private suspend fun setStatus(progress: suspend (BackupStatus) -> Unit, f: (BackupStatus) -> BackupStatus) {
        val s = f(_status.value)
        _status.value = s
        progress(s)
    }

    /**
     * 把一份旧形式的备份换成新形式。手机空间不够放这份临时文件时跳过它，返回 false（不算失败，下一轮再试）。
     * 网盘上的文件已经不在时抛 [HttpException]（gone），解不开 / 内容不对时抛 [CorruptBackupException]。
     */
    private suspend fun convert(group: List<RecordEntry>, localItem: MediaItem?, encrypt: Boolean, key: ByteArray?): Boolean {
        val e = group.first()
        val drive = drive(e.provider)
        // 网盘上有缩略图、本机缓存里没有（没存过，或者被缓存上限淘汰了）：先用原来的形式拉下来，转换后照样有缩略图。
        // 加密和不加密都要拉：不然开启加密时，旧的明文缩略图删了、新的却传不上去
        if (e.thumb.isNotEmpty()) runCatching { thumbnail(entryOf(e)) }
        val tmpDir = File(app.cacheDir, "cloud-convert").apply { mkdirs() }
        val plain = File(tmpDir, "plain")
        try {
            // 从网盘下载旧文件当来源：加密的边下载边解密，只落一份明文临时文件（4 GB 的视频不用 8 GB 空间）
            suspend fun remoteSource(): Pair<Long, () -> InputStream> {
                // 临时文件要放得下这份明文，还要给系统留 200 MB
                if (tmpDir.usableSpace < e.size + CONVERT_SPACE_MARGIN) throw NoSpaceForConvert()
                val k = if (e.encrypted) ((if (e.keyId.isEmpty()) key else vault.oldKey(e.keyId)) ?: throw IOException(tr(R.string.e2ee_key_missing))) else null
                retrying {
                    plain.outputStream().use { out ->
                        if (k == null) {
                            drive.download(e.remoteId, out)
                        } else {
                            downloadDecrypting(drive, e.remoteId, k, out)
                        }
                    }
                }
                // 和记下的指纹核对：网盘上的文件被调包、或者损坏时不拿它去覆盖
                if (e.hash.isNotEmpty() && !fileSha(plain).equals(e.hash, ignoreCase = true)) throw CorruptBackupException(null)
                return plain.length() to { plain.inputStream() }
            }
            // 本机原图能不能用：大小和登记的一致；原来那份备份有指纹时，先算本机指纹，对不上就不用（省一次白传）
            val local = localItem?.takeIf { item ->
                val real = realSize(item)
                (real == null || real == item.sizeBytes) &&
                    (e.hash.isEmpty() || runCatching { sha256(item) }.getOrNull() == e.hash)
            }
            var source: Pair<Long, () -> InputStream>
            var newId: String
            val resume = resumeKeyFor(e.provider, e.hash, local?.sizeBytes ?: e.size, encrypt, key)
            if (local != null) {
                var reader: HashingInputStream? = null
                source = local.sizeBytes to { HashingInputStream(open(local), local.sizeBytes).also { reader = it } }
                newId = journaled(e.provider, uploadFile(e.name, e.album, e.mime, source.first, e.takenAt, encrypt, key, resume, source.second)) { f -> retrying { drive.upload(f) } }
                val h = reader?.hexIfComplete()
                if (h == null || (e.hash.isNotEmpty() && h != e.hash)) {
                    // 上传过程中本机文件变了：这份作废，同一次就改用网盘上的旧文件转换，不让整轮卡住
                    resume?.let { resumeCrypto.remove(it) }
                    deleteNowOrLater(e.provider, newId)
                    source = remoteSource()
                    newId = journaled(e.provider, uploadFile(e.name, e.album, e.mime, source.first, e.takenAt, encrypt, key, null, source.second)) { f -> retrying { drive.upload(f) } }
                }
            } else {
                // 本机已经删了（或者本机那份和备份对不上），用网盘上的旧文件
                source = remoteSource()
                newId = journaled(e.provider, uploadFile(e.name, e.album, e.mime, source.first, e.takenAt, encrypt, key, resume, source.second)) { f -> retrying { drive.upload(f) } }
            }
            resume?.let { resumeCrypto.remove(it) }
            // 转换期间网页或别的手机把这张删了：新文件不留，也不写记录（下次合并墓碑时旧记录照常删掉）
            if (deletedMeanwhile(e.provider, e.remoteId)) {
                deleteNowOrLater(e.provider, newId)
                return true
            }
            // 缩略图也换成新形式（加密时不能留明文缩略图）
            val oldThumbFile = File(thumbDir, e.hash.ifEmpty { e.provider.name + "-" + e.remoteId } + ".jpg")
            val newThumbFile = File(thumbDir, e.hash.ifEmpty { e.provider.name + "-" + newId } + ".jpg")
            if (oldThumbFile != newThumbFile && oldThumbFile.isFile) oldThumbFile.renameTo(newThumbFile)
            if (e.thumb.isNotEmpty()) deleteNowOrLater(e.provider, e.thumb)
            val newThumb = if (newThumbFile.isFile) runCatching { uploadThumb(e.provider, newThumbFile, encrypt, key) }.getOrDefault("") else ""
            records.transaction {
                group.forEach { records.put(it.copy(remoteId = newId, encrypted = encrypt, size = source.first, thumb = newThumb, keyId = "")) }
            }
            // 旧文件告诉系统照片选择器已删除，不然那边会留一份打不开的
            rememberPickerDeleted(group.take(1))
            // 旧形式的文件（比如开启加密后的明文原图）一定要删掉：这次删不掉就留在队列里，下轮接着删
            discardRemote(e.provider, e.remoteId, "")
            markReplaced(e.provider, e.remoteId, newId)
            return true
        } catch (_: NoSpaceForConvert) {
            return false
        } catch (ex: app.zyll.crypto.ZyllCryptoException) {
            throw CorruptBackupException(ex)
        } finally {
            plain.delete()
        }
    }

    private class NoSpaceForConvert : IOException()

    /**
     * 边下载边解密：下载写进管道，另一头解密写进 [out]。要求是原图。解不开、被截断时抛 [app.zyll.crypto.ZyllCryptoException]。
     */
    private suspend fun downloadDecrypting(drive: CloudDrive, id: String, k: ByteArray, out: java.io.OutputStream) = kotlinx.coroutines.coroutineScope {
        val pin = java.io.PipedInputStream(1 shl 20)
        val pout = java.io.PipedOutputStream(pin)
        // 下载那头的错误先记下再关管道：解密这头因为读到半截而失败时，抛的是真正的原因（404、断网、登录失效）
        val readerDone = java.util.concurrent.atomic.AtomicBoolean(false)
        var dlError: Exception? = null
        val dl = launch(Dispatchers.IO) {
            try {
                drive.download(id, pout)
            } catch (e: Exception) {
                // 解密那头先出错、关了管道导致的写入失败不算
                if (!readerDone.get()) dlError = e
            } finally {
                runCatching { pout.close() }
            }
        }
        try {
            Zylc.decryptStream(pin, k, out, BlobKind.ORIGINAL)
        } catch (e: Exception) {
            readerDone.set(true)
            runCatching { pin.close() }
            dl.join()
            throw dlError ?: e
        }
        readerDone.set(true)
        runCatching { pin.close() }
        dl.join()
        dlError?.let { throw it }
    }

    /** 把本机的密钥包传到网盘（换手机时要用）。网盘上已经有另一套的密钥包时不覆盖（用户放弃过的除外）。 */
    private suspend fun uploadKeyBundle(connected: Set<CloudProvider>) {
        val mine = vault.vaultId ?: return
        val now = System.currentTimeMillis()
        for (p in connected) {
            // 这个账号的备份在别的设备上删除过：不再把密钥包传回去（读不到“已清空”标记时这次也先不传）
            if (runCatching { wipedElsewhere(p, WIPE_CHECK_FRESH_MS) }.getOrDefault(true)) continue
            // 每个账号重新取：前一个账号里可能刚换上了别的设备写的新版
            val bundle = vault.bundleJson ?: return
            // 传过以后每天再看一眼：别的手机可能用另一套密钥覆盖了，或者密钥包被删了。发现后马上停下这个账号，不再用错的密钥写
            val uploaded = names.getBoolean(KEY_VAULT_UPLOADED_PREFIX + p.name, false)
            if (uploaded && now - names.getLong(KEY_BUNDLE_CHECKED_AT + p.name, 0) < BUNDLE_RECHECK_MS) continue
            try {
                val drive = drive(p)
                val existing = retrying { drive.findFile(KEYBUNDLE_PATH) }
                // 网盘上是同一套密钥、但比本机旧（本机刚改了密码或换了恢复密钥）：要覆盖
                var newer = false
                if (existing != null) {
                    val out = java.io.ByteArrayOutputStream()
                    retrying { out.reset(); drive.download(existing, out) }
                    val text = String(out.toByteArray())
                    val remote = runCatching { KeyBundle.parse(text) }.getOrNull()
                    val theirs = remote?.vaultId
                    if (remote != null && theirs == mine) {
                        // 同一套密钥：按 rev 比新旧。网盘上的新（别的设备改了密码或换了恢复密钥）、mac 也对得上就换上；
                        // 本机的新就覆盖上去；一样新就不动。
                        // 网盘上的 rev 更大、mac 却对不上（不是有这套密钥的设备写的，或者写坏了）：不可信，用本机这份覆盖
                        val localRev = KeyBundle.revOf(KeyBundle.parse(bundle))
                        val remoteRev = KeyBundle.revOf(remote)
                        val wrapKey = runCatching { vault.wrapKey() }.getOrNull() ?: continue
                        val trusted = remoteRev > localRev && KeyBundle.verify(remote, wrapKey)
                        newer = localRev > remoteRev || (remoteRev > localRev && !trusted)
                        if (!newer) {
                            if (trusted) adoptBundleFromElsewhere(text)
                            addVaultAccount(p)
                            names.edit().putLong(KEY_BUNDLE_CHECKED_AT + p.name, now).apply()
                            bundleSynced(p)
                            continue
                        }
                        if (remoteRev > localRev) android.util.Log.w("ZyllBackup", "keybundle in ${p.name} has a newer rev but a bad mac; replacing it")
                    }
                    if (newer) {
                        // 往下走，覆盖成本机这份
                    } else if (theirs != null && theirs in vault.oldKeyIds()) {
                        // 以前另一套（已解开、等着换掉）：这个账号里用它加密的照片全部换成当前密钥、目录也换过以后，
                        // 才把网盘上的密钥包换成当前这套。换完之前别的手机还要靠旧密钥包解开那些照片
                        val left = records.entries().any { it.provider == p && it.keyId == theirs }
                        if (left || pendingKeyChoice() == theirs || names.getBoolean(KEY_INDEX_DIRTY, false)) continue
                    } else if ((theirs ?: "") != names.getString(KEY_REMOTE_ABANDONED + p.name, null)) {
                        // 另一套密钥：先停下来让用户决定
                        names.edit().putString(KEY_REMOTE_BUNDLE + p.name, text).apply()
                        refreshUnlockState()
                        continue
                    }
                }
                val bytes = bundle.toByteArray()
                retrying { drive.upload(UploadFile(KEYBUNDLE_PATH, "application/json", bytes.size.toLong(), System.currentTimeMillis(), replace = true) { bytes.inputStream() }) }
                addVaultAccount(p)
                names.edit().putLong(KEY_BUNDLE_CHECKED_AT + p.name, now).apply()
                bundleSynced(p)
                // 换掉的是以前那套的密钥包：目录在过渡期还是用那套加密的（见 indexKeyFor），现在改用当前密钥重新传
                if (existing != null && !newer) markIndexDirty()
            } catch (_: IOException) {
            }
        }
    }

    // ---------- 系统照片选择器（别的 App 选图时显示网盘里的照片） ----------

    /** 选择器里的一项。[localUri] 不为空表示手机里也有这张，选择器会去重。 */
    class PickerItem(val id: String, val entry: CloudEntry, val generation: Long, val localUri: android.net.Uri?)

    private fun pickerId(p: CloudProvider, remoteId: String) = "${p.ordinal}_$remoteId"

    // 给系统照片选择器用的缓存。都是可空 / Boolean，JVM 默认值就安全（init 里启动的协程可能比字段初始化先跑）
    @Volatile private var pickerCache: List<PickerItem>? = null
    /** 备份记录里这台手机上还在的照片（MediaStore id）；还没查过为 null。 */
    @Volatile private var localIdsCache: Set<Long>? = null
    @Volatile private var localIdsAt = 0L
    @Volatile private var localIdsRefreshing = false

    /**
     * 在后台重新查一次“备份记录里哪些照片手机上还在”。查完换掉缓存，选择器下次取列表时用新的。
     *
     * 不能在选择器的调用里同步查：系统选择器（MediaProvider）调用 Zyll 时可能正拿着自己的锁，
     * 这时 Zyll 再去查 MediaStore 就要等同一个 MediaProvider，两边互相等，整个系统的媒体服务卡死，只能重启手机。
     */
    private fun refreshLocalIds() {
        if (localIdsRefreshing) return
        localIdsRefreshing = true
        scope.launch(Dispatchers.IO) {
            try {
                val ids = records.entries().map { it.mediaId }.filter { it > 0 }.distinct()
                localIdsCache = existingMediaIds(ids)
                localIdsAt = android.os.SystemClock.elapsedRealtime()
                pickerCache = null
            } catch (e: Exception) {
                android.util.Log.w("ZyllPicker", "local ids refresh failed", e)
            } finally {
                localIdsRefreshing = false
            }
        }
    }

    /**
     * 当前网盘里的全部照片（同一份内容只出现一次），给系统照片选择器用。选择器在它的线程里同步调用：
     * 这里只读本机数据库和内存缓存，**不查 MediaStore**（原因见 [refreshLocalIds]）。
     * “手机上还在不在”用上次在后台查到的结果；还没查过时一律当作只在网盘里（最多在选择器里和本机那张各显示一次，不会漏）。
     */
    fun pickerItems(): List<PickerItem> {
        pickerCache?.let { cached ->
            if (android.os.SystemClock.elapsedRealtime() - localIdsAt > LOCAL_IDS_TTL_MS) refreshLocalIds()
            return cached
        }
        val all = records.entries()
        val localIds = localIdsCache ?: emptySet()
        if (localIdsCache == null || android.os.SystemClock.elapsedRealtime() - localIdsAt > LOCAL_IDS_TTL_MS) refreshLocalIds()
        val out = all.groupBy { it.provider to it.remoteId }.values
            .map { group -> group.firstOrNull { it.mediaId in localIds } ?: group.first() }
            .distinctBy { it.hash.ifEmpty { it.provider.name + it.remoteId } }
            .map { r ->
                val local = r.mediaId.takeIf { it in localIds }?.let {
                    val base = if (r.mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    android.content.ContentUris.withAppendedId(base, it)
                }
                PickerItem(
                    pickerId(r.provider, r.remoteId),
                    CloudEntry(r.provider, r.remoteId, r.encrypted, r.name, r.album, r.mime, r.takenAt, r.size, r.hash, r.mediaId, r.modified, r.thumb, r.keyId, r.motion),
                    r.uploadedAt.coerceAtLeast(1),
                    local,
                )
            }
        pickerCache = out
        return out
    }

    /** 从网盘删掉的照片，给选择器做增量同步。 */
    fun pickerDeleted(): List<Pair<String, Long>> =
        names.getStringSet(KEY_PICKER_DELETED, emptySet())!!.mapNotNull { s ->
            val i = s.lastIndexOf('|')
            if (i <= 0) null else s.substring(0, i) to (s.substring(i + 1).toLongOrNull() ?: 0L)
        }

    fun pickerEntry(id: String): CloudEntry? {
        val ordinal = id.substringBefore('_').toIntOrNull() ?: return null
        val p = CloudProvider.fromOrdinal(ordinal) ?: return null
        val remoteId = id.substringAfter('_')
        return records.entries().firstOrNull { it.provider == p && it.remoteId == remoteId }?.let { r ->
            CloudEntry(r.provider, r.remoteId, r.encrypted, r.name, r.album, r.mime, r.takenAt, r.size, r.hash, r.mediaId, r.modified, r.thumb, r.keyId, r.motion)
        }
    }

    /**
     * 这张网盘照片手机上还在不在，和 [pickerItems] 的 localUri 同一判定（用后台查好的缓存，不在调用里查 MediaStore）。
     * 记录里的 mediaId > 0 不够：手机上刚删掉、记录还没改过来的照片也是正数。还没查过时当作不在。
     */
    fun onDevice(e: CloudEntry): Boolean = e.mediaId > 0 && localIdsCache?.contains(e.mediaId) == true

    private fun existingMediaIds(ids: List<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        val uri = MediaStore.Files.getContentUri("external")
        ids.chunked(500).forEach { chunk ->
            runCatching {
                app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})", null, null)?.use { c ->
                    while (c.moveToNext()) out += c.getLong(0)
                }
            }
        }
        return out
    }

    private fun rememberPickerDeleted(list: List<RecordEntry>) {
        val now = System.currentTimeMillis()
        val set = names.getStringSet(KEY_PICKER_DELETED, emptySet())!!.toMutableSet()
        list.forEach { set += pickerId(it.provider, it.remoteId) + "|" + now }
        // 只留最近的一些，选择器每次同步都会读
        val trimmed = if (set.size > 2000) set.sortedByDescending { it.substringAfterLast('|') }.take(2000).toSet() else set
        names.edit().putStringSet(KEY_PICKER_DELETED, trimmed).apply()
    }

    // ---------- 网盘里的照片：查看、恢复、删除、释放空间 ----------

    /** 手动备份选中的照片（不受相簿规则和“自动备份”开关限制）。 */
    fun backupItems(ids: Collection<Long>) {
        val set = names.getStringSet(KEY_FORCED, emptySet())!!.toMutableSet()
        ids.forEach { set += it.toString() }
        names.edit().putStringSet(KEY_FORCED, set).apply()
        // 用户亲手选的：不管“仅 Wi‑Fi”“仅充电”，有网就马上传。单独一个任务，不受自动备份任务的网络条件限制；
        // 正在跑的那一轮传完手上这张就让出来，下一轮先传这些
        if (_status.value.running) {
            rerun = true
            urgent = true
        }
        _status.update { it.copy(message = if (!isOnline()) tr(R.string.backup_offline) else null, manual = true) }
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf(BackupWorker.KEY_MANUAL to true, BackupWorker.KEY_PICKED to true))
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(FORCED_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun removeForced(id: Long) {
        val set = names.getStringSet(KEY_FORCED, emptySet())!!.toMutableSet()
        if (set.remove(id.toString())) names.edit().putStringSet(KEY_FORCED, set).apply()
    }

    /** 这些照片里，内容和网盘上的备份完全一致、可以放心从本机删掉的那些。 */
    fun safelyBackedUp(items: List<MediaItem>): List<MediaItem> {
        val byMedia = _entries.value.groupBy { it.mediaId }
        return items.filter { i -> byMedia[i.id].orEmpty().any { it.modified == i.modifiedAt && it.size == i.sizeBytes } }
    }

    private fun thumbKey(e: CloudEntry) = thumbKeyOf(e.provider, e.remoteId, e.hash)

    private fun thumbKeyOf(p: CloudProvider, remoteId: String, hash: String) = hash.ifEmpty { p.name + "-" + remoteId }

    /** 缩略图缓存最多留 [THUMB_CACHE_BYTES]，超出时先删最久没用过的。 */
    /** 上次整理缩略图缓存的时间（elapsedRealtime）。 */
    private val thumbTrimmedAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 下载一直失败的无指纹记录（见 [backfill]）上次失败的时间。只记在内存里。 */
    private val hashlessFailedAt = java.util.concurrent.ConcurrentHashMap<Pair<CloudProvider, String>, Long>()

    private fun trimThumbCache() {
        // 每次没命中都列一遍目录（上万个文件）太费：最多每分钟整理一次，超出上限一点没关系
        val now = android.os.SystemClock.elapsedRealtime()
        val last = thumbTrimmedAt.get()
        if (last != 0L && now - last < THUMB_TRIM_INTERVAL_MS) return
        if (!thumbTrimmedAt.compareAndSet(last, now)) return
        val files = thumbDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= THUMB_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= THUMB_CACHE_BYTES * 9 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    private fun entryOf(r: RecordEntry) =
        CloudEntry(r.provider, r.remoteId, r.encrypted, r.name, r.album, r.mime, r.takenAt, r.size, r.hash, r.mediaId, r.modified, r.thumb, r.keyId, r.motion)

    /** 网盘照片的缩略图：先用本机存的，没有就把原图下回来现做一张。 */
    suspend fun thumbnail(e: CloudEntry): File = withContext(Dispatchers.IO) {
        val f = File(thumbDir, thumbKey(e) + ".jpg")
        if (f.isFile && f.length() > 0) {
            // 记一下最近用过（缓存满时先删最久没看的）；一天内只改一次，省得频繁写
            val now = System.currentTimeMillis()
            if (now - f.lastModified() > 86_400_000) f.setLastModified(now)
            return@withContext f
        }
        trimThumbCache()
        // 预览、查看、下载回手机都是用户自己点的：用流量也照样加载。“仅 Wi‑Fi”只管后台备份
        if (e.thumb.isNotEmpty()) {
            val out = java.io.ByteArrayOutputStream()
            retrying { out.reset(); drive(e.provider).download(e.thumb, out) }
            val raw = out.toByteArray()
            val jpeg = if (e.encrypted) {
                val k = keyFor(e.keyId) ?: throw IOException(tr(R.string.e2ee_unlock_first_short))
                try { Zylc.decrypt(raw, k, BlobKind.THUMBNAIL) } catch (ex: app.zyll.crypto.ZyllCryptoException) { throw CorruptBackupException(ex) }
            } else raw
            thumbDir.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(jpeg)
            tmp.renameTo(f)
            return@withContext f
        }
        // 以前的备份没有网盘缩略图：视频不为了一张缩略图下载整段，图片才下载原图来做
        if (e.isVideo) throw IOException(tr(R.string.thumb_none))
        val original = original(e)
        val bmp = if (e.isVideo) {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(original.path)
                r.frameAtTime
            } finally {
                r.release()
            }
        } else {
            decodeSampled(original, THUMB_PX)
        } ?: throw IOException(tr(R.string.thumb_failed))
        writeThumb(bmp, f)
        f
    }

    /** 原图缓存文件名（不含扩展名）。 */
    private fun viewName(p: CloudProvider, remoteId: String) = "${p.name}-${remoteId.replace(Regex("[^A-Za-z0-9_-]"), "_")}"

    /**
     * 把网盘里的原图下载（加密的顺便解密）到缓存，返回本地文件。
     * [onProgress] 收到 0..1 的下载进度（按记录里的文件大小估算，大小不知道时不报）。
     */
    suspend fun original(e: CloudEntry, onProgress: ((Float) -> Unit)? = null): File = withContext(Dispatchers.IO) {
        val lock = synchronized(downloadLocks) { downloadLocks.getOrPut(e.key) { Mutex() } }
        lock.withLock {
            viewDir.mkdirs()
            val ext = e.name.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "bin"
            val target = File(viewDir, "${viewName(e.provider, e.remoteId)}.$ext")
            if (target.isFile && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            val tmp = File(viewDir, target.name + ".part")
            try {
                retrying {
                    tmp.outputStream().use { raw ->
                        val out = if (onProgress != null && e.size > 0) ProgressOutputStream(raw, e.size, onProgress) else raw
                        drive(e.provider).download(e.remoteId, out)
                    }
                }
                if (e.encrypted) {
                    val k = keyFor(e.keyId) ?: throw IOException(tr(R.string.e2ee_photo_locked))
                    try {
                        // 要求是原图（不能被换成一张缩略图的密文）
                        tmp.inputStream().use { input -> target.outputStream().use { Zylc.decryptStream(input, k, it, BlobKind.ORIGINAL) } }
                    } catch (ex: app.zyll.crypto.ZyllCryptoException) {
                        throw CorruptBackupException(ex)
                    }
                    tmp.delete()
                } else if (!tmp.renameTo(target)) {
                    throw IOException(tr(R.string.cache_write_failed))
                }
                // 核对内容指纹：网盘上的文件被调包（换成了另一张照片的密文）时解得开，但内容对不上
                if (e.hash.isNotEmpty() && !fileSha(target).equals(e.hash, ignoreCase = true)) throw CorruptBackupException(null)
            } catch (ex: Exception) {
                tmp.delete()
                target.delete()
                throw ex
            }
            trimViewCache()
            target
        }
    }

    /** 数写进去的字节，换算成进度；每多 1% 报一次。重试时重新开一个，进度从头算。 */
    private class ProgressOutputStream(
        private val target: java.io.OutputStream,
        private val total: Long,
        private val onProgress: (Float) -> Unit,
    ) : java.io.OutputStream() {
        private var written = 0L
        private var reported = -1
        override fun write(b: Int) { target.write(b); advance(1) }
        override fun write(b: ByteArray, off: Int, len: Int) { target.write(b, off, len); advance(len.toLong()) }
        override fun flush() = target.flush()
        override fun close() = target.close()
        private fun advance(n: Long) {
            written += n
            // 加密的文件比原图略大：封顶 99%，下完（解密、核对）后由调用方收尾
            val pct = (written * 100 / total).toInt().coerceIn(0, 99)
            if (pct != reported) { reported = pct; onProgress(pct / 100f) }
        }
    }

    private fun trimViewCache() {
        val files = viewDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= VIEW_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** 把网盘里的照片下载回手机相册。返回成功的数量。 */
    suspend fun restore(list: List<CloudEntry>, repo: MediaRepository, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) { exclusive { restoreLocked(list, repo, onProgress) } }

    private suspend fun restoreLocked(list: List<CloudEntry>, repo: MediaRepository, onProgress: (Int, Int) -> Unit): Int {
        var ok = 0
        val all = records.entries()
        val todo = list.distinctBy { it.hash.ifEmpty { it.key } }
        todo.forEachIndexed { i, e ->
            // 成功失败都算处理过一张
            onProgress(i, todo.size)
            try {
                val f = original(e)
                val folder = (if (e.isVideo) "Movies/" else "Pictures/") + e.album.ifBlank { "Zyll" }.replace('/', '_')
                val uri = repo.saveNew(e.name.ifBlank { "photo.jpg" }, e.mime.ifBlank { "image/jpeg" }, folder, e.takenAt) { out ->
                    f.inputStream().use { it.copyTo(out, 256 * 1024) }
                }
                val id = android.content.ContentUris.parseId(uri)
                var modified = 0L
                var size = f.length()
                app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        modified = c.getLong(0) * 1000
                        size = c.getLong(1)
                    }
                }
                // 恢复回来的照片直接算作“已备份”，不用再传一遍
                all.filter { r -> (e.hash.isNotEmpty() && r.hash == e.hash) || (r.provider == e.provider && r.remoteId == e.remoteId) }
                    .distinctBy { it.provider }
                    .forEach { r -> records.put(r.copy(mediaId = id, modified = modified, size = size)) }
                ok++
            } catch (ex: IOException) {
                if (ok == 0 && list.size == 1) throw ex
            }
        }
        onProgress(todo.size, todo.size)
        markIndexDirty()
        reloadRecords()
        return ok
    }

    /**
     * 修改网盘里一张照片的名称、拍摄时间、位置。只改名时只改记录；改时间或位置要把文件下载下来改好再传上去，
     * 原来的文件随后删除。加密的照片改完仍然加密。
     */
    suspend fun editCloud(
        e: CloudEntry,
        name: String?,
        takenAt: Long?,
        location: Pair<Double, Double>?,
        clearLocation: Boolean,
    ) = withContext(Dispatchers.IO) {
        exclusive {
            checkWritable(e.provider)
            editLocked(e, name, takenAt, location, clearLocation)
        }
    }

    private suspend fun editLocked(e: CloudEntry, name: String?, takenAt: Long?, location: Pair<Double, Double>?, clearLocation: Boolean) {
        val ext = e.name.substringAfterLast('.', "")
        val newName = name?.trim()?.replace('/', '_')?.removeSuffix(".$ext")?.takeIf { it.isNotEmpty() }
            ?.let { if (ext.isNotEmpty()) "$it.$ext" else it } ?: e.name
        val group = records.entries().filter { it.provider == e.provider && it.remoteId == e.remoteId }
        if (group.isEmpty()) throw IOException(tr(R.string.cloud_photo_gone))
        val exifWritable = !e.isVideo && e.mime in setOf("image/jpeg", "image/png", "image/webp")
        val rewrite = exifWritable && (takenAt != null || location != null || clearLocation)
        if (!rewrite) {
            group.forEach { records.put(it.copy(name = newName, takenAt = takenAt ?: it.takenAt)) }
        } else {
            val src = original(e)
            val dir = File(app.cacheDir, "cloud-edit").apply { mkdirs() }
            val tmp = File(dir, "edit.$ext")
            src.copyTo(tmp, overwrite = true)
            try {
                val exif = androidx.exifinterface.media.ExifInterface(tmp.path)
                if (takenAt != null) {
                    val text = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(takenAt))
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, text)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, text)
                }
                if (location != null) exif.setLatLong(location.first, location.second)
                if (clearLocation) GPS_TAGS.forEach { exif.setAttribute(it, null) }
                exif.saveAttributes()
                // 按现在的加密开关传：刚开启加密、这张还没转换时，改完也直接加密，不再按明文传一次
                val encrypt = vault.wantEncrypted
                val key = if (encrypt) vault.wrapKey() ?: throw IOException(tr(R.string.e2ee_unlock_first_short)) else null
                val drive = drive(e.provider)
                val size = tmp.length()
                val newId = journaled(e.provider, uploadFile(newName, e.album, e.mime, size, takenAt ?: e.takenAt, encrypt, key) { tmp.inputStream() }) { f -> retrying { drive.upload(f) } }
                val hash = fileSha(tmp)
                // 编辑期间网页或别的手机把这张删了：改好的文件不留
                if (deletedMeanwhile(e.provider, e.remoteId)) {
                    deleteNowOrLater(e.provider, newId)
                    throw IOException(tr(R.string.cloud_photo_gone))
                }
                // 改完用当前密钥重新加密了（缩略图下一轮按新形式补传）
                // 形式没变就沿用网盘上原来的缩略图；形式变了（明文 ↔ 加密）原来那张不能留
                val oldThumb = group.first().thumb
                val keepThumb = encrypt == e.encrypted && e.keyId.isEmpty()
                group.forEach {
                    records.put(it.copy(remoteId = newId, name = newName, takenAt = takenAt ?: it.takenAt, size = size, hash = hash, keyId = "", encrypted = encrypt, thumb = if (keepThumb) it.thumb else ""))
                }
                if (!keepThumb && oldThumb.isNotEmpty()) deleteNowOrLater(e.provider, oldThumb)
                rememberPickerDeleted(group.take(1))
                discardRemote(e.provider, e.remoteId, "")
                markReplaced(e.provider, e.remoteId, newId)
                // 改好的文件直接放进查看缓存，缩略图换成新的
                File(viewDir, "${e.provider.name}-${newId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.${ext.ifEmpty { "bin" }}").let { tmp.copyTo(it, overwrite = true) }
                src.delete()
                File(thumbDir, thumbKey(e) + ".jpg").delete()
                // 用改好的文件做一张新缩略图；形式变了的顺便传一张新形式的到网盘
                runCatching {
                    val f = File(thumbDir, hash + ".jpg")
                    decodeSampled(tmp, THUMB_PX)?.let { writeThumb(it, f) }
                    if (!keepThumb && f.isFile) {
                        val id = uploadThumb(e.provider, f, encrypt, key)
                        records.byRemote(e.provider, newId).forEach { records.put(it.copy(thumb = id)) }
                    }
                }
            } finally {
                tmp.delete()
            }
        }
        markIndexDirty()
        reloadRecords()
    }

    private fun fileSha(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    /** 从网盘里永久删除这些备份。返回成功的数量。 */
    suspend fun deleteFromCloud(list: List<CloudEntry>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) { exclusive { deleteLocked(list, onProgress) } }

    private suspend fun deleteLocked(list: List<CloudEntry>, onProgress: (Int, Int) -> Unit): Int {
        var ok = 0
        val all = records.entries()
        val targets = list.flatMap { e ->
            all.filter { r -> (e.hash.isNotEmpty() && r.hash == e.hash) || (r.provider == e.provider && r.remoteId == e.remoteId) }
        }.distinctBy { it.provider to it.remoteId }
        onProgress(0, targets.size)
        for (r in targets) {
            // 删不掉（断网）的记进待删除队列，下次联网接着删；在相册里马上就看不到了
            discardRemote(r.provider, r.remoteId, r.thumb)
            records.removeRemote(r.provider, r.remoteId)
            rememberPickerDeleted(listOf(r))
            File(thumbDir, r.hash.ifEmpty { r.provider.name + "-" + r.remoteId } + ".jpg").delete()
            // 看过的原图解密后留在缓存里（明文）：一起删掉，不等缓存自己淘汰
            viewDir.listFiles()?.filter { it.name.startsWith(viewName(r.provider, r.remoteId) + ".") }?.forEach { it.delete() }
            ok++
            onProgress(ok, targets.size)
        }
        markIndexDirty()
        reloadRecords()
        // 网盘空间变了，下次重新查
        if (ok > 0) accountCheckedAt.clear()
        return ok
    }

    private fun saveThumb(item: MediaItem, key: String): File? {
        val f = File(thumbDir, "$key.jpg")
        if (f.isFile && f.length() > 0) return f
        val bmp = if (Build.VERSION.SDK_INT >= 29) {
            app.contentResolver.loadThumbnail(item.uri, android.util.Size(THUMB_PX, THUMB_PX), null)
        } else if (item.isVideo) {
            null
        } else {
            val tmp = File(app.cacheDir, "thumb-src")
            try {
                open(item).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                decodeSampled(tmp, THUMB_PX)
            } finally {
                tmp.delete()
            }
        } ?: return null
        writeThumb(bmp, f)
        return f.takeIf { it.isFile }
    }

    /** 把小缩略图传到网盘（开着加密就加密），返回文件 id。 */
    /**
     * 会变成记录的上传（原图、缩略图）都走这里：传之前在本机记一笔，传完补上文件 id（见 [RecordStore.uploadStarted]）。
     * 写进记录以后这笔就没用了，每轮清掉；对账时只清理这份清单里没人用的文件。
     */
    private suspend fun journaled(p: CloudProvider, file: UploadFile, upload: suspend (UploadFile) -> String): String {
        val token = java.util.UUID.randomUUID().toString()
        records.uploadStarted(p, token, file.path.last(), System.currentTimeMillis())
        val id = upload(file)
        records.uploadFinished(p, token, id)
        return id
    }

    private suspend fun uploadThumb(p: CloudProvider, f: File, encrypt: Boolean, key: ByteArray?): String {
        val plain = f.readBytes()
        val bytes = if (encrypt) Zylc.encrypt(plain, BlobKind.THUMBNAIL, key ?: throw IOException(tr(R.string.e2ee_encryption_key_missing))) else plain
        val name = ByteArray(12).also { SecureRandom().nextBytes(it) }.toHex()
        val path = if (encrypt) listOf(CloudConfig.ENCRYPTED_FOLDER, "t-$name.zylc") else listOf(THUMB_FOLDER, "$name.jpg")
        val file = UploadFile(path, if (encrypt) "application/octet-stream" else "image/jpeg", bytes.size.toLong(), System.currentTimeMillis()) { bytes.inputStream() }
        return journaled(p, file) { f -> retrying { drive(p).upload(f) } }
    }

    private fun writeThumb(bmp: android.graphics.Bitmap, f: File) {
        thumbDir.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        tmp.renameTo(f)
    }

    /** 按目标边长缩小解码，并按 EXIF 方向转正。 */
    private fun decodeSampled(file: File, target: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotation = runCatching {
            when (androidx.exifinterface.media.ExifInterface(file.path).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rotation) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    // ---------- 网盘里的备份目录：换手机、重装后靠它找回照片 ----------

    /** 这个账号的网盘目录已经包含了本机全部改动。 */
    private fun indexSynced(p: CloudProvider): Boolean =
        names.getLong(KEY_INDEX_PUSHED + p.name, -1) == names.getLong(KEY_INDEX_VERSION, 0)

    private fun markIndexDirty() = names.edit()
        .putBoolean(KEY_INDEX_DIRTY, true)
        .putInt(KEY_INDEX_PENDING, names.getInt(KEY_INDEX_PENDING, 0) + 1)
        .putLong(KEY_INDEX_VERSION, names.getLong(KEY_INDEX_VERSION, 0) + 1)
        .apply()

    /** 备份目录里的一项（字段名就是目录 JSON 里的键）。 */
    private class IndexItem(
        val r: String, val e: Boolean, val n: String, val a: String, val m: String, val t: Long, val s: Long,
        val h: String, val th: String, val d: String,
        /** 用哪套密钥加密的（和目录不是同一套时才写）。 */
        var k: String,
        /** 动态照片（mo），只是显示用的提示；没有这个字段不代表不是。 */
        val mo: Boolean = false,
    )

    /** 读出来的一份目录文件。 */
    private class IndexDoc(
        val seq: Long,
        val hasSeq: Boolean,
        val items: List<IndexItem>,
        val del: List<String>,
        val settings: JSONObject?,
        /** 这个账号最近一次的加密选择：开没开、什么时候改的（见 [modeConflicts]）。 */
        val mode: Pair<Boolean, Long>?,
        /** 写这份目录的手机还没删掉的旧文件（换手机后新手机接着删）。 */
        val trash: List<String>,
        /** 搬存放位置时换过的文件 id：旧 → (新, 时间)。 */
        val mv: Map<String, Pair<String, Long>> = emptyMap(),
    )

    /** 从网盘读回来的备份目录（加密和不加密的两份合在一起）。 */
    private class PulledIndex(
        val items: List<IndexItem>,
        val deleted: Set<String>,
        val settings: JSONObject?,
        val seq: Long,
        val encId: String?,
        val plainId: String?,
        /** 加密的那份读到并合并了（没有密钥、用户放弃了的旧加密目录不读）。 */
        val encMerged: Boolean,
        /** 每个文件是哪台手机传的（旧版本写的目录没有）。 */
        val devices: Map<String, String>,
        /** 别的手机的记录在用的文件。 */
        val foreign: Set<String>,
        val mode: Pair<Boolean, Long>?,
        val trash: Set<String>,
        /** 明文那份读到并合并了。没合并的（开着加密时不信任它）不能删。 */
        val plainMerged: Boolean,
        /** 读到了、但没合并的明文目录里引用的文件（只用来防误删，不加进记录）。 */
        val unmergedRefs: Set<String>,
        /** 其中别的手机写进去的那些（这台手机自己以前写的已经在加密目录里了）。 */
        val unmergedForeign: Set<String>,
        /** 这个账号的每一份目录都读到了（没有因为没密钥、放弃过、坏了被跳过的）。 */
        val complete: Boolean,
        /** 目录里带的“搬存放位置时换过的 id”（上面的各项已经按它换成新 id 了）。 */
        val moves: Map<String, Pair<String, Long>> = emptyMap(),
    ) {
        val exists get() = encId != null || plainId != null
    }

    /** 网盘上的备份目录读不了的原因（界面上给出处理办法）。 */
    enum class IndexIssue { Corrupt, Rollback }


    private fun refreshIndexIssues() {
        _indexIssues.value = CloudProvider.entries.mapNotNull { p ->
            when {
                names.contains(KEY_INDEX_CORRUPT + p.name) -> p to IndexIssue.Corrupt
                names.getBoolean(KEY_INDEX_ROLLBACK + p.name, false) -> p to IndexIssue.Rollback
                else -> null
            }
        }.toMap()
    }

    private class IndexCorruptException : IOException(tr(R.string.backup_index_corrupt))

    /** 流式读一份目录 JSON（几十万项也不用把整个文件读进内存）。格式不对时抛 [IndexCorruptException]。 */
    private fun parseIndex(file: File): IndexDoc = try {
        android.util.JsonReader(file.inputStream().buffered().reader(Charsets.UTF_8)).use { rd ->
            var seq = 0L
            var hasSeq = false
            val items = ArrayList<IndexItem>()
            val del = ArrayList<String>()
            val trash = ArrayList<String>()
            var settings: JSONObject? = null
            var mode: Pair<Boolean, Long>? = null
            val mv = HashMap<String, Pair<String, Long>>()
            rd.beginObject()
            while (rd.hasNext()) {
                when (rd.nextName()) {
                    "seq" -> { seq = rd.nextLong(); hasSeq = true }
                    "mv" -> {
                        rd.beginArray()
                        while (rd.hasNext()) {
                            var o = ""; var n = ""; var at = 0L
                            rd.beginObject()
                            while (rd.hasNext()) {
                                val name = rd.nextName()
                                if (rd.peek() == android.util.JsonToken.NULL) { rd.nextNull(); continue }
                                when (name) { "o" -> o = rd.nextString(); "n" -> n = rd.nextString(); "at" -> at = rd.nextLong(); else -> rd.skipValue() }
                            }
                            rd.endObject()
                            if (o.isNotEmpty() && n.isNotEmpty() && o != n) mv[o] = n to at
                        }
                        rd.endArray()
                    }
                    "items" -> {
                        rd.beginArray()
                        while (rd.hasNext()) {
                            var r = ""; var e = false; var n = ""; var a = ""; var m = ""; var t = 0L; var sz = 0L
                            var h = ""; var th = ""; var d = ""; var k = ""; var mo = false
                            rd.beginObject()
                            while (rd.hasNext()) {
                                val name = rd.nextName()
                                if (rd.peek() == android.util.JsonToken.NULL) { rd.nextNull(); continue }
                                when (name) {
                                    "r" -> r = rd.nextString()
                                    "e" -> e = rd.nextBoolean()
                                    "n" -> n = rd.nextString()
                                    "a" -> a = rd.nextString()
                                    "m" -> m = rd.nextString()
                                    "t" -> t = rd.nextLong()
                                    "s" -> sz = rd.nextLong()
                                    "h" -> h = rd.nextString()
                                    "th" -> th = rd.nextString()
                                    "d" -> d = rd.nextString()
                                    "k" -> k = rd.nextString()
                                    // 只认 true（和网页版一致）；别的类型跳过，不当成目录损坏
                                    "mo" -> if (rd.peek() == android.util.JsonToken.BOOLEAN) mo = rd.nextBoolean() else rd.skipValue()
                                    else -> rd.skipValue()
                                }
                            }
                            rd.endObject()
                            items += IndexItem(r, e, n, a, m, t, sz, h, th, d, k, mo)
                        }
                        rd.endArray()
                    }
                    "del" -> { rd.beginArray(); while (rd.hasNext()) del += rd.nextString(); rd.endArray() }
                    "trash" -> { rd.beginArray(); while (rd.hasNext()) trash += rd.nextString(); rd.endArray() }
                    "settings" -> settings = if (rd.peek() == android.util.JsonToken.BEGIN_OBJECT) readJsonObject(rd) else { rd.skipValue(); null }
                    "mode" -> {
                        var enc = false; var at = 0L
                        rd.beginObject()
                        while (rd.hasNext()) when (rd.nextName()) { "enc" -> enc = rd.nextBoolean(); "at" -> at = rd.nextLong(); else -> rd.skipValue() }
                        rd.endObject()
                        mode = enc to at
                    }
                    else -> rd.skipValue()
                }
            }
            rd.endObject()
            IndexDoc(seq, hasSeq, items, del, settings, mode, trash, mv)
        }
    } catch (e: IOException) {
        // JsonReader 的格式错误也是 IOException（MalformedJsonException），读文件本身不会在这里断网
        throw IndexCorruptException().apply { initCause(e) }
    } catch (e: RuntimeException) {
        throw IndexCorruptException().apply { initCause(e) }
    }

    private fun readJsonObject(rd: android.util.JsonReader): JSONObject {
        val o = JSONObject()
        rd.beginObject()
        while (rd.hasNext()) o.put(rd.nextName(), readJsonValue(rd))
        rd.endObject()
        return o
    }

    private fun readJsonValue(rd: android.util.JsonReader): Any = when (rd.peek()) {
        android.util.JsonToken.BEGIN_OBJECT -> readJsonObject(rd)
        android.util.JsonToken.BEGIN_ARRAY -> org.json.JSONArray().also { a -> rd.beginArray(); while (rd.hasNext()) a.put(readJsonValue(rd)); rd.endArray() }
        android.util.JsonToken.BOOLEAN -> rd.nextBoolean()
        android.util.JsonToken.NUMBER -> rd.nextString().let { t -> t.toLongOrNull() ?: t.toDouble() }
        android.util.JsonToken.NULL -> { rd.nextNull(); JSONObject.NULL }
        else -> rd.nextString()
    }

    private fun writeJsonValue(w: android.util.JsonWriter, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> w.nullValue()
            is JSONObject -> { w.beginObject(); v.keys().forEach { k -> w.name(k); writeJsonValue(w, v.opt(k)) }; w.endObject() }
            is org.json.JSONArray -> { w.beginArray(); for (i in 0 until v.length()) writeJsonValue(w, v.opt(i)); w.endArray() }
            is Boolean -> w.value(v)
            is Int, is Long -> w.value((v as Number).toLong())
            is Number -> w.value(v)
            else -> w.value(v.toString())
        }
    }

    /**
     * 读网盘上的备份目录。读不到（断网、目录损坏、加密目录还没解锁）就抛异常，调用方据此不去覆盖它。
     * 加密目录在这台手机没有密钥、而且用户已经放弃以前的加密备份（或网盘里根本没有密钥包）时跳过。
     * 本机开着加密、网盘上也有加密目录时，不读明文目录：能写网盘的人放一份明文目录，就能塞进假照片、藏起真照片。
     */
    private suspend fun pullIndex(p: CloudProvider, key: ByteArray?, from: CloudDrive? = null): PulledIndex {
        // [from] 不为空：读搬家前的旧位置（只读出来合并，不记损坏、回滚这些状态，旧目录序号本来就比新位置旧）
        val probe = from != null
        val drive = from ?: drive(p)
        val encId = retrying { drive.findFile(INDEX_ENCRYPTED) }
        val plainId = retrying { drive.findFile(INDEX_PLAIN) }
        val ignored = names.getString(KEY_INDEX_IGNORE + p.name, null)
        val dir = File(app.cacheDir, "index-pull").apply { mkdirs() }
        val raw = File(dir, "raw-${p.name}")
        val dec = File(dir, "dec-${p.name}")
        try {
            suspend fun download(id: String) = retrying { raw.outputStream().use { drive.download(id, it) } }
            fun tryDecrypt(k: ByteArray): Boolean = try {
                raw.inputStream().buffered().use { i -> dec.outputStream().buffered().use { o -> Zylc.decryptStream(i, k, o, BlobKind.INDEX_SNAPSHOT) } }
                true
            } catch (e: app.zyll.crypto.ZyllCryptoException) {
                false
            }
            fun parseOrFlag(f: File, id: String): IndexDoc = try {
                parseIndex(f)
            } catch (e: IndexCorruptException) {
                if (probe) throw e
                // 损坏了：记下来让用户处理（以这台手机为准重建），不覆盖它
                names.edit().putString(KEY_INDEX_CORRUPT + p.name, id).apply()
                refreshIndexIssues()
                throw e
            }
            val docs = ArrayList<IndexDoc>()
            var encMerged = false
            // 有任何一份目录这次没读到，就不算完整（对账时不能据此判断哪些文件没人用）
            var complete = true
            if (encId != null && encId == ignored) complete = false
            if (encId != null && encId != ignored) {
                if (key != null) {
                    download(encId)
                    var usedOld: String? = null
                    // 当前密钥解不开：试试暂存的以前那套（用户刚用旧密码解开的账号）
                    val opened = tryDecrypt(key) || vault.oldKeyIds().any { id ->
                        val k = runCatching { vault.oldKey(id) }.getOrNull()
                        (k != null && tryDecrypt(k)).also { if (it) usedOld = id }
                    }
                    if (opened) {
                        val doc = parseOrFlag(dec, encId)
                        // 用以前那套密钥解开的目录：里面没写明密钥的加密项都是用那套加密的
                        usedOld?.let { id -> doc.items.forEach { if (it.e && it.k.isEmpty()) it.k = id } }
                        docs += doc
                        encMerged = true
                    } else if (!probe && !skipUnreadable(p, drive)) {
                        throw IOException(tr(R.string.e2ee_index_cannot_open))
                    } else {
                        complete = false
                    }
                } else if (probe) {
                    complete = false
                } else if (names.contains(KEY_REMOTE_BUNDLE + p.name) || !names.getBoolean(KEY_REMOTE_CHECKED + p.name, false)) {
                    names.edit().putBoolean(KEY_INDEX_LOCKED + p.name, true).apply()
                    throw IOException(tr(R.string.e2ee_index_locked))
                } else {
                    complete = false
                    // 本机没有密钥，网盘上却有加密目录：多半是另一台手机后来开了加密。
                    // 以前只在第一次连接时查一次密钥包，这里隔一段时间重新查，查到就按“有加密备份，待解锁”处理
                    if (recheckRemoteVault(p)) {
                        names.edit().putBoolean(KEY_INDEX_LOCKED + p.name, true).apply()
                        throw IOException(tr(R.string.e2ee_index_locked))
                    }
                }
            }
            val ignorePlain = encMerged && vault.wantEncrypted
            var plainMerged = false
            val unmergedRefs = HashSet<String>()
            val unmergedForeign = HashSet<String>()
            if (plainId != null && plainId == ignored) complete = false
            if (plainId != null && plainId != ignored) {
                download(plainId)
                if (!ignorePlain) {
                    docs += parseOrFlag(raw, plainId)
                    plainMerged = true
                } else {
                    // 开着加密时不合并明文目录（不信任它的内容），但另一台没开加密的手机还在用它：
                    // 里面引用的文件都算“有人在用”，不删
                    try {
                        parseIndex(raw).items.forEach { o ->
                            val refs = listOf(o.r, o.th).filter { it.isNotEmpty() }
                            unmergedRefs += refs
                            if (o.d != deviceId) unmergedForeign += refs
                        }
                    } catch (e: IndexCorruptException) {
                        complete = false
                    }
                }
            }
            // 新的在前：同一个文件两份里都有时用新的那份
            docs.sortByDescending { it.seq }
            val seq = docs.maxOfOrNull { it.seq } ?: 0L
            // 防回滚：网盘上的目录不能比这台手机上次看到的还旧（旧版本写的目录没有序号，不查）
            if (!probe && docs.any { it.hasSeq } && seq < names.getLong(KEY_INDEX_SEQ + p.name, 0) && !names.getBoolean(KEY_INDEX_OVERRIDE + p.name, false)) {
                names.edit().putBoolean(KEY_INDEX_ROLLBACK + p.name, true).apply()
                refreshIndexIssues()
                throw IOException(tr(R.string.backup_index_older))
            }
            if (!probe && (names.contains(KEY_INDEX_CORRUPT + p.name) || names.getBoolean(KEY_INDEX_ROLLBACK + p.name, false))) {
                names.edit().remove(KEY_INDEX_CORRUPT + p.name).remove(KEY_INDEX_ROLLBACK + p.name).apply()
                refreshIndexIssues()
            }
            // 搬存放位置时换过的文件 id（别的设备搬的）：几份目录里的合在一起，下面各项里的旧 id 先换成新的，
            // 这样同一张照片不会因为新旧两个 id 被当成两张，旧 id 的墓碑也能删到新文件
            val moves = HashMap<String, Pair<String, Long>>()
            docs.forEach { d -> d.mv.forEach { (o, nv) -> moves.putIfAbsent(o, nv) } }
            fun moved(id: String) = moves[id]?.first ?: id
            fun moved(o: IndexItem) =
                if (o.r !in moves && o.th !in moves) o
                else IndexItem(moved(o.r), o.e, o.n, o.a, o.m, o.t, o.s, o.h, if (o.th.isEmpty()) "" else moved(o.th), o.d, o.k, o.mo)
            val deleted = HashSet<String>()
            docs.forEach { d -> d.del.forEach { deleted += moved(it) } }
            val seen = HashSet<String>()
            val items = ArrayList<IndexItem>()
            docs.forEach { d -> d.items.forEach { o0 -> val o = moved(o0); if (o.r.isNotEmpty() && o.r !in deleted && seen.add(o.r)) items += o } }
            val devices = items.associate { it.r to it.d }.filterValues { it.isNotEmpty() }
            // 同一个文件可能几台手机都在用（相同照片只存一份）：只要有一份记录不是这台手机的就算共享
            val foreign = HashSet<String>()
            docs.forEach { d -> d.items.forEach { o -> if (o.d.isNotEmpty() && o.d != deviceId) foreign += moved(o.r) } }
            val mode = docs.firstNotNullOfOrNull { it.mode }
            if (!probe) mode?.let { (enc, at) -> names.edit().putString(KEY_REMOTE_MODE + p.name, (if (enc) "1|" else "0|") + at).apply() }
            val trash = HashSet<String>()
            docs.forEach { d -> d.trash.forEach { trash += moved(it) } }
            return PulledIndex(
                items, deleted, docs.firstNotNullOfOrNull { it.settings }, seq, encId, plainId, encMerged, devices, foreign, mode, trash,
                plainMerged, unmergedRefs.mapTo(HashSet()) { moved(it) }, unmergedForeign.mapTo(HashSet()) { moved(it) }, complete, moves,
            )
        } finally {
            raw.delete()
            dec.delete()
        }
    }

    /**
     * 加密目录用这台手机的密钥都解不开：看看网盘上现在放的是哪套密钥包再决定。
     * - 网盘上没有密钥包（或者坏了）：谁也打不开它，跳过（之后会被覆盖）；
     * - 是用户放弃过的那套：跳过；
     * - 就是这台手机这套：密钥对、却解不开，说明目录坏了，按损坏处理（不覆盖）；
     * - 是另一套（别的手机“放弃旧备份、重新开始”了）：进入密钥冲突，等用户处理，绝不覆盖。
     * 返回 true 表示可以跳过。
     */
    private suspend fun skipUnreadable(p: CloudProvider, drive: CloudDrive): Boolean {
        val id = retrying { drive.findFile(KEYBUNDLE_PATH) } ?: return true
        val out = java.io.ByteArrayOutputStream()
        retrying { out.reset(); drive.download(id, out) }
        val text = String(out.toByteArray())
        val theirs = runCatching { KeyBundle.parse(text).vaultId }.getOrNull() ?: return true
        val abandoned = names.getString(KEY_REMOTE_ABANDONED + p.name, null)
        return when {
            theirs == abandoned -> true
            theirs == vault.vaultId -> {
                names.edit().putString(KEY_INDEX_CORRUPT + p.name, "enc").apply()
                refreshIndexIssues()
                false
            }
            theirs in vault.oldKeyIds() -> false
            else -> {
                names.edit().putString(KEY_REMOTE_BUNDLE + p.name, text).apply()
                refreshUnlockState()
                false
            }
        }
    }

    /**
     * 把网盘目录合进本机记录：别的手机删掉的文件，本机记录也删；本机没有的文件，加成“只在网盘里”的记录；
     * 别的手机没删掉的旧文件，这台手机也接着删（自己还在用的除外）。整个合并在一个事务里。返回有没有改动。
     */
    private fun mergePulled(p: CloudProvider, pulled: PulledIndex): Boolean {
        // 别的设备搬存放位置时换了文件 id：记下对照表（以后这台写目录时也带上，传给还没看到的设备），
        // 再把本机记录、墓碑、待删、共享标记里的旧 id 换成新的。只换本机真有的，几万条对照也不慢
        var remapped = false
        if (pulled.moves.isNotEmpty()) {
            records.addIdMoves(p, pulled.moves)
            val present = HashSet<String>()
            records.entries().forEach { if (it.provider == p) { present += it.remoteId; if (it.thumb.isNotEmpty()) present += it.thumb } }
            present += tombstones(p)
            present += records.pendingDeletesOf(p, Int.MAX_VALUE)
            val apply = pulled.moves.filterKeys { it in present }.mapValues { it.value.first }
            if (apply.isNotEmpty()) {
                records.remapIds(p, apply)
                markIndexDirty()
                remapped = true
            }
        }
        val mine = records.entries().filter { it.provider == p }
        val known = mine.mapTo(HashSet()) { it.remoteId }
        val gone = tombstones(p)
        var changed = false
        val removed = ArrayList<RecordEntry>()
        val pushed = names.getLong(KEY_INDEX_PUSHED + p.name, -1)
        val remoteIds = pulled.items.mapTo(HashSet()) { it.r }
        val extraDeleted = ArrayList<String>()
        records.transaction {
            for (r0 in pulled.deleted) {
                var r = r0
                // 旧文件刚被这台手机换成新文件、替换还没写进这个网盘的目录：这个墓碑是网页或别的手机打的，删的就是换上去的新文件
                // （顺着替换链找，转换后又编辑过的也能找到）
                // 网盘目录里已经有新文件，说明这次替换已经传上去了，墓碑是这台手机自己打的，不算
                if (r !in known) {
                    var hops = 0
                    while (r !in known && hops++ < 4) {
                        val (next, at) = records.replacementOf(p, r) ?: break
                        if (at <= pushed || next in remoteIds) break
                        r = next
                    }
                    if (r !in known) continue
                    extraDeleted += r
                }
                removed += mine.filter { it.remoteId == r }
                records.removeRemote(p, r)
                changed = true
            }
            // 顺着替换删掉的新文件：网盘上也删（连同缩略图），目录里记成已删除
            for (r in extraDeleted) {
                records.enqueueDelete(p, r)
                mine.filter { it.remoteId == r && it.thumb.isNotEmpty() }.forEach { records.enqueueDelete(p, it.thumb) }
            }
            records.addTombstones(p, pulled.deleted - gone + extraDeleted, TOMBSTONE_MAX)
            // 新文件的墓碑要写进目录，别的设备才会跟着删
            if (extraDeleted.isNotEmpty()) markIndexDirty()
            // 别的手机的记录也在用的文件：这台手机替换旧版本时不能删
            records.markShared(p, pulled.foreign)
            for (o in pulled.items) {
                // 动态照片标记只加不减（同一个文件 id 的内容不会变）：别的设备认出来的，补进本机记录，
                // 不然这台手机重写目录时会把它抹掉
                if (o.mo && o.r in known && o.r !in gone && o.r !in pulled.deleted) {
                    records.byRemote(p, o.r).filter { !it.motion }.forEach { records.put(it.copy(motion = true)); changed = true }
                }
                if (o.r in known || o.r in gone || o.r in pulled.deleted) continue
                records.put(
                    RecordEntry(
                        // 不是这台手机的 MediaStore id：用负数，不会和本机照片混淆
                        mediaId = syntheticId(p, o.r),
                        provider = p, modified = 0, size = o.s, remoteId = o.r, encrypted = o.e,
                        name = o.n, album = o.a, mime = o.m, takenAt = o.t, hash = o.h, thumb = o.th,
                        // 目录里写明了是以前另一套密钥加密的；就是当前这套时记成空
                        keyId = o.k.takeIf { it.isNotEmpty() && it != vault.vaultId }.orEmpty(),
                        motion = o.mo,
                    ),
                )
                changed = true
            }
            // 别的手机还没删掉的旧文件：这台手机和目录里都没人用的，接着删
            val inUse = HashSet<String>().apply { addAll(known); pulled.items.forEach { add(it.r); if (it.th.isNotEmpty()) add(it.th) } }
            mine.forEach { if (it.thumb.isNotEmpty()) inUse += it.thumb }
            pulled.trash.filter { it !in inUse }.forEach { records.enqueueDelete(p, it) }
        }
        if (removed.isNotEmpty()) {
            // 别的手机删掉的：系统照片选择器里也要去掉
            rememberPickerDeleted(removed.distinctBy { it.remoteId })
            touchSameHash(removed.map { it.hash })
        }
        return changed || remapped
    }

    /**
     * 把备份目录传到网盘（换手机时靠它找回照片）。目录可能有几 MB，所以不是每次都传：
     * 距上次超过 30 分钟、或者积累了 200 处改动、或者 [force]（加密开关、每次备份任务结束、离开 App 时）才传。
     * 传之前先读回网盘上的目录合并（别的手机新备份的不会被冲掉）；读不回来就不传，绝不覆盖。
     * [key] 用来读加密目录，开着加密（[want]）时也用它加密；开着加密却没有密钥时不传，不会退回传明文。
     * 目录边写边存进临时文件、加密时边读边加密上传，几十万张照片也不会占用上百 MB 内存。
     */
    private suspend fun uploadIndex(connected: Set<CloudProvider>, want: Boolean, key: ByteArray?, force: Boolean = false): Set<CloudProvider> {
        // 全局没有改动、而且每个账号的目录都已经包含了最新版本时才跳过（之前没就绪的账号现在就绪了，也要补传）
        val current = names.getLong(KEY_INDEX_VERSION, 0)
        if (!names.getBoolean(KEY_INDEX_DIRTY, true) && connected.all { names.getLong(KEY_INDEX_PUSHED + it.name, -1) == current }) return emptySet()
        val since = System.currentTimeMillis() - names.getLong(KEY_INDEX_AT, 0)
        if (!force && since < INDEX_INTERVAL_MS && names.getInt(KEY_INDEX_PENDING, 0) < INDEX_BATCH) return emptySet()
        if (want && key == null) return emptySet()
        val version = names.getLong(KEY_INDEX_VERSION, 0)
        // 这次上传成功、而且网盘上已经没有加密目录（没有，或者合并后删掉了）的网盘
        val noEncrypted = HashSet<CloudProvider>()
        var allOk = true
        var merged = false
        var uploadedAny = false
        val tmp = File(app.cacheDir, "index-push").apply { mkdirs() }
        for (p in connected) {
            if (!isReady(p) || _indexIssues.value.containsKey(p)) {
                allOk = false
                continue
            }
            // 别的设备刚把这个账号的备份全部删除了：不再把目录传回去
            if (runCatching { wipedElsewhere(p, WIPE_CHECK_FRESH_MS) }.getOrDefault(true)) {
                allOk = false
                continue
            }
            // 这个账号的目录已经是最新版本（比如上次别的账号离线、这个账号已经传过了）：不用再拉下来重传一遍
            if (!force && names.getLong(KEY_INDEX_PUSHED + p.name, -1) == version) continue
            val out = File(tmp, "index-${p.name}.json")
            try {
                val drive = drive(p)
                val pulled = pullIndex(p, key)
                if (mergePulled(p, pulled)) merged = true
                val mine = records.entries().filter { it.provider == p }
                // 过渡期：网盘上的密钥包还是以前那套时，目录也用那套加密，只有那套密钥的手机照样能读
                val (indexKey, indexKeyId) = indexKeyFor(p, key)
                // 设置也跟着账号走（不含密钥）。这台手机还没决定要不要恢复以前的设置时，先原样保留网盘上那份
                val settings = names.getString(KEY_REMOTE_SETTINGS, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: JSONObject().put("backup", settingsStore.encode(_settings.value)).also { st ->
                        runCatching { extras?.export() }.getOrNull()?.let { st.put("gallery", it) }
                    }
                val seq = maxOf(pulled.seq, names.getLong(KEY_INDEX_SEQ + p.name, 0)) + 1
                // 这个账号的加密选择：别的手机改得更晚、而且这台手机还没决定跟不跟时，原样保留它的
                val conflict = modeConflicts()[p]
                val mode = conflict?.let { pulled.mode } ?: localMode()
                // 开了端到端加密时目录也加密，网盘上看不出有哪些照片。这台手机关着加密、但这个账号里还有加密的照片
                // （或者另一台手机刚开了加密、还没决定跟不跟）时，有密钥也照样加密，不把加密照片的文件名写成明文
                val encrypt = (want || mine.any { it.encrypted } || conflict != null) && key != null
                android.util.JsonWriter(out.outputStream().buffered().writer(Charsets.UTF_8)).use { w ->
                    w.beginObject()
                    w.name("v").value(2)
                    w.name("seq").value(seq)
                    w.name("items").beginArray()
                    // 没有密钥、只能写明文目录时，加密照片不写进去（它们留在别的手机写的加密目录里）
                    mine.distinctBy { it.remoteId }.filter { encrypt || !it.encrypted }.forEach {
                        w.beginObject()
                        w.name("r").value(it.remoteId).name("e").value(it.encrypted).name("n").value(it.name).name("a").value(it.album)
                            .name("m").value(it.mime).name("t").value(it.takenAt).name("s").value(it.size).name("h").value(it.hash).name("th").value(it.thumb)
                            .name("d").value(if (it.mediaId > 0) deviceId else pulled.devices[it.remoteId].orEmpty())
                        // "k" 记的是和目录不是同一套的密钥；目录用以前那套加密时，当前这套也要写明
                        val k = it.keyId.ifEmpty { if (indexKeyId != null && it.encrypted) vault.vaultId.orEmpty() else "" }
                        if (k.isNotEmpty() && k != indexKeyId) w.name("k").value(k)
                        if (it.motion) w.name("mo").value(true)
                        w.endObject()
                    }
                    w.endArray()
                    // 只带本机表里的（已经合并了读回来的、并按时间裁剪过）
                    w.name("del").beginArray(); tombstones(p).forEach { w.value(it) }; w.endArray()
                    // 还没删掉的旧文件（比如开启加密后要删的明文原图）：这台手机清了数据，新手机也会接着删
                    w.name("trash").beginArray(); records.pendingDeletesOf(p, TRASH_IN_INDEX).forEach { w.value(it) }; w.endArray()
                    if (mode != null) w.name("mode").beginObject().name("enc").value(mode.first).name("at").value(mode.second).endObject()
                    // 搬存放位置时换过的 id（保留 90 天）：还拿着旧 id 的设备、网页按它换掉
                    records.pruneIdMoves(p, System.currentTimeMillis() - ID_MOVES_KEEP_MS)
                    val moves = records.idMoves(p)
                    if (moves.isNotEmpty()) {
                        w.name("mv").beginArray()
                        moves.forEach { (o, nv) -> w.beginObject().name("o").value(o).name("n").value(nv.first).name("at").value(nv.second).endObject() }
                        w.endArray()
                    }
                    w.name("settings"); writeJsonValue(w, settings)
                    w.endObject()
                }
                val plainLen = out.length()
                val file = if (encrypt) {
                    val k = indexKey ?: key!!
                    val cipherLen = ZylcHeader(Zylc.DEFAULT_CHUNK_LOG2, BlobKind.INDEX_SNAPSHOT, ByteArray(16), ByteArray(12), ByteArray(48), plainLen).totalLen
                    UploadFile(INDEX_ENCRYPTED, "application/octet-stream", cipherLen, System.currentTimeMillis(), replace = true) {
                        ZylcEncryptingInputStream(out.inputStream().buffered(), plainLen, BlobKind.INDEX_SNAPSHOT, k)
                    }
                } else {
                    UploadFile(INDEX_PLAIN, "application/octet-stream", plainLen, System.currentTimeMillis(), replace = true) { out.inputStream().buffered() }
                }
                val newId = retrying { drive.upload(file) }
                // 以前版本留下的改了名的旧目录（“index 1.zylc”）
                names.getString(KEY_INDEX_ID + p.name, null)?.takeIf { it != newId && it != pulled.encId && it != pulled.plainId }
                    ?.let { old -> runCatching { drive.delete(old) } }
                val edit = names.edit().putString(KEY_INDEX_ID + p.name, newId).putLong(KEY_INDEX_SEQ + p.name, seq)
                    .putLong(KEY_PULLED_AT + p.name, System.currentTimeMillis())
                    // 这个账号的目录已经包含到哪个版本的改动（释放空间前据此判断记录是不是已经在网盘目录里）
                    .putLong(KEY_INDEX_PUSHED + p.name, version)
                uploadedAny = true
                // 以前跳过的那份坏目录已经被这次覆盖了
                if (names.getString(KEY_INDEX_IGNORE + p.name, null) == newId) edit.remove(KEY_INDEX_IGNORE + p.name)
                edit.remove(KEY_INDEX_OVERRIDE + p.name)
                edit.apply()
                records.pruneReplaced(p, version)
                // 另一种形式的旧目录：已经读到并合并进来了才删。加密目录只在本机拿着密钥、这个网盘也没有加密记录时才删
                if (encrypt) {
                    // 明文目录：读到并合并了才删；没合并的，只有里面别的手机写的条目这台手机全都认识（都在加密目录、
                    // 墓碑或待删除里）时才删，否则另一台没开加密的手机写进去的照片就从网盘目录里消失了。
                    // 这台手机自己以前写的条目已经在加密目录里，不妨碍删（不删的话文件名会一直以明文留在网盘上）
                    val plainId = pulled.plainId
                    if (plainId != null && plainId != newId) {
                        val known = HashSet<String>()
                        mine.forEach { known += it.remoteId; if (it.thumb.isNotEmpty()) known += it.thumb }
                        known += pulled.deleted
                        known += tombstones(p)
                        val safe = pulled.plainMerged || pulled.unmergedForeign.all { it in known || records.inTrash(p, it) }
                        if (safe) runCatching { drive.delete(plainId) }
                    }
                } else if (pulled.encId == null) {
                    noEncrypted += p
                } else if (pulled.encMerged && mine.none { it.encrypted } && pulled.encId != newId) {
                    if (runCatching { retrying { drive.delete(pulled.encId!!) } }.isSuccess) noEncrypted += p
                }
            } catch (_: IOException) {
                allOk = false
            } catch (_: org.json.JSONException) {
                allOk = false
            } catch (_: app.zyll.crypto.ZyllCryptoException) {
                allOk = false
            } finally {
                out.delete()
            }
        }
        if (merged) reloadRecords()
        // 上传期间又有改动（版本号变了）就不清“有改动”的标记，下次接着传。
        // 这次只传了一部分账号（比如断开某个账号前只传它）时也不清：别的账号还没拿到这些改动
        // 只看能传的账号（已登录、就绪、没暂停）：暂停或这会儿连不上的账号不该让标记永远清不掉
        val paused = pausedProviders()
        val coveredAll = CloudProvider.entries.filter { auth.isSignedIn(it) && isReady(it) && it !in paused }
            .all { names.getLong(KEY_INDEX_PUSHED + it.name, -1) == version }
        if (coveredAll && names.getLong(KEY_INDEX_VERSION, 0) == version) {
            names.edit().putBoolean(KEY_INDEX_DIRTY, false).putInt(KEY_INDEX_PENDING, 0).putLong(KEY_INDEX_AT, System.currentTimeMillis()).apply()
        } else if (uploadedAny) {
            // 有账号传成功了：照样记下时间、清掉计数，按间隔节流，不要每传一张照片就把每个账号的目录拉下来重传一遍
            names.edit().putInt(KEY_INDEX_PENDING, 0).putLong(KEY_INDEX_AT, System.currentTimeMillis()).apply()
        }
        // 共享相册列表上次没推上去的，顺便再推一次
        if (uploadedAny) runCatching { sharedAlbums.pushDirty() }
        return noEncrypted
    }

    /** 这台手机自己的加密选择（开没开、什么时候改的）；从没改过时为 null。 */
    private fun localMode(): Pair<Boolean, Long>? {
        val at = names.getLong(KEY_MODE_AT, 0)
        return if (at == 0L) null else vault.wantEncrypted to at
    }

    /**
     * 别的手机比这台更晚改了这个账号的加密开关，而且和这台不一样：账号 → 那边现在是开（true）还是关（false）。
     * 这时这台手机不自动转换，等用户选“跟着改”或者“保持这台的设置”，免得两台手机来回转换。
     */
    fun modeConflicts(): Map<CloudProvider, Boolean> {
        val localAt = names.getLong(KEY_MODE_AT, 0)
        val want = vault.wantEncrypted
        return CloudProvider.entries.filter { auth.isSignedIn(it) }.mapNotNull { p ->
            val text = names.getString(KEY_REMOTE_MODE + p.name, null) ?: return@mapNotNull null
            val enc = text.substringBefore('|') == "1"
            val at = text.substringAfter('|').toLongOrNull() ?: 0L
            if (at > localAt && enc != want) p to enc else null
        }.toMap()
    }

    /** 加密选择冲突时，用户选了“保持这台手机的设置”：记成最新的选择，下次传目录时写到网盘上，别的手机会看到。 */
    fun keepLocalEncryptionMode() {
        names.edit().putLong(KEY_MODE_AT, System.currentTimeMillis()).apply()
        markIndexDirty()
        refreshModeConflict()
        backupNow(manual = true)
    }

    private fun refreshModeConflict() {
        val c = modeConflicts()
        _encryption.update {
            it.copy(
                modeConflict = c.values.firstOrNull(),
                modeConflictAccounts = c.keys.map { p -> accountLabel(p) },
            )
        }
    }

    /**
     * 网盘上的备份目录损坏了：以这台手机为准重建。坏掉的那份先另存一份（index-broken-时间），再跳过它，之后照常覆盖。
     */
    suspend fun rebuildIndex(p: CloudProvider) = withContext(Dispatchers.IO) {
        exclusive {
            checkWritable(p)
            val bad = names.getString(KEY_INDEX_CORRUPT + p.name, null) ?: return@exclusive
            val drive = drive(p)
            val id = if (bad == "enc") retrying { drive.findFile(INDEX_ENCRYPTED) } else bad
            if (id != null) {
                val f = File(app.cacheDir, "index-broken").apply { parentFile?.mkdirs() }
                try {
                    retrying { f.outputStream().use { drive.download(id, it) } }
                    val name = "index-broken-${System.currentTimeMillis()}" + if (bad == "enc") ".zylc" else ".json"
                    val path = if (bad == "enc") listOf(CloudConfig.ENCRYPTED_FOLDER, name) else listOf(name)
                    retrying { drive.upload(UploadFile(path, "application/octet-stream", f.length(), System.currentTimeMillis()) { f.inputStream() }) }
                } finally {
                    f.delete()
                }
                names.edit().putString(KEY_INDEX_IGNORE + p.name, id).apply()
            }
            names.edit().remove(KEY_INDEX_CORRUPT + p.name).apply()
            refreshIndexIssues()
            markIndexDirty()
        }
        backupNow(manual = true)
    }

    /**
     * 网盘被“还原”过、目录比这台手机上次看到的旧：以这台手机为准，把那份旧目录合并进来后用更新的序号覆盖它。
     * 这台手机知道已经删掉的照片不会因为旧目录又冒出来（有删除记录）。
     */
    fun keepLocalIndex(p: CloudProvider) {
        names.edit().putBoolean(KEY_INDEX_OVERRIDE + p.name, true).remove(KEY_INDEX_ROLLBACK + p.name).apply()
        refreshIndexIssues()
        markIndexDirty()
        backupNow(manual = true)
    }

    /** 网盘被“还原”过、目录比这台手机上次看到的旧：以网盘为准重新同步（接受这份旧目录）。 */
    fun acceptRemoteIndex(p: CloudProvider) {
        names.edit().putLong(KEY_INDEX_SEQ + p.name, 0).remove(KEY_INDEX_ROLLBACK + p.name).remove(KEY_INDEX_CHECKED + p.name).apply()
        refreshIndexIssues()
        backupNow(manual = true)
    }

    /**
     * 加密目录用哪套密钥：这个账号还等着把网盘上以前那套的密钥包换成当前这套（[KEY_REKEY_ACCOUNT]，还没传上新密钥包）时，
     * 用以前那套，免得另一台只有那套密钥的手机读不了目录；密钥包换掉以后用当前这套。返回密钥和它的 vaultId（当前这套时为 null）。
     */
    /** 这个账号网盘上的密钥包还是以前那套、还没换成当前这套（见 [indexKeyFor]）。 */
    private fun rekeyPending(p: CloudProvider): Boolean {
        val marker = names.getString(KEY_REKEY_ACCOUNT + p.name, null) ?: return false
        return marker != vault.vaultId && !names.getBoolean(KEY_VAULT_UPLOADED_PREFIX + p.name, false)
    }

    private fun indexKeyFor(p: CloudProvider, key: ByteArray?): Pair<ByteArray?, String?> {
        val marker = names.getString(KEY_REKEY_ACCOUNT + p.name, null) ?: return key to null
        if (marker == vault.vaultId || names.getBoolean(KEY_VAULT_UPLOADED_PREFIX + p.name, false)) return key to null
        val old = runCatching { vault.oldKey(marker) }.getOrNull() ?: return key to null
        return old to marker
    }

    /**
     * 第一次连上某个网盘账号时，从网盘里的目录把以前备份过的照片找回来。
     * 是否恢复过只看“已检查”标记；读失败就不设标记，下一轮再试（这期间也不会往这个网盘传东西）。
     */
    private suspend fun restoreIndex(p: CloudProvider, key: ByteArray?) {
        // 库其实在可见文件夹、这台手机还没授权跟过去：当前位置（隐藏区）是空的，读了会把账号错记成“已找回”
        if (names.getBoolean(KEY_NEEDS_VISIBLE + p.name, false)) { diag("restoreIndex ${p.name}: skip (library elsewhere)"); return }
        val flag = KEY_INDEX_CHECKED + p.name
        if (names.getBoolean(flag, false)) { diag("restoreIndex ${p.name}: skip (already checked) space=${storage.space(p)}"); return }
        // 加密目录还没解锁：不要每轮都重新下载，等解锁后再读
        if (key == null && names.getBoolean(KEY_INDEX_LOCKED + p.name, false)) { diag("restoreIndex ${p.name}: skip (locked)"); return }
        val pulled = pullIndex(p, key)
        diag("restoreIndex ${p.name}: space=${storage.space(p)} key=${key != null} exists=${pulled.exists} seq=${pulled.seq}")
        if (pulled.exists) {
            pulled.settings?.let { st ->
                // 只在这台手机自己还没备份过时才提示（新手机、重装后），老手机重新连接网盘不打扰
                if (!names.getBoolean(KEY_SETTINGS_DECIDED, false) && records.entries().none { it.mediaId > 0 }) {
                    names.edit().putString(KEY_REMOTE_SETTINGS, st.toString()).apply()
                    _remoteSettings.value = true
                }
            }
            mergePulled(p, pulled)
        }
        names.edit().putBoolean(flag, true).remove(KEY_INDEX_LOCKED + p.name).putLong(KEY_INDEX_SEQ + p.name, pulled.seq).apply()
        // 马上和这台手机上的照片对一下，同一张照片不会在时间线上出现两次
        if (pulled.exists) {
            runCatching { linkRestored(MediaRepository(app).loadAll()) }
            markIndexDirty()
        }
        reloadRecords()
    }

    /**
     * 同步 AI 整理结果（见 [AiResultsSync]、[AiResultsFile]）。只在端到端加密开着、本机拿着密钥时做；
     * 用的密钥由加密主密钥派生（HKDF，和备份目录用的那把分开），网盘服务商只看得到密文。
     *
     * 文件可能有十几 MB，所以不是每轮都传：模型换了（或这台手机还没读过）马上读一次；
     * 本机有新结果时最多 6 小时传一次；没新结果也每天读一次，拿别的手机的新结果。
     * 下载、解密、合并、加密上传都经过临时文件边读边写，内存里只放本机导出的那一份。
     * 读不回来（断网）就不传，免得冲掉别的手机传的。
     *
     * 解不开时（密钥对不上）不覆盖，只跳过：可能是别的手机换了一套新密钥、这台还拿着旧的，
     * 覆盖了会让那台手机的结果丢掉。只有这台手机自己放弃过以前那套加密备份（重新开始）时，
     * 网盘上那份旧密钥的文件才确定没人要，直接换成新的。解得开但格式不对（损坏）的也直接重建。
     */
    private suspend fun syncAiResults(job: AiSyncJob) {
        val sync = aiResults ?: return
        if (!aiSyncLock.tryLock()) return // 上一轮还在传
        val dir = File(app.cacheDir, "ai-sync").apply { mkdirs() }
        try {
            val model = sync.model() ?: return
            // 内容指纹核对过的记录才用：换手机后按名字配上、还没核对的，指纹不一定是这张照片的
            val photos = job.records
                .filter { it.hash.isNotEmpty() && (it.mediaId <= 0 || it.modified != 0L) }
                .groupBy({ it.hash.lowercase() }, { AiPhotoRef(it.mediaId, it.modified, it.takenAt) })
                .mapValues { it.value.distinct() }
            if (photos.isEmpty()) return
            val aiKey = Primitives.hkdfSha256(job.key, ByteArray(0), "zyll/v1/ai-results".toByteArray(), 32)
            val version = sync.version()
            val now = System.currentTimeMillis()
            for (p in job.connected) {
                if (!isReady(p)) continue
                val dirty = names.getLong(KEY_AI_VERSION + p.name, -1) != version
                val since = now - names.getLong(KEY_AI_AT + p.name, 0)
                val due = names.getString(KEY_AI_MODEL + p.name, null) != model ||
                    since >= if (dirty) AI_UPLOAD_INTERVAL_MS else AI_PULL_INTERVAL_MS
                if (!due) continue
                val cipher = File(dir, "remote.zylc")
                val plain = File(dir, "remote.bin")
                val merged = File(dir, "merged.bin")
                try {
                    val drive = drive(p)
                    val id = retrying { drive.findFile(AI_RESULTS_PATH) }
                    var sections = emptyList<AiResultsFile.Section>()
                    var old: File? = null
                    if (id != null) {
                        retrying { cipher.outputStream().use { drive.download(id, it) } }
                        fun tryOpen(k: ByteArray): Boolean = try {
                            cipher.inputStream().buffered().use { input -> plain.outputStream().buffered().use { Zylc.decryptStream(input, k, it, BlobKind.OTHER) } }
                            true
                        } catch (e: app.zyll.crypto.ZyllCryptoException) {
                            false
                        }
                        // 当前密钥解不开时，试试暂存的以前那套（刚解开、正在换成当前密钥的账号）；合并后用当前密钥传回去
                        val opened = tryOpen(aiKey) || vault.oldKeyIds().any { id ->
                            val old = runCatching { vault.oldKey(id) }.getOrNull() ?: return@any false
                            tryOpen(Primitives.hkdfSha256(old, ByteArray(0), "zyll/v1/ai-results".toByteArray(), 32))
                        }
                        if (!opened && !names.contains(KEY_REMOTE_ABANDONED + p.name)) continue
                        if (opened) {
                            sections = try { AiResultsFile.sections(plain) } catch (_: IOException) { emptyList() }
                            if (sections.isNotEmpty()) old = plain
                        }
                        cipher.delete()
                    }
                    // 分批导入，每批最多 1000 张
                    val cur = sections.firstOrNull { it.model == model }
                    if (cur != null && old != null) {
                        var imported = 0
                        val batch = HashMap<String, ByteArray>()
                        AiResultsFile.forEach(old, cur) { hex, _, v ->
                            if (hex in photos) batch[hex] = v
                            if (batch.size >= 1000) {
                                imported += sync.import(model, batch, photos)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) imported += sync.import(model, batch, photos)
                        if (imported > 0) sync.importFinished(model)
                    }
                    names.edit().putString(KEY_AI_MODEL + p.name, model).apply()
                    if (AiResultsFile.merge(old, sections, model, sync.export(model, photos), now, merged)) {
                        val plainLen = merged.length()
                        val cipherLen = ZylcHeader(Zylc.DEFAULT_CHUNK_LOG2, BlobKind.OTHER, ByteArray(16), ByteArray(12), ByteArray(48), plainLen).totalLen
                        retrying {
                            drive.upload(
                                UploadFile(AI_RESULTS_PATH, "application/octet-stream", cipherLen, now, replace = true) {
                                    ZylcEncryptingInputStream(merged.inputStream().buffered(), plainLen, BlobKind.OTHER, aiKey)
                                },
                            )
                        }
                    }
                    names.edit().putLong(KEY_AI_AT + p.name, now).putLong(KEY_AI_VERSION + p.name, version).apply()
                } catch (_: IOException) {
                    // 下一轮再试
                } finally {
                    cipher.delete(); plain.delete(); merged.delete()
                }
            }
        } finally {
            aiSyncLock.unlock()
        }
    }

    /** 不是这台手机的 MediaStore id：用 63 位负数，几十万张照片也不会撞。 */
    private fun syntheticId(p: CloudProvider, remoteId: String): Long {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest((p.name + "/" + remoteId).toByteArray())
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xff)
        return -1L - (v and Long.MAX_VALUE)
    }

    /**
     * 从网盘目录恢复出来的记录（负数 id）和本机照片配对：文件名、大小一样，拍摄时间也对得上的，
     * 就挂到本机照片上；有几张候选、或者时间对不上时，用内容指纹确认。返回有没有改动。
     * 有指纹却没核对过的，先记成“待核对”（修改时间记 0）：界面上不算已备份，也不会被当成可以放心删，
     * 之后备份或补充时核对指纹，对上了才算数。必须在 [lock] 里调用。
     */
    private fun linkRestored(local: List<MediaItem>): Boolean {
        val all = records.entries()
        val orphans = all.filter { it.mediaId < 0 }
        if (orphans.isEmpty() || local.isEmpty()) return false
        val linkedRemotes = all.filter { it.mediaId > 0 }.mapTo(HashSet()) { it.provider to it.remoteId }
        val byKey = local.groupBy { it.displayName to it.sizeBytes }
        var changed = false
        for ((remote, group) in orphans.groupBy { it.provider to it.remoteId }) {
            if (remote in linkedRemotes) continue
            val r = group.first()
            val candidates = byKey[r.name to r.size] ?: continue
            val close = candidates.filter { r.takenAt <= 0 || kotlin.math.abs(it.takenAt - r.takenAt) < 2_000 }
            var verified = false
            val match = when {
                close.size == 1 && (r.hash.isEmpty() || candidates.size == 1) -> close[0]
                r.hash.isNotEmpty() && remote !in linkMisses ->
                    candidates.firstOrNull { c -> runCatching { sha256(c) }.getOrNull() == r.hash }
                        ?.also { verified = true }
                        ?: run { linkMisses += remote; null }
                else -> null
            } ?: continue
            // 这张本机照片在这个网盘上已经有自己的记录了：不动它
            if (records.get(match.id, r.provider) != null) continue
            records.removeRemote(r.provider, r.remoteId)
            val sameFile = r.modified > 0 && r.modified == match.modifiedAt
            records.put(r.copy(mediaId = match.id, modified = if (r.hash.isEmpty() || verified || sameFile) match.modifiedAt else 0L))
            changed = true
        }
        return changed
    }

    /** 算过指纹也没配上的，这次运行不再重复算。 */
    private val linkMisses = java.util.Collections.synchronizedSet(HashSet<Pair<CloudProvider, String>>())

    /** App 拿到本机照片列表后调用：把恢复出来的记录配上本机照片。 */
    fun linkRestoredLater(local: List<MediaItem>) {
        scope.launch {
            // 正在备份就跳过：备份那一轮开始时自己会配一次，不和上传同时改记录
            if (!lock.tryLock()) return@launch
            try {
                if (runCatching { linkRestored(local) }.getOrDefault(false)) reloadRecords()
            } finally {
                lock.unlock()
            }
        }
    }

    /** 恢复网盘里保存的设置（换手机后）。 */
    fun applyRemoteSettings() {
        val text = names.getString(KEY_REMOTE_SETTINGS, null) ?: return
        runCatching {
            val o = org.json.JSONObject(text)
            o.optJSONObject("backup")?.let { b ->
                val restored = settingsStore.decode(b)
                // 这台手机的“见过的相簿”和是否开启以这台手机为准
                update { cur -> restored.copy(enabled = cur.enabled, knownAlbums = cur.knownAlbums) }
            }
            o.optJSONObject("gallery")?.let { g -> extras?.import(g) }
        }
        dismissRemoteSettings()
    }

    fun dismissRemoteSettings() {
        names.edit().remove(KEY_REMOTE_SETTINGS).putBoolean(KEY_SETTINGS_DECIDED, true).apply()
        _remoteSettings.value = false
    }

    /**
     * 关闭加密、本机记录全部转换完以后：逐个账号确认里面已经没有加密备份，再删掉那个账号里的密钥包和加密目录。
     * 所有上传过这套密钥的账号都处理完，才删本机的密钥；有账号还没连上就先留着密钥，等它连上再处理。
     */
    private suspend fun finishDisable(connected: Set<CloudProvider>, key: ByteArray?) {
        if (records.entries().any { it.encrypted }) return
        var tags = vaultAccounts()
        val targets = connected.filter { accountTag(it) in tags }
        // 先把不加密的目录传上去：会先读回并合并加密的那份，合并成功后才删掉它；里面要是还有加密照片，下一轮接着转换
        val clean = if (targets.isNotEmpty()) {
            markIndexDirty()
            runCatching { uploadIndex(connected, false, key, force = true) }.getOrDefault(emptySet())
        } else emptySet()
        val all = records.entries()
        for (p in targets) {
            val tag = accountTag(p)
            // 加密目录没能读回合并（或没删掉）、或者还有加密记录：先不删密钥包，下一轮再来
            if (p !in clean || all.any { it.provider == p && it.encrypted }) continue
            val ok = runCatching {
                val drive = drive(p)
                // 加密的 AI 整理结果：关了加密就不再同步，删掉
                retrying { drive.findFile(AI_RESULTS_PATH) }?.let { id -> retrying { drive.delete(id) } }
                retrying { drive.findFile(KEYBUNDLE_PATH) }?.let { id ->
                    // 只删自己这套密钥的密钥包
                    val out = java.io.ByteArrayOutputStream()
                    retrying { out.reset(); drive.download(id, out) }
                    if (runCatching { KeyBundle.parse(String(out.toByteArray())).vaultId }.getOrNull() == vault.vaultId) retrying { drive.delete(id) }
                }
            }.isSuccess
            if (ok) {
                tags = tags - tag
                names.edit().remove(KEY_VAULT_UPLOADED_PREFIX + p.name).putBoolean(KEY_REMOTE_CHECKED + p.name, true)
                    .remove(KEY_AI_MODEL + p.name).remove(KEY_AI_AT + p.name).remove(KEY_AI_VERSION + p.name).apply()
            }
        }
        names.edit().putStringSet(KEY_VAULT_ACCOUNTS, tags).apply()
        if (tags.isEmpty() && records.entries().none { it.encrypted }) {
            vault.forget()
            // 看过的加密照片留在缓存里的解密副本：密钥都删了，副本也不留
            viewDir.listFiles()?.forEach { it.delete() }
            MotionPhotos.clearCache(app, MotionPhotos.cloudPrefix(null))
            _encryption.update { it.copy(enabled = false, waitingAccounts = emptyList(), waitingTags = emptyList()) }
        } else {
            val list = tags.toList()
            _encryption.update { it.copy(waitingAccounts = list.map { t -> tagLabel(t) }, waitingTags = list) }
        }
    }

    /**
     * 看看网盘里有没有以前的加密备份（只要有密钥包就算）。每个账号查一次；没查成功之前，这个网盘不传任何东西。
     * - 和本机是同一套密钥：没事；
     * - 本机没有密钥、或者是另一套：记下来，要用户解锁（或放弃）之后才继续。
     */
    /**
     * 本机没有密钥、网盘上却出现了加密目录时调用：隔一段时间（[BUNDLE_RECHECK_MS]）重新查一次密钥包。
     * 返回 true 表示查到了要解锁的密钥包（已经记下，界面会提示）。
     */
    private suspend fun recheckRemoteVault(p: CloudProvider): Boolean {
        val now = System.currentTimeMillis()
        if (now - names.getLong(KEY_VAULT_RECHECK_AT + p.name, 0) >= BUNDLE_RECHECK_MS) {
            names.edit().putLong(KEY_VAULT_RECHECK_AT + p.name, now).remove(KEY_REMOTE_CHECKED + p.name).apply()
            try {
                checkRemoteVault(p)
            } catch (e: IOException) {
                // 没查成：下次再查，这期间按“没检查过”处理（不上传）
                return true
            }
        }
        return names.contains(KEY_REMOTE_BUNDLE + p.name)
    }

    private suspend fun checkRemoteVault(p: CloudProvider) {
        // 同上：库在别处，当前位置找不到密钥包也不能记成“没有加密备份”
        if (names.getBoolean(KEY_NEEDS_VISIBLE + p.name, false)) { diag("checkRemoteVault ${p.name}: skip (library elsewhere)"); return }
        if (names.getBoolean(KEY_REMOTE_CHECKED + p.name, false)) { diag("checkRemoteVault ${p.name}: skip (checked) space=${storage.space(p)}"); return }
        diag("checkRemoteVault ${p.name}: space=${storage.space(p)}")
        val drive = drive(p)
        val id = retrying { drive.findFile(KEYBUNDLE_PATH) }
        if (id != null) {
            val out = java.io.ByteArrayOutputStream()
            retrying { out.reset(); drive.download(id, out) }
            val text = String(out.toByteArray())
            val remoteId = runCatching { KeyBundle.parse(text).vaultId }.getOrNull()
            when {
                remoteId != null && remoteId == vault.vaultId -> addVaultAccount(p)
                // 以前另一套、这台手机已经解开暂存着：不用再解锁，之后换成当前密钥
                remoteId != null && remoteId in vault.oldKeyIds() -> {}
                // 放弃过的那套（解析不了的密钥包放弃时记成空字符串）
                (remoteId ?: "") == names.getString(KEY_REMOTE_ABANDONED + p.name, null) -> {}
                else -> names.edit().putString(KEY_REMOTE_BUNDLE + p.name, text).apply()
            }
        }
        names.edit().putBoolean(KEY_REMOTE_CHECKED + p.name, true).apply()
        refreshUnlockState()
    }

    private fun open(item: MediaItem): InputStream {
        // 有“照片位置”权限时读原始文件，备份里保留拍摄地点
        val keepLocation = Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        val uri = if (keepLocation) MediaStore.setRequireOriginal(item.uri) else item.uri
        return app.contentResolver.openInputStream(uri) ?: throw IOException(tr(R.string.read_failed_name, item.displayName))
    }

    /**
     * [resume]：断点续传的标识（见 [resumeKeyFor]）。必须能保证同一个标识下内容不变：
     * 加密时同一个标识用同一组随机数，重新打开得到的密文逐字节一样，才能从断点接着传。
     */
    private fun uploadFile(
        name: String, album: String, mime: String, size: Long, takenAt: Long,
        encrypt: Boolean, key: ByteArray?, resume: String? = null, open: () -> InputStream,
    ): UploadFile {
        if (!encrypt) {
            return UploadFile(
                path = listOf(album.ifBlank { "未命名" }.replace('/', '_'), name.ifBlank { "photo" }.replace('/', '_')),
                mimeType = mime.ifBlank { "application/octet-stream" },
                sizeBytes = size,
                modifiedAt = takenAt,
                resumeKey = resume,
                open = open,
            )
        }
        val k = key ?: throw IOException(tr(R.string.e2ee_encryption_key_missing))
        // 加密后文件名是随机的，网盘上看不出是什么照片
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        val cipherLen = ZylcHeader(Zylc.DEFAULT_CHUNK_LOG2, BlobKind.ORIGINAL, ByteArray(16), ByteArray(12), ByteArray(48), size).totalLen
        resume?.let { r -> resumeCrypto.getOrPut(r) { ResumeCrypto(Primitives.KEY_LEN) } }
        return UploadFile(
            path = listOf(CloudConfig.ENCRYPTED_FOLDER, "$id.zylc"),
            mimeType = "application/octet-stream",
            sizeBytes = cipherLen,
            // 不能写拍摄时间：网盘上每个随机命名的文件都会带着精确的拍摄时间。程序也不从网盘读回这个时间
            modifiedAt = System.currentTimeMillis(),
            resumeKey = resume,
            // 新会话从头传：换一组新的随机数（同一个会话里续传才必须沿用同一组）
            onNewSession = resume?.let { r -> { resumeCrypto[r] = ResumeCrypto(Primitives.KEY_LEN) } },
            // 会话和这组随机数绑在一起：随机数换了或丢了，旧会话就不再续传
            resumeTag = resume?.let { r -> { resumeCrypto[r]?.tag ?: "none" } },
            open = {
                val fixed = resume?.let { resumeCrypto[it] }
                if (fixed == null) ZylcEncryptingInputStream(open(), size, BlobKind.ORIGINAL, k)
                else ZylcEncryptingInputStream.with(open(), size, BlobKind.ORIGINAL, k, fixed.blobId, fixed.dek, fixed.nonce)
            },
        )
    }

    /** 断点续传的加密文件：同一个标识固定用这一组随机数（只在内存里，传完或换了内容就丢掉）。 */
    private class ResumeCrypto(keyLen: Int) {
        val blobId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val tag: String = blobId.toHex()
        val dek = ByteArray(keyLen).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    }
    private val resumeCrypto = java.util.concurrent.ConcurrentHashMap<String, ResumeCrypto>()

    /**
     * 大文件的断点续传标识。带上内容指纹：内容变了标识就变，不会把两份不同的内容拼成一个文件，
     * 加密时也不会用同一组随机数加密两份不同的内容。带上密钥指纹：换了密钥也重新开始。小文件不续传。
     */
    private fun resumeKeyFor(p: CloudProvider, hash: String, size: Long, encrypt: Boolean, key: ByteArray?): String? {
        if (size < RESUME_MIN_BYTES || hash.isEmpty()) return null
        val kfp = if (encrypt && key != null) Primitives.sha256(key).copyOfRange(0, 8).toHex() else "-"
        return "${p.name}/$hash/$size/$encrypt/$kfp"
    }

    private fun entryFor(item: MediaItem, p: CloudProvider, remoteId: String, encrypted: Boolean, hash: String) = RecordEntry(
        mediaId = item.id, provider = p, modified = item.modifiedAt, size = item.sizeBytes, remoteId = remoteId,
        encrypted = encrypted, name = item.displayName, album = item.bucketName, mime = item.mimeType, takenAt = item.takenAt,
        hash = hash,
    )

    /**
     * 写入一条新记录。同一张照片以前指向别的网盘文件时：
     * - 那条是换手机后按名字配上、还没核对指纹的：放回“只在网盘里”，不能把别人的备份当成旧版本删掉；
     * - 是这台手机以前传的旧版本（照片编辑过）、而且没有别的记录还指向它：加进待删除队列。
     */
    private suspend fun replaceRecord(e: RecordEntry): String? {
        val old = records.get(e.mediaId, e.provider)
        records.put(e)
        if (old == null || old.remoteId == e.remoteId) return null
        // 确实像是同一张照片编辑过（名字一样，或者拍摄时间一样）才当成旧版本；
        // 否则多半是系统媒体库重建后 id 撞上了别的照片：把那份备份放回“只在网盘里”，绝不删
        val samePhoto = old.name == e.name || (old.takenAt > 0 && kotlin.math.abs(old.takenAt - e.takenAt) < 60_000)
        if (old.modified == 0L || !samePhoto) {
            if (records.byRemote(old.provider, old.remoteId).isEmpty()) records.put(old.copy(mediaId = syntheticId(old.provider, old.remoteId), modified = 0))
            return null
        }
        if (records.byRemote(old.provider, old.remoteId).isEmpty() && !records.isShared(old.provider, old.remoteId)) {
            // 别的手机也在用的（共享）不删：那可能是它唯一的备份
            discardRemote(old.provider, old.remoteId, old.thumb)
            return old.remoteId
        }
        return null
    }

    /**
     * 转换、编辑一张网盘照片的途中，旧文件是不是被删了：本机已经知道它删了（墓碑、记录没了），
     * 或者网盘上已经找不到它（网页或别的手机删的，墓碑还没合并过来）。查不了网盘时按没删处理，之后靠 [markReplaced] 兜底。
     */
    private suspend fun deletedMeanwhile(p: CloudProvider, oldId: String): Boolean {
        if (records.isTombstoned(p, oldId) || records.byRemote(p, oldId).isEmpty()) return true
        return try { retrying { drive(p).stat(oldId) } == null } catch (_: IOException) { false }
    }

    /**
     * 记下旧文件换成了新文件，直到这次替换写进这个网盘的目录（[discardRemote] 刚把目录版本加了一）。
     * 在那之前网盘目录里出现旧 id 的墓碑，只能是网页或别的手机删的（这台手机的墓碑还没传上去），合并时把新文件一起删掉。
     */
    private fun markReplaced(p: CloudProvider, oldId: String, newId: String) =
        records.markReplaced(p, oldId, newId, names.getLong(KEY_INDEX_VERSION, 0))

    /** 不再需要的网盘文件：马上删；删不掉（断网等）就记进待删除队列，每轮重试到删掉为止。备份目录里记下它已删除。 */
    private suspend fun discardRemote(p: CloudProvider, remoteId: String, thumb: String) {
        addTombstone(p, remoteId)
        markIndexDirty()
        deleteNowOrLater(p, remoteId)
        if (thumb.isNotEmpty()) deleteNowOrLater(p, thumb)
    }

    private suspend fun deleteNowOrLater(p: CloudProvider, id: String) {
        if (id.isEmpty()) return
        records.enqueueDelete(p, id)
        // 删掉了东西就腾出了空间：“空间已满”的标记作废，下一轮重新查
        runCatching { retrying { drive(p).delete(id) } }.onSuccess { records.dropDelete(p, id); quotaFull.remove(p) }
        _encryption.update { it.copy(pendingCleanup = records.pendingDeleteCount()) }
    }

    private fun addTombstone(p: CloudProvider, remoteId: String) = records.addTombstones(p, listOf(remoteId), TOMBSTONE_MAX)

    /** 这台手机知道已经删掉的网盘文件（某个网盘）。 */
    private fun tombstones(p: CloudProvider): Set<String> = records.tombstones(p)

    /** 把待删除队列里的网盘文件删掉（每轮最多一批）。 */
    private suspend fun processDeletes(connected: Set<CloudProvider>) {
        for ((p, id) in records.pendingDeletes(DELETE_BATCH)) {
            if (p !in connected) continue
            runCatching { retrying { drive(p).delete(id) } }.onSuccess { records.dropDelete(p, id); quotaFull.remove(p) }
        }
        _encryption.update { it.copy(pendingCleanup = records.pendingDeleteCount()) }
    }

    // ---------- 核对网盘：释放空间前、媒体库重建后、定期对账 ----------

    /**
     * 系统媒体库重建过（用户清了“媒体存储”的数据、部分系统升级）：MediaStore 的 id 会从头编号，
     * 新照片可能拿到以前别的照片的 id。版本号变了就把所有本机 id 的记录放回“只在网盘里”，交给 [linkRestored] 按名字、大小、指纹重新配对。
     * 必须在 [lock] 里调用。
     */
    private fun checkMediaVersion() {
        if (Build.VERSION.SDK_INT < 29) return
        val v = runCatching { MediaStore.getVersion(app) }.getOrNull() ?: return
        val old = names.getString(KEY_MEDIA_VERSION, null)
        if (old == v) return
        if (old != null) {
            records.transaction {
                records.entries().filter { it.mediaId > 0 }.forEach { r ->
                    records.remove(r.mediaId, r.provider)
                    // 同一个网盘文件只留一条“只在网盘里”的记录
                    if (records.byRemote(r.provider, r.remoteId).isEmpty()) {
                        // 保留原来的修改时间：名字、大小、修改时间都和原来一样的照片重新配对时可以直接配回去，不用每张都算指纹
                        records.put(r.copy(mediaId = syntheticId(r.provider, r.remoteId)))
                    }
                }
            }
            // 手动选的、传得慢的照片也是按 id 记的，一起作废
            names.edit().remove(KEY_FORCED).remove(KEY_SLOW).apply()
            linkMisses.clear()
            _backedUp.value = emptySet()
        }
        names.edit().putString(KEY_MEDIA_VERSION, v).apply()
    }

    /** 上传时网盘说空间满了的账号（elapsedRealtime）。 */
    private val quotaFull = java.util.concurrent.ConcurrentHashMap<CloudProvider, Long>()

    private fun markQuotaFull(p: CloudProvider) {
        quotaFull[p] = android.os.SystemClock.elapsedRealtime()
        accountCheckedAt.remove(p)
    }

    private fun quotaFullRecently(p: CloudProvider): Boolean =
        quotaFull[p]?.let { android.os.SystemClock.elapsedRealtime() - it < ACCOUNT_TTL_MS } == true

    /** 传失败过的大文件：下一轮排到最后。 */
    private fun markSlow(item: MediaItem) {
        val set = names.getStringSet(KEY_SLOW, emptySet())!!
        if (item.id.toString() !in set) names.edit().putStringSet(KEY_SLOW, (set + item.id.toString()).toList().takeLast(500).toSet()).apply()
    }

    private fun clearSlow(item: MediaItem) {
        val set = names.getStringSet(KEY_SLOW, emptySet())!!
        if (item.id.toString() in set) names.edit().putStringSet(KEY_SLOW, set - item.id.toString()).apply()
    }

    private fun brokenKey(p: CloudProvider, remoteId: String) = p.name + "/" + remoteId

    /** 坏掉的备份（解不开、被截断、内容不对）：不再转换，也不算作“已备份”。 */
    private fun brokenSet(): Set<String> = names.getStringSet(KEY_BROKEN, emptySet())!!

    private fun isBroken(r: RecordEntry) = brokenKey(r.provider, r.remoteId) in brokenSet()

    /**
     * 转换时发现这份备份坏了：本机还有原图的，记录删掉，下一轮按现在的形式重新上传；
     * 只在网盘里的，记成“坏掉”，不再转换（网盘上的文件不删：可能只是这台手机缺了那套密钥）。
     */
    private fun markBroken(group: List<RecordEntry>, local: Map<Long, MediaItem>) {
        val e = group.first()
        records.transaction {
            group.filter { g -> local[g.mediaId]?.modifiedAt == g.modified }.forEach { records.remove(it.mediaId, it.provider) }
        }
        names.edit().putStringSet(KEY_BROKEN, brokenSet() + brokenKey(e.provider, e.remoteId)).apply()
        markIndexDirty()
    }

    /** 网盘上的文件已经不在了：指向它的记录都作废（本机还有原图的会重新备份）。 */
    private suspend fun dropMissing(group: List<RecordEntry>) {
        val e = group.first()
        records.removeRemote(e.provider, e.remoteId)
        addTombstone(e.provider, e.remoteId)
        rememberPickerDeleted(group.take(1))
        group.map { it.thumb }.filter { it.isNotEmpty() }.distinct().forEach { deleteNowOrLater(e.provider, it) }
        // 同样内容的照片在别的账号里还有：让选择器重新取那一份
        touchSameHash(group.map { it.hash })
        markIndexDirty()
    }

    /** 同样内容的其他记录重新写一遍（更新上传时间），系统照片选择器的增量同步才拿得到新的代表项。 */
    private fun touchSameHash(hashes: Collection<String>) {
        val hs = hashes.filter { it.isNotEmpty() }.toSet()
        if (hs.isEmpty()) return
        records.transaction { records.entries().filter { it.hash in hs }.forEach { records.put(it) } }
    }

    /** 很久没读网盘目录了：读一次并合并（这台手机没有新照片时，以前不会去读，拿不到别的手机删掉的照片）。 */
    /** 目录有问题的账号：最多每 [ISSUE_RETRY_MS] 试读一次；读成功时 [pullIndex] 会自己清掉问题标记。 */
    private suspend fun retryIndexIssues(online: Collection<CloudProvider>, key: ByteArray?) {
        val now = System.currentTimeMillis()
        for (p in online) {
            if (!_indexIssues.value.containsKey(p)) continue
            if (now - names.getLong(KEY_ISSUE_RETRY_AT + p.name, 0) < ISSUE_RETRY_MS) continue
            names.edit().putLong(KEY_ISSUE_RETRY_AT + p.name, now).apply()
            runCatching { syncFromRemote(p, key) }
        }
    }

    private suspend fun pullIfStale(connected: Set<CloudProvider>, key: ByteArray?) {
        val now = System.currentTimeMillis()
        for (p in connected) {
            if (now - names.getLong(KEY_PULLED_AT + p.name, 0) < PULL_INTERVAL_MS) continue
            runCatching { syncFromRemote(p, key) }
        }
    }

    /** 读一次网盘目录并合并，不管本机有没有改动。 */
    private suspend fun syncFromRemote(p: CloudProvider, key: ByteArray?) {
        val pulled = pullIndex(p, key)
        val changed = mergePulled(p, pulled)
        names.edit().putLong(KEY_PULLED_AT + p.name, System.currentTimeMillis())
            .putLong(KEY_INDEX_SEQ + p.name, maxOf(pulled.seq, names.getLong(KEY_INDEX_SEQ + p.name, 0))).apply()
        if (changed) reloadRecords()
    }

    /** 释放空间前的核对结果：[safe] 确认网盘里还在、打得开的；[missing] 网盘里已经没了（已改回未备份）；[unverified] 这会儿查不到（没联网等）。 */
    data class FreeCheck(val safe: List<MediaItem>, val missing: Int, val unverified: Int)

    /**
     * 从手机删照片之前逐个确认网盘里那份还在：先读一次网盘目录（拿到别的手机删掉的），
     * 再查每个网盘文件是否还在、大小对不对；加密的还要确认本机有解开它的密钥。
     * 不在的记录删掉、照片改回“未备份”。[onProgress] 报已核对 / 总数。
     * [onChecked] 每张出结果时调用：1 = 网盘里在，0 = 确认没了，-1 = 这会儿没法确认（用户中途跳过核对时，
     * 界面据此把已经确认没了的照片排除掉，不删）。
     */
    suspend fun verifyForFree(
        items: List<MediaItem>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onChecked: (MediaItem, Int) -> Unit = { _, _ -> },
    ): FreeCheck = withContext(Dispatchers.IO) {
        exclusive {
            // 媒体库刚重建、还没跑过一轮备份时，记录里还是旧 id：先放回“只在网盘里”，免得撞号的照片被当成已备份
            runCatching { checkMediaVersion() }
            val key = runCatching { vault.wrapKey() }.getOrNull()
            val signedIn = CloudProvider.entries.filter { auth.isSignedIn(it) && isReady(it) }
            for (p in signedIn) runCatching { syncFromRemote(p, key) }
            // 本机还有没传上去的目录改动：先传。没传成的账号，它里面的备份换手机时找不到，不算数
            runCatching { uploadIndex(signedIn.toSet(), vault.wantEncrypted, key, force = true) }
            val usable = signedIn.filterTo(HashSet()) { indexSynced(it) }
            val vaultTags = vaultAccounts()
            val broken = brokenSet()
            val byMedia = records.entries().groupBy { it.mediaId }
            val want = items.associateWith { i ->
                byMedia[i.id].orEmpty().filter { it.modified == i.modifiedAt && it.size == i.sizeBytes && brokenKey(it.provider, it.remoteId) !in broken }
            }
            // 照片多时整个列一遍网盘比逐个查快得多
            val needed = want.values.flatten().map { it.provider }.toSet()
            val listings = HashMap<CloudProvider, Map<String, RemoteFile>?>()
            if (items.size > LIST_INSTEAD_OF_STAT) for (p in needed) listings[p] = runCatching { drive(p).listAll().associateBy { it.id } }.getOrNull()
            val stats = java.util.concurrent.ConcurrentHashMap<Pair<CloudProvider, String>, Int>() // 1 在，0 不在，-1 查不到
            suspend fun check(r: RecordEntry): Int = stats.getOrPut(r.provider to r.remoteId) {
                val expected = if (r.encrypted) ZylcHeader(Zylc.DEFAULT_CHUNK_LOG2, BlobKind.ORIGINAL, ByteArray(16), ByteArray(12), ByteArray(48), r.size).totalLen else r.size
                val listing = listings[r.provider]
                // 列表里没看到的再单独查一次：刚传上去的文件不一定马上出现在列表里，只有确认是 404 才算没了
                val f = listings[r.provider]?.get(r.remoteId)
                    ?: try { retrying { drive(r.provider).stat(r.remoteId) } } catch (e: IOException) { return@getOrPut -1 }
                when {
                    f == null -> 0
                    // 大小对不上：更像是坏了或者记录过时，不是“删了”。不能放心删本机原图，但也不写墓碑
                    f.size >= 0 && f.size != expected -> -1
                    else -> 1
                }
            }
            val safe = ArrayList<MediaItem>()
            var missing = 0
            var unverified = 0
            var done = 0
            val gone = HashMap<Pair<CloudProvider, String>, List<RecordEntry>>()
            val sem = kotlinx.coroutines.sync.Semaphore(6)
            val results = kotlinx.coroutines.coroutineScope {
                want.entries.map { (item, recs) ->
                    async {
                        sem.withPermit {
                            var ok = false
                            var unknown = false
                            for (r in recs) {
                                // 这个账号目录有问题、或者目录还没传上去：网盘那份换手机时找不到，这次不算
                                if (r.provider !in usable) { unknown = true; continue }
                                // 加密的：本机要有解开它的那套密钥；这个账号里也要已经有这套密钥的密钥包（不在换密钥的过渡期），
                                // 否则换手机后用密码也解不开
                                if (r.encrypted && (runCatching { keyFor(r.keyId) }.getOrNull() == null ||
                                        accountTag(r.provider) !in vaultTags || rekeyPending(r.provider))
                                ) { unknown = true; continue }
                                when (check(r)) {
                                    1 -> { ok = true; break }
                                    0 -> synchronized(gone) { gone[r.provider to r.remoteId] = records.byRemote(r.provider, r.remoteId) }
                                    else -> unknown = true
                                }
                            }
                            val result = if (ok) 1 else if (unknown) -1 else 0
                            synchronized(gone) {
                                done++
                                onChecked(item, result)
                                onProgress(done, items.size)
                            }
                            item to result
                        }
                    }
                }.map { it.await() }
            }
            for ((item, r) in results) when (r) {
                1 -> safe += item
                -1 -> unverified++
                else -> missing++
            }
            if (gone.isNotEmpty()) {
                gone.values.filter { it.isNotEmpty() }.forEach { dropMissing(it) }
                reloadRecords()
            }
            FreeCheck(safe, missing, unverified)
        }
    }

    /**
     * 和网盘对一次账（每个账号每周最多一次，只在充电、不计流量时）：列出 Zyll 在网盘里的全部文件，
     * - 记录指向的文件已经不在了：记录作废（本机还有原图的会重新备份）；
     * - 这台手机自己传上去、却没写进任何记录的文件（上传成功但没来得及记下）：加进待删除队列（见 [cleanOwnOrphans]）。
     *   别的手机传的文件不碰。
     */
    private suspend fun reconcile(connected: Set<CloudProvider>, key: ByteArray?, stop: () -> Boolean) {
        val now = System.currentTimeMillis()
        for (p in connected) {
            if (stop()) return
            if (now - names.getLong(KEY_RECONCILED_AT + p.name, 0) < RECONCILE_INTERVAL_MS) continue
            // 切换了存放位置、旧位置的文件还没搬完：列表只列得到新位置，会把旧位置的缩略图当作没了。搬完再对账
            if (p.kind == DriveKind.GoogleDrive && storage.cleanup(p) != null) continue
            val drive = drive(p)
            val files = try { drive.listAll() } catch (e: IOException) { continue }
            // 别的手机的记录也算引用：先读回网盘目录
            val pulled = try { pullIndex(p, key) } catch (e: IOException) { continue }
            mergePulled(p, pulled)
            val present = files.associateBy { it.id }
            val mine = records.entries().filter { it.provider == p }
            for ((rid, group) in mine.groupBy { it.remoteId }) {
                if (stop()) return
                if (rid !in present) {
                    // 列表里没看到不一定是没了（刚传上去的文件可能还没出现在列表里）：单独查一次，确认是 404 才作废
                    val gone = try { retrying { drive.stat(rid) } == null } catch (e: IOException) { false }
                    if (gone) dropMissing(group)
                } else {
                    // 网盘缩略图没了：清掉，之后补传
                    group.filter { it.thumb.isNotEmpty() && it.thumb !in present }.forEach { records.put(it.copy(thumb = "")) }
                }
            }
            val referenced = HashSet<String>()
            records.entries().filter { it.provider == p }.forEach { r -> referenced += r.remoteId; if (r.thumb.isNotEmpty()) referenced += r.thumb }
            pulled.items.forEach { o -> referenced += o.r; if (o.th.isNotEmpty()) referenced += o.th }
            referenced += pulled.unmergedRefs
            listOfNotNull(pulled.encId, pulled.plainId, names.getString(KEY_INDEX_ID + p.name, null)).forEach { referenced += it }
            cleanOwnOrphans(p, files, referenced, pulled.complete, now, stop)
            if (stop()) return
            names.edit().putLong(KEY_RECONCILED_AT + p.name, now).apply()
            markIndexDirty()
        }
        reloadRecords()
    }

    /**
     * 孤儿只清这台手机自己传上去、却没写进任何记录的文件（上传日志，见 [journaled]）。
     * 别的手机传的文件这台手机从来不删：它可能读不了那台手机的目录（没有密钥、开着加密不合并明文目录、目录坏了被跳过）。
     * 旧形式的文件（比如开启加密后的明文原图）由目录里的待删除列表接力删，不靠这里。
     */
    private fun cleanOwnOrphans(p: CloudProvider, files: List<RemoteFile>, referenced: Set<String>, complete: Boolean, now: Long, stop: () -> Boolean) {
        records.pruneUploads()
        val present = files.associateBy { it.id }
        for (u in records.uploads(p)) {
            if (stop()) return
            val age = now - u.startedAt
            if (u.remoteId.isNotEmpty() && u.remoteId in referenced) {
                records.dropUpload(p, u.token)
                continue
            }
            // 太久的日志没必要留着
            if (age > UPLOAD_LOG_MAX_AGE_MS) {
                records.dropUpload(p, u.token)
                continue
            }
            // 刚传完的可能正要写记录；也给别的手机留时间把同名的新文件写进目录
            if (age < UPLOAD_ORPHAN_AGE_MS) continue
            // 这个账号有目录没读到：同样内容的照片可能被别的手机共用着这份文件，先不动，下次再看
            if (!complete) continue
            // 这次上传出来的文件：日志里的 id，加上按名字和时间找到的（没等到上传结果，或者 OneDrive 最后一片的响应丢了、
            // 新会话又传了一份改了名的）。只要有记录或目录在用就不删
            val mine = HashSet<String>()
            fun inWindow(f: RemoteFile) = f.createdAt > 0 && f.createdAt in (u.startedAt - 60_000)..(u.startedAt + UPLOAD_MATCH_WINDOW_MS) &&
                now - f.createdAt >= UPLOAD_ORPHAN_AGE_MS
            val known = u.remoteId.takeIf { it.isNotEmpty() }?.let { present[it] }
            if (known != null) mine += known.id
            when {
                // OneDrive 最后一片的响应丢了、新会话又传了一份时，网盘上会多一个改了名的同名文件，和日志里那份在同一个文件夹
                known != null && p.kind == DriveKind.OneDrive && known.parent.isNotEmpty() ->
                    files.filter { f -> f.id != known.id && f.parent == known.parent && inWindow(f) && sameUploadName(f.name, u.name) }.mapTo(mine) { it.id }
                // 日志里有 id 的只认那个 id（明文照片名字常重复，按名字找可能找到别的手机传的）
                known != null || u.remoteId.isNotEmpty() -> {}
                // 没等到上传结果：只有加密文件的随机名字可以放心按名字找
                RANDOM_NAME.matches(u.name) -> files.filter { f -> inWindow(f) && f.name == u.name }.mapTo(mine) { it.id }
            }
            for (id in mine) {
                val f = present[id] ?: continue
                if (id in referenced || f.name in PROTECTED_NAMES || f.name.startsWith("index") || records.inTrash(p, id)) continue
                records.enqueueDelete(p, id)
            }
            records.dropUpload(p, u.token)
        }
    }

    /** 网盘上的文件名是不是这次上传的：同名，或者 OneDrive 遇到同名时自动改的“名字 1.jpg”。 */
    private fun sameUploadName(actual: String, wanted: String): Boolean {
        if (actual == wanted) return true
        val dot = wanted.lastIndexOf('.')
        val base = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        if (!actual.startsWith("$base ") || !actual.endsWith(ext)) return false
        val mid = actual.substring(base.length + 1, actual.length - ext.length)
        return mid.isNotEmpty() && mid.all { it.isDigit() }
    }

    /** 文件的真实大小（读不到时为 null）。 */
    private fun realSize(item: MediaItem): Long? = runCatching {
        app.contentResolver.openFileDescriptor(item.uri, "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it >= 0 }

    private fun sha256(item: MediaItem): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        open(item).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    companion object {
        private const val PERIODIC = "zyll-backup"
        private const val NOW = "zyll-backup-now"
        private const val FORCED_WORK = "zyll-backup-picked"
        /** 选择器用的“手机上还在不在”缓存多久在后台重查一次。 */
        private const val LOCAL_IDS_TTL_MS = 60_000L
        private const val NEW_MEDIA = "zyll-backup-new-media"
        private const val KEY_VAULT_UPLOADED_PREFIX = "vaultUploaded-"
        private const val KEY_FORCED = "forcedBackup"
        /** 排着一轮用户点的“立即备份”、还没开始跑（等网络时改了设置，重排时要保留“立即”）。 */
        private const val KEY_MANUAL_QUEUED = "manualQueued"
        private const val KEY_EXTRA_ACCOUNTS = "extraAccounts"
        private const val KEY_PICKER_DELETED = "pickerDeleted"
        private const val KEY_INDEX_DIRTY = "indexDirty"
        private const val KEY_INDEX_PUSHED = "indexPushedVersion-"
        private const val THUMB_TRIM_INTERVAL_MS = 60_000L
        private const val HASHLESS_RETRY_MS = 24 * 3600_000L
        private const val KEY_VAULT_RECHECK_AT = "vaultRecheckAt-"
        private const val KEY_ISSUE_RETRY_AT = "indexIssueRetryAt-"
        private const val ISSUE_RETRY_MS = 6 * 3600_000L
        private const val KEY_INDEX_PENDING = "indexPending"
        private const val KEY_INDEX_AT = "indexUploadedAt"
        private const val INDEX_INTERVAL_MS = 30L * 60_000
        private const val INDEX_BATCH = 200
        private const val KEY_INDEX_ID = "indexId-"
        private const val KEY_INDEX_CHECKED = "indexChecked-"
        private val INDEX_PLAIN = listOf("index.json")
        private val INDEX_ENCRYPTED = listOf(CloudConfig.ENCRYPTED_FOLDER, "index.zylc")
        private val KEYBUNDLE_PATH = listOf(CloudConfig.ENCRYPTED_FOLDER, "keybundle.json")
        /** vault/ 下固定名字的文件（搬存储位置时覆盖，不另存一份）。 */
        private val FIXED_VAULT_FILES = setOf("keybundle.json", "ai-results.zylc", "identity.zylc", "shared-albums.zylc", "share-links.zylc", "wiped.json")
        private val AI_RESULTS_PATH = listOf(CloudConfig.ENCRYPTED_FOLDER, "ai-results.zylc")
        private const val KEY_AI_MODEL = "aiResultsModel-"
        private const val KEY_AI_AT = "aiResultsAt-"
        private const val KEY_AI_VERSION = "aiResultsVersion-"
        private const val AI_UPLOAD_INTERVAL_MS = 6L * 3600_000
        private const val AI_PULL_INTERVAL_MS = 24L * 3600_000
        private const val THUMB_FOLDER = ".thumbs"
        private const val KEY_INDEX_LOCKED = "indexLocked-"
        private const val KEY_REMOTE_CHECKED = "remoteVaultChecked-"
        private const val KEY_REMOTE_BUNDLE = "remoteBundle-"
        private const val KEY_REMOTE_SETTINGS = "remoteSettings"
        private const val KEY_SETTINGS_DECIDED = "settingsDecided"
        private const val KEY_REMOTE_ABANDONED = "remoteVaultAbandoned-"
        private const val KEY_VAULT_ACCOUNTS = "vaultAccounts"
        /** 按槽位：这个账号的库在可见文件夹、还没授权读它（见 [markNeedsVisible]）。 */
        private const val KEY_NEEDS_VISIBLE = "needsVisible-"
        /** 按账号标识：断开这个账号时，核对过它网盘里的密钥包是第几版（见 [syncBundleBeforeSignOut]）。 */
        private const val KEY_VAULT_REV = "vaultStamp-"
        /** 刚用另一套密码解开、等用户选保留哪套的那套密钥（vaultId）。 */
        private const val KEY_KEY_CHOICE = "keyChoice"
        /** 这个账号的网盘里放着的是哪套旧密钥的密钥包（等着换成当前这套）。 */
        private const val KEY_REKEY_ACCOUNT = "rekeyAccount-"
        private const val KEY_RETIRED_KEYS = "retiredKeys"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_ACCOUNT_ID = "accountId-"
        private const val KEY_LIMIT_PER_KIND = "limitPerKind"
        private const val KEY_LIMIT_TOTAL = "limitTotal"
        private const val KEY_ACCOUNT_LABEL = "accountLabel-"
        private const val KEY_INDEX_VERSION = "indexVersion"
        private const val KEY_INDEX_SEQ = "indexSeq-"
        private const val TOMBSTONE_MAX = 20_000
        /** 搬存放位置时换过的 id 在目录里留多久（期间没打开过的设备之后要从头恢复记录）。 */
        private const val ID_MOVES_KEEP_MS = 90L * 24 * 3600 * 1000

        /** 旧位置挂上“正在搬”之后等多久才开始复制（让别的手机、网页版正在进行的写入做完）。 */
        private const val MIGRATION_GRACE_MS = 10 * 60_000L

        /** 复制完再列一遍旧位置，最多这么多轮。 */
        private const val MIGRATION_ROUNDS = 3

        /** 搬的时候隔多久刷新一次标记时间。 */
        private const val MIGRATION_BEAT_MS = 10 * 60_000L

        /** 别的手机的“正在搬”标记超过这么久没刷新，就提示用户可以不再等它。 */
        private const val MIGRATION_STALE_MS = 3 * 24 * 3600_000L
        private const val DELETE_BATCH = 200
        private const val VERIFY_BATCH = 100
        private const val VERIFY_HASHLESS = 20
        private const val VERIFY_DOWNLOAD_MAX = 200L shl 20
        private const val BACKFILL_HASH = 300
        private const val BACKFILL_THUMB = 60
        /** 每轮给以前备份的照片补 mo 标记的数量（每张只读开头和结尾几百 KB）。 */
        private const val BACKFILL_MOTION = 300
        private const val KEY_MOTION_BACKFILL = "motionBackfillAfter"
        private const val THUMB_PX = 384
        private val GPS_TAGS = listOf(
            androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE, androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE_REF,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE, androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE_REF,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE, androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
        )
        private const val ACCOUNT_TTL_MS = 30L * 60_000
        private const val VIEW_CACHE_BYTES = 1L shl 30
        private const val THUMB_CACHE_BYTES = 512L shl 20
        private const val KEY_SLOW = "slowUploads"
        private const val KEY_BROKEN = "brokenBackups"
        private const val KEY_MEDIA_VERSION = "mediaStoreVersion"
        private const val KEY_PULLED_AT = "indexPulledAt-"
        private const val PULL_INTERVAL_MS = 6L * 3600_000
        private const val KEY_RECONCILED_AT = "reconciledAt-"
        private const val RECONCILE_INTERVAL_MS = 7L * 24 * 3600_000
        private const val UPLOAD_ORPHAN_AGE_MS = 24 * 3600_000L
        /** 加密原图、缩略图的随机文件名（不会和别的文件重名）。 */
        private val RANDOM_NAME = Regex("""^(t-)?[0-9a-f]{24,32}\.zylc$""")
        private const val UPLOAD_MATCH_WINDOW_MS = 6 * 3600_000L
        private const val UPLOAD_LOG_MAX_AGE_MS = 90L * 24 * 3600_000L
        private val PROTECTED_NAMES = setOf("index.json", "index.zylc", "keybundle.json", "ai-results.zylc")
        private const val LIST_INSTEAD_OF_STAT = 200
        private const val RESUME_MIN_BYTES = 8L shl 20
        private const val CONVERT_SPACE_MARGIN = 200L shl 20
        private const val KEY_INDEX_CORRUPT = "indexCorrupt-"
        private const val KEY_INDEX_ROLLBACK = "indexRollback-"
        private const val KEY_INDEX_IGNORE = "indexIgnore-"
        private const val KEY_INDEX_OVERRIDE = "indexOverride-"
        private const val KEY_REMOTE_MODE = "remoteMode-"
        private const val KEY_MODE_AT = "encryptionModeAt"
        private const val TRASH_IN_INDEX = 2000
        private const val KEY_SWAP = "keySwap"
        private const val KEY_BUNDLE_CHECKED_AT = "bundleCheckedAt-"
        private const val KEY_BUNDLE_CHANGED = "bundleChangedElsewhere"
        private const val KEY_BUNDLE_CHANGED_RK = "bundleRecoveryChangedElsewhere"
        /** 改了密码或换了恢复密钥后，网盘里还是旧密钥包的账号（accountTag）。 */
        private const val KEY_BUNDLE_PENDING = "bundlePending"
        private const val KEY_SYNC = "zyll-keybundle-sync"
        private const val KEY_WIPE_AT = "wipeAt"
        /** 按账号（accountTag）：见过的“已清空”标记时间。 */
        private const val KEY_WIPE_SEEN = "wipeSeen-"
        /** 在别的设备上删除过、这台手机先不往里写的账号（accountTag）。 */
        private const val KEY_WIPE_HOLD = "wipeHold"
        private const val KEY_WIPED_NOTICE = "wipedNotice"
        private const val WIPED_NAME = "wiped.json"
        private val WIPED_PATH = listOf(CloudConfig.ENCRYPTED_FOLDER, WIPED_NAME)
        /** 发现别的设备删除过以后，这台手机删掉自己在那之前这么久以内传上去的文件。 */
        private const val WIPE_RACE_MS = 24L * 3600_000
        /** 一轮备份传很久时，隔这么久再看一眼“已清空”标记。 */
        private const val WIPE_RECHECK_MS = 5L * 60_000
        /** 传目录、密钥包之前看标记：这么久之内看过就不再读。 */
        private const val WIPE_CHECK_FRESH_MS = 60_000L
        private const val KEY_WIPE = "wipePending"
        /** 记在 [KEY_WIPE] 里：本机的密钥和记录还没清。 */
        private const val WIPE_LOCAL = "<local>"
        private const val WIPE_PARALLEL = 4
        private val THUMB_NAME = Regex("""^[0-9a-f]{24}\.jpg$""")
        private const val BUNDLE_RECHECK_MS = 24L * 3600_000

        @Volatile
        private var instance: CloudBackup? = null

        fun get(context: Context): CloudBackup =
            instance ?: synchronized(this) { instance ?: CloudBackup(context).also { instance = it } }
    }
}

/**
 * 上传时顺便算 SHA-256，并核对文件的真实长度。
 * 上传只读登记的大小（[expected]）那么多字节；读满时再偷看一个字节，确认确实到了文件末尾。
 * 文件比登记的长（登记过时、别的 App 正在写）或者更短，都算这次上传无效。
 */
private class HashingInputStream(input: InputStream, private val expected: Long) : java.io.FilterInputStream(input) {
    private val md = java.security.MessageDigest.getInstance("SHA-256")
    private var count = 0L
    private var eof = false
    private var skipped = false
    /** 读满登记大小后发现后面还有内容。 */
    var tooLong = false
        private set

    private fun probe() {
        if (count == expected && !eof && !tooLong) {
            if (`in`.read() >= 0) tooLong = true else eof = true
        }
    }

    override fun read(): Int {
        if (count >= expected && expected > 0) {
            probe()
            if (tooLong) return -1
        }
        val b = super.read()
        if (b < 0) eof = true else { md.update(b.toByte()); count++ }
        probe()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        // 不让上传读过登记的大小：多出来的字节由 probe 发现
        val allowed = if (expected > 0) minOf(len.toLong(), expected - count).toInt() else len
        if (allowed <= 0) {
            probe()
            return -1
        }
        val n = super.read(b, off, allowed)
        if (n < 0) eof = true else if (n > 0) { md.update(b, off, n); count += n }
        probe()
        return n
    }

    override fun skip(n: Long): Long {
        skipped = true
        return super.skip(n)
    }

    override fun markSupported() = false

    /** 读到的长度和登记的完全一致、而且后面没有多余内容。 */
    val lengthOk: Boolean get() = !tooLong && count == expected && eof

    fun hexIfComplete(): String? = if (lengthOk && !skipped) md.digest().toHex() else null
}

/** 上传过程中发现文件长度和登记的不一样（正在被写、登记过时）。 */
private class ChangedWhileReading : IOException(tr(R.string.backup_file_changed))

/**
 * 网盘上的备份坏了：解不开、被截断、文件类型不对，或者内容和记下的指纹对不上。
 * 重试也没用，所以和网络问题分开处理（不会卡住整轮备份）。
 */
class CorruptBackupException(cause: Throwable?) : IOException(tr(R.string.backup_file_corrupt), cause)
