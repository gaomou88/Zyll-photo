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
import app.zyll.media.MediaItem
import app.zyll.media.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    // 额外登录的账号（每种网盘的第 2 个起）要在读出任何状态之前登记好
    init {
        CloudProvider.setExtra(names.getStringSet(KEY_EXTRA_ACCOUNTS, emptySet())!!.mapNotNull(CloudProvider::fromName))
    }

    private val drives = java.util.concurrent.ConcurrentHashMap<CloudProvider, CloudDrive>()

    private fun drive(p: CloudProvider): CloudDrive = drives.getOrPut(p) {
        when (p.kind) {
            DriveKind.GoogleDrive -> GoogleDriveApi(auth, app, p)
            DriveKind.OneDrive -> OneDriveApi(auth, p)
        }
    }

    private fun forgetFolders(p: CloudProvider) {
        (drive(p) as? GoogleDriveApi)?.forgetFolders()
    }

    /** 能登录几个账号；App 按发行版和是否 Pro 设置。后台任务里不用（只限制新登录）。 */
    @Volatile var limits: AccountLimits = AccountLimits.FREE
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<BackupSettings> = _settings.asStateFlow()

    private val _drives = MutableStateFlow(CloudProvider.entries.map(::initialState))
    val driveStates: StateFlow<List<DriveState>> = _drives.asStateFlow()

    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val _backedUp = MutableStateFlow<Set<Long>>(emptySet())
    /** 已经有备份的照片（MediaStore id）。 */
    val backedUpIds: StateFlow<Set<Long>> = _backedUp.asStateFlow()

    private val _encryption = MutableStateFlow(EncryptionState(vault.wantEncrypted))

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
            .remove(KEY_INDEX_SEQ + p.name).remove(KEY_REMOTE_CHECKED + p.name).remove(KEY_VAULT_UPLOADED_PREFIX + p.name)
            .remove(KEY_REMOTE_BUNDLE + p.name).remove(KEY_REMOTE_ABANDONED + p.name).remove(KEY_SETTINGS_DECIDED)
            .remove(KEY_AI_MODEL + p.name).remove(KEY_AI_AT + p.name).remove(KEY_AI_VERSION + p.name)
            .apply()
        refreshUnlockState()
    }

    /** 网盘里找到、还没解锁的密钥包（按网盘）。 */
    private fun remoteBundles(): Map<CloudProvider, String> =
        CloudProvider.entries.mapNotNull { p -> names.getString(KEY_REMOTE_BUNDLE + p.name, null)?.let { p to it } }.toMap()

    private fun refreshUnlockState() {
        val pending = remoteBundles().isNotEmpty()
        val hasLocal = vault.bundleJson != null
        val broken = hasLocal && runCatching { vault.wrapKey() }.exceptionOrNull() is VaultBrokenException
        _encryption.update { it.copy(needsUnlock = pending, conflict = pending && hasLocal, keyBroken = broken) }
    }

    /** 这个网盘可以上传了：已经确认过网盘里有没有以前的加密备份，也读回了以前的备份目录。 */
    private fun isReady(p: CloudProvider) =
        names.getBoolean(KEY_REMOTE_CHECKED + p.name, false) && names.getBoolean(KEY_INDEX_CHECKED + p.name, false) &&
            !names.contains(KEY_REMOTE_BUNDLE + p.name)

    /** 改记录、改加密状态的操作都要拿这把锁；正在备份时请它这个文件传完就让出来。 */
    private suspend fun <T> exclusive(block: suspend () -> T): T {
        urgent = true
        return lock.withLock { block() }
    }

    private fun initialState(p: CloudProvider) =
        DriveState(p, CloudConfig.of(p).configured, auth.isSignedIn(p), auth.needsReconnect(p))

    /** 已连接网盘的简短描述，给系统照片选择器显示。没有连接时返回 null。 */
    fun accountSummary(): String? = CloudProvider.entries
        .filter { auth.isSignedIn(it) }
        .joinToString("、") { p -> names.getString(p.name, null)?.let { "${p.label}（$it）" } ?: p.label }
        .ifEmpty { null }

    /** 账号变了要换一个集合 id，系统照片选择器才会重新同步。 */
    fun pickerCollectionId(): String = "zyll-" + (accountSummary() ?: "none").hashCode().toUInt().toString(16)

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
        val s = transform(_settings.value)
        settingsStore.save(s)
        _settings.value = s
        schedule()
        if (s.enabled) backupNow(manual = true)
    }

    fun signInIntent(p: CloudProvider): Intent = auth.signInIntent(p)

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
        try {
            val wasReconnect = auth.needsReconnect(p)
            auth.complete(p, data)
            if (wasReconnect) {
                // 存储位置变了，旧位置的记录作废，重新备份到新位置
                records.clear(p)
                forgetFolders(p)
                reloadRecords()
            }
            refreshAccount(p)
            // 同一个账号不能登录两次：两个槽位会往同一个网盘目录里互相覆盖
            val id = names.getString(KEY_ACCOUNT_ID + p.name, null)
            if (id != null && CloudProvider.entries.any { it != p && it.kind == p.kind && auth.isSignedIn(it) && names.getString(KEY_ACCOUNT_ID + it.name, null) == id }) {
                signOut(p)
                _accountNotice.value = tr(R.string.account_already_signed_in)
                return
            }
            resetAccount(p)
            // 换了手机或重装后：先看网盘里有没有以前的加密密钥包，再从备份目录找回以前备份过的照片
            scope.launch {
                lock.withLock {
                    runCatching { checkRemoteVault(p) }
                    runCatching { restoreIndex(p, vault.wrapKey()) }
                }
                reloadRecords()
            }
            notifyPicker()
            schedule()
            if (_settings.value.enabled) backupNow(manual = true)
        } catch (e: IOException) {
            if (p.slot > 0 && !auth.isSignedIn(p)) {
                removeSlot(p)
                _accountNotice.value = e.message
            } else {
                setDrive(p) { it.copy(error = e.message) }
            }
        }
    }

    fun signOut(p: CloudProvider) {
        auth.signOut(p)
        // 换账号后旧记录不再成立，下次连接时重新检查
        records.clear(p)
        records.clearAccountTables(p)
        resetAccount(p)
        names.edit().remove(p.name).remove(KEY_ACCOUNT_ID + p.name).apply()
        forgetFolders(p)
        setDrive(p) { initialState(p) }
        removeSlot(p)
        notifyPicker()
        scope.launch { reloadRecords() }
        schedule()
    }

    suspend fun refreshAccounts() = CloudProvider.entries.forEach { refreshAccount(it) }

    private val accountCheckedAt = java.util.concurrent.ConcurrentHashMap<CloudProvider, Long>()

    /** 30 分钟内查过网盘空间就直接用上次的结果，不用每轮都联网。 */
    private fun cachedAccount(p: CloudProvider): DriveAccount? {
        val at = accountCheckedAt[p] ?: return null
        if (android.os.SystemClock.elapsedRealtime() - at > ACCOUNT_TTL_MS || !auth.isSignedIn(p)) return null
        return _drives.value.firstOrNull { it.provider == p }?.account
    }

    private suspend fun refreshAccount(p: CloudProvider): DriveAccount? {
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
            CloudEntry(it.provider, it.remoteId, it.encrypted, it.name, it.album, it.mime, it.takenAt, it.size, it.hash, it.mediaId, it.modified, it.thumb)
        }
        // 网盘内容真的变了才让系统照片选择器重新同步
        val sig = all.fold(all.size.toLong()) { acc, r -> acc * 31 + (r.provider.name + r.remoteId + r.name + r.takenAt).hashCode() }
        if (sig != pickerSig) {
            pickerSig = sig
            notifyPicker()
        }
        _usage.value = all.distinctBy { it.provider to it.remoteId }.groupBy { it.provider }.mapValues { (_, v) -> v.sumOf { it.size } }
        val want = vault.wantEncrypted
        val cleanup = records.pendingDeleteCount()
        _encryption.update { it.copy(enabled = want, remaining = all.count { e -> e.encrypted != want }, pendingCleanup = cleanup) }
    }

    // ---------- 端到端加密 ----------

    /** 本机还留着加密密钥（开着加密，或刚关闭、旧备份还没全部转换完）。 */
    val hasEncryptionKey: Boolean get() = vault.bundleJson != null

    /** 开启加密：生成密钥，之后把所有已有备份换成加密版本。返回要让用户抄下的恢复密钥。 */
    suspend fun enableEncryption(password: String): String? = withContext(Dispatchers.Default) {
        exclusive {
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
                vault.checkPassword(password)
                vault.wantEncrypted = true
                null
            } else {
                // 新的一套密钥：以前记的“密钥包已上传”都不算数了
                val edit = names.edit()
                CloudProvider.entries.forEach { edit.remove(KEY_VAULT_UPLOADED_PREFIX + it.name) }
                edit.putStringSet(KEY_VAULT_ACCOUNTS, emptySet()).apply()
                vault.create(password)
            }
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
        exclusive {
            fun unlockWith(json: String) = if (recoveryKey) KeyBundle.unlockWithRecoveryKey(json, secret.trim()) else KeyBundle.unlockWithPassword(json, secret)
            val local = vault.bundleJson
            if (local != null && _encryption.value.keyBroken) {
                val unlocked = try { unlockWith(local) } catch (e: Exception) { throw IllegalArgumentException(if (recoveryKey) tr(R.string.e2ee_wrong_recovery_key) else tr(R.string.e2ee_wrong_password), e) }
                // 只是修好本机的密钥，加密开关保持原样
                vault.adopt(local, unlocked.wrapKey, want = vault.wantEncrypted)
            } else {
                if (local != null) throw IllegalStateException(tr(R.string.e2ee_other_key_in_use))
                val bundles = remoteBundles()
                if (bundles.isEmpty()) throw IllegalStateException(tr(R.string.e2ee_no_remote_backup))
                val (json, unlocked) = bundles.values.distinct().firstNotNullOfOrNull { json -> runCatching { json to unlockWith(json) }.getOrNull() }
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
     * [account] 是界面上显示的名字（[EncryptionState.waitingAccounts] 里的一项）。
     */
    fun giveUpVaultAccount(account: String) {
        scope.launch {
            exclusive {
                val left = vaultAccounts().filterNot { t -> tagLabel(t) == account }.toSet()
                names.edit().putStringSet(KEY_VAULT_ACCOUNTS, left).apply()
                _encryption.update { it.copy(waitingAccounts = it.waitingAccounts - account) }
            }
            backupNow(manual = true)
        }
    }

    /** 关闭加密：核对密码后，把所有加密备份换回普通文件。密码不对会抛异常。 */
    suspend fun disableEncryption(password: String) = withContext(Dispatchers.Default) {
        exclusive {
            vault.checkPassword(password)
            vault.wantEncrypted = false
            reloadRecords()
        }
        backupNow(manual = true)
    }

    // ---------- 后台任务 ----------

    /** 按当前设置安排（或取消）定期备份和“有新照片就备份”。 */
    fun schedule() {
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
        if (manual) {
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

    /** 离开 App 时：还没传的备份目录补传一次。 */
    fun flushIndex() {
        if (!names.getBoolean(KEY_INDEX_DIRTY, false)) return
        // 设了仅 Wi‑Fi 时不用流量传，留给下一轮备份
        if (_settings.value.wifiOnly && isMetered()) return
        scope.launch { uploadIndexNow() }
    }

    /** 立刻把备份目录传上去（备份任务结束时、离开 App 时）。旧手机突然丢了，新手机也能看到最近备份的照片。 */
    internal suspend fun uploadIndexNow() {
        if (!names.getBoolean(KEY_INDEX_DIRTY, false)) return
        runCatching {
            lock.withLock {
                val connected = _drives.value.filter { it.signedIn && it.account != null }.map { it.provider }.toSet()
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

    private fun isMetered(): Boolean =
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
    internal suspend fun runBackup(isStopped: () -> Boolean, manual: Boolean = false, progress: suspend (BackupStatus) -> Unit): Boolean {
        val result = runBackupLocked(isStopped, manual, progress)
        // AI 整理结果的同步要传十几 MB，放在锁外面做：期间删除、恢复、改信息不用等它
        aiSyncPending.getAndSet(null)?.let { job -> if (!isStopped()) runCatching { syncAiResults(job) } }
        return result
    }

    /** 一轮备份结束时记下的 AI 结果同步任务（备份记录的快照），锁外执行。 */
    private class AiSyncJob(val connected: Set<CloudProvider>, val key: ByteArray, val records: List<RecordEntry>)
    private val aiSyncPending = java.util.concurrent.atomic.AtomicReference<AiSyncJob?>(null)
    private val aiSyncLock = Mutex()

    private suspend fun runBackupLocked(isStopped: () -> Boolean, manual: Boolean, progress: suspend (BackupStatus) -> Unit): Boolean = lock.withLock {
        val s = _settings.value
        urgent = false
        val want = vault.wantEncrypted
        val stale = records.entries().filter { it.encrypted != want }
        val forced = names.getStringSet(KEY_FORCED, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        if (!s.enabled && stale.isEmpty() && forced.isEmpty()) return@withLock true
        setStatus(progress) { it.copy(phase = BackupPhase.Checking, message = null, manual = manual, total = 0, done = 0) }
        try {
            val statuses = CloudProvider.entries.map { p ->
                val account = cachedAccount(p) ?: refreshAccount(p)
                DriveStatus(p, connected = account != null, freeBytes = account?.freeBytes)
            }
            val online = statuses.filter { it.connected }.map { it.provider }.toSet()
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
            if (_encryption.value.needsUnlock) {
                // 以前的加密备份还没解锁：先不传新照片，免得同一个账号里一半加密一半不加密
                setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.e2ee_unlock_in_settings)) }
                return@withLock true
            }
            val connected = online.filter { isReady(it) }.toSet()
            val notReady = online - connected
            if (connected.isEmpty()) {
                setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.backup_reading_remote)) }
                return@withLock false
            }
            // 上次没删掉的旧文件，这次接着删
            processDeletes(connected)
            // 加密的文件上传之前，先保证网盘里有这套密钥的密钥包（换手机时靠它解开）
            if (want && key != null) {
                uploadKeyBundle(connected)
                if (_encryption.value.needsUnlock) {
                    setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.e2ee_conflict_in_settings)) }
                    return@withLock true
                }
            }
            val items = MediaRepository(app).loadAll().filter { it.sizeBytes > 0 }
            // 换手机后恢复出来的记录配上本机照片（在锁里做，不会和这一轮的上传同时改记录）
            if (runCatching { linkRestored(items) }.getOrDefault(false)) reloadRecords()
            registerAlbums(items.map { it.bucketId }.distinct())
            val local = items.associateBy { it.id }
            val dead = HashSet<CloudProvider>()

            // ---- 1. 转换旧备份：一律先拿到明文（本机有就用本机的，没有就从网盘下载），再按新形式上传，最后删旧的 ----
            val todo = stale.filter { it.provider in connected }
            if (todo.isNotEmpty()) {
                val phase = if (want) BackupPhase.Encrypting else BackupPhase.Decrypting
                setStatus(progress) { it.copy(phase = phase, total = todo.size, done = 0) }
                _encryption.update { it.copy(converting = true) }
                var failed = 0
                // 重复照片共用一个网盘文件：同一个文件只转换一次，所有指向它的记录一起更新
                val groups = todo.groupBy { it.provider to it.remoteId }.values.toList()
                var doneCount = 0
                for (group in groups) {
                    val e = group.first()
                    doneCount += group.size
                    val i = doneCount - 1
                    if (isStopped()) return@withLock false
                    if (urgent) {
                        // 有人在等这把锁（用户在删除、恢复、改照片信息）：先让出来，马上接着转换
                        rerun = true
                        _encryption.update { it.copy(converting = false) }
                        markIndexDirty()
                        reloadRecords()
                        return@withLock true
                    }
                    if (e.provider in dead) continue
                    try {
                        val src = group.firstNotNullOfOrNull { g -> local[g.mediaId]?.takeIf { it.modifiedAt == g.modified } }
                        convert(group, src, want, key)
                    } catch (ex: NotSignedInException) {
                        dead += e.provider
                        failed++
                    } catch (ex: IOException) {
                        failed++
                        if (failed >= 5) break
                    }
                    setStatus(progress) { it.copy(done = i + 1, current = e.name) }
                    _encryption.update { it.copy(remaining = (it.remaining - group.size).coerceAtLeast(0)) }
                }
                _encryption.update { it.copy(converting = false) }
                markIndexDirty()
                reloadRecords()
                if (failed > 0) {
                    setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.backup_convert_pending, failed)) }
                    // 不能一半加密一半不加密：没转换完之前不上传新照片
                    return@withLock false
                }
            }
            if (!want && vault.bundleJson != null) finishDisable(connected, key)
            if (!s.enabled && forced.isEmpty()) {
                runCatching { uploadIndex(connected, want, key, force = todo.isNotEmpty()) }
                setStatus(progress) { it.copy(phase = BackupPhase.Idle, lastFinishedAt = System.currentTimeMillis()) }
                return@withLock true
            }

            // ---- 2. 上传新照片 ----
            // 这一轮的记录只读一遍，后面都用它
            val snapshot = records.entries()
            val recs = snapshot.groupBy { it.mediaId }
            val router = BackupRouter(_settings.value, statuses.map { it.copy(connected = it.provider in connected) })
            val jobs = ArrayList<Pair<MediaItem, List<CloudProvider>>>()
            var eligible = 0
            var backedUp = 0
            var full = false
            // 手动点了“备份”的照片：不管相簿规则和自动备份开没开，都备份到能用的网盘
            val fallback = listOfNotNull(s.primary.takeIf { it in connected } ?: connected.firstOrNull())
            for (item in items) {
                if (!s.enabled && item.id !in forced) continue
                val already = recs[item.id].orEmpty().filter { it.modified == item.modifiedAt }.map { it.provider }.toSet()
                if (item.id in forced && already.isEmpty()) {
                    val r = router.route(BackupCandidate(item.id, item.bucketId, item.sizeBytes, item.isVideo), already)
                    eligible++
                    jobs.add(item to ((r as? Route.Upload)?.providers?.takeIf { it.isNotEmpty() } ?: fallback))
                    continue
                }
                when (val r = router.route(BackupCandidate(item.id, item.bucketId, item.sizeBytes, item.isVideo), already)) {
                    is Route.Upload -> {
                        eligible++
                        if (r.providers.isEmpty()) {
                            backedUp++
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
                    }
                    Route.NoDrive -> {
                        eligible++
                    }
                    Route.Skipped -> {}
                }
            }
            // 手动选的照片排在最前面
            jobs.sortBy { if (it.first.id in forced) 0 else 1 }
            setStatus(progress) {
                it.copy(phase = if (jobs.isEmpty()) BackupPhase.Checking else BackupPhase.Uploading, total = jobs.size, done = 0, backedUp = backedUp, eligible = eligible)
            }
            // 只有网盘里已有同样大小的文件时才算内容指纹（用来去重），大部分照片不用多读一遍
            val knownSizes = snapshot.mapTo(HashSet()) { it.size }

            // 内容指纹 → 各网盘上已有的文件：完全相同的照片只传一份
            val byHash = HashMap<String, MutableMap<CloudProvider, String>>()
            snapshot.forEach { e -> if (e.hash.isNotEmpty()) byHash.getOrPut(e.hash) { HashMap() }[e.provider] = e.remoteId }
            var failures = 0
            var uploaded = 0
            for ((index, job) in jobs.withIndex()) {
                if (isStopped()) return@withLock false
                if (urgent) {
                    // 用户又手动选了照片：这一轮先到这里，马上开下一轮先传那些
                    rerun = true
                    break
                }
                val (item, providers) = job
                var allOk = true
                // 文件实际大小和系统登记的不一样（登记过时、正在被写）：这次先跳过，下次再传
                val real = realSize(item)
                if (real != null && real != item.sizeBytes) {
                    setStatus(progress) { it.copy(done = index + 1, current = item.displayName) }
                    continue
                }
                // 网盘里已有同样大小的文件时先算指纹，看能不能直接复用；其他照片在上传时边读边算，不多读一遍
                var hash = if (item.sizeBytes !in knownSizes) "" else try {
                    sha256(item)
                } catch (e: IOException) {
                    ""
                }
                for (p in providers) {
                    if (p in dead) continue
                    try {
                        val existing = if (hash.isNotEmpty()) byHash[hash]?.get(p) else null
                        val remoteId = existing ?: run {
                            var reader: HashingInputStream? = null
                            val id = retrying {
                                drive(p).upload(
                                    uploadFile(item.displayName, item.bucketName, item.mimeType, item.sizeBytes, item.takenAt, want, key) {
                                        HashingInputStream(open(item), item.sizeBytes).also { reader = it }
                                    },
                                )
                            }
                            if (reader?.lengthOk != true) {
                                // 读到的长度和登记的对不上：这份备份不完整，删掉，下次重传
                                records.enqueueDelete(p, id)
                                throw ChangedWhileReading()
                            }
                            if (hash.isEmpty()) {
                                hash = reader?.hexIfComplete() ?: runCatching { sha256(item) }.getOrDefault("")
                            }
                            id
                        }
                        knownSizes += item.sizeBytes
                        if (hash.isNotEmpty()) byHash.getOrPut(hash) { HashMap() }[p] = remoteId
                        // 存一张小缩略图：本机删掉原图后，相册里还能看到它；也传一份到网盘，换手机后不用下载原图
                        val thumbFile = runCatching { saveThumb(item, hash.ifEmpty { p.name + "-" + remoteId }) }.getOrNull()
                        val thumbId = if (existing != null) {
                            snapshot.firstOrNull { it.provider == p && it.remoteId == existing }?.thumb.orEmpty()
                        } else {
                            thumbFile?.let { f -> runCatching { uploadThumb(p, f, want, key) }.getOrNull() }.orEmpty()
                        }
                        replaceRecord(entryFor(item, p, remoteId, want, hash).copy(thumb = thumbId))
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
                    } catch (e: IOException) {
                        allOk = false
                        // 连续失败多半是网络断了，停下来等 WorkManager 重试
                        if (++failures >= 5) {
                            setStatus(progress) { it.copy(phase = BackupPhase.Idle, message = tr(R.string.backup_network_continue)) }
                            return@withLock false
                        }
                    }
                }
                if (allOk && item.id in forced) removeForced(item.id)
                setStatus(progress) { it.copy(done = index + 1, backedUp = it.backedUp + if (allOk) 1 else 0, current = item.displayName) }
            }
            // 本机已经不在的照片也不用再强制备份了
            forced.filter { it !in local }.forEach(::removeForced)
            if (!isStopped() && !urgent) runCatching { backfill(local, want, key) { isStopped() || urgent } }
            runCatching { uploadIndex(connected, want, key, force = todo.isNotEmpty()) }
            // 只在端到端加密开着、本机拿着密钥时同步
            if (want && key != null && aiResults != null) aiSyncPending.set(AiSyncJob(connected, key, records.entries()))
            reloadRecords()
            // 传了东西，剩余空间变了，下一轮重新查
            if (uploaded > 0) accountCheckedAt.clear()
            setStatus(progress) {
                it.copy(
                    phase = BackupPhase.Idle,
                    current = null,
                    lastFinishedAt = System.currentTimeMillis(),
                    lastRunUploaded = uploaded,
                    message = when {
                        dead.isNotEmpty() -> dead.joinToString("、") { p -> p.label } + tr(R.string.backup_needs_sign_in_suffix)
                        full -> tr(R.string.backup_cloud_full)
                        notReady.isNotEmpty() -> notReady.joinToString("、") { p -> p.label } + tr(R.string.backup_still_reading_suffix)
                        else -> null
                    },
                )
            }
            // 有网盘还没确认完：稍后再来一轮
            notReady.isEmpty()
        } finally {
            _status.update { if (it.running) it.copy(phase = BackupPhase.Idle) else it }
        }
    }

    /**
     * 给以前的备份补上缺的东西：充电时补算内容指纹（换手机后靠它认出同一张照片），
     * 每轮再给几十张补传网盘缩略图。都有数量上限，不会一次跑很久。
     */
    private suspend fun backfill(local: Map<Long, MediaItem>, want: Boolean, key: ByteArray?, isStopped: () -> Boolean) {
        var changed = false
        // 换手机后按名字配上、还没核对指纹的：核对一下，对上了才算已备份，对不上就放回“只在网盘里”
        records.entries().filter { it.mediaId > 0 && it.modified == 0L && it.hash.isNotEmpty() }.take(VERIFY_BATCH).forEach { r ->
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
        val fresh = if (changed) records.entries() else all
        fresh.filter { it.thumb.isEmpty() && it.encrypted == want }
            .distinctBy { it.provider to it.remoteId }
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

    private fun isCharging(): Boolean =
        app.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true

    private suspend fun setStatus(progress: suspend (BackupStatus) -> Unit, f: (BackupStatus) -> BackupStatus) {
        val s = f(_status.value)
        _status.value = s
        progress(s)
    }

    /** 把一份旧形式的备份换成新形式。 */
    private suspend fun convert(group: List<RecordEntry>, localItem: MediaItem?, encrypt: Boolean, key: ByteArray?) {
        val e = group.first()
        val drive = drive(e.provider)
        val tmpDir = File(app.cacheDir, "cloud-convert").apply { mkdirs() }
        val raw = File(tmpDir, "raw")
        val plain = File(tmpDir, "plain")
        try {
            // 从网盘下载旧文件（加密的先解密）当来源
            suspend fun remoteSource(): Pair<Long, () -> InputStream> {
                retrying { raw.outputStream().use { drive.download(e.remoteId, it) } }
                return if (e.encrypted) {
                    val k = key ?: throw IOException(tr(R.string.e2ee_key_missing))
                    raw.inputStream().use { input -> plain.outputStream().use { Zylc.decryptStream(input, k, it) } }
                    plain.length() to { plain.inputStream() }
                } else {
                    raw.length() to { raw.inputStream() }
                }
            }
            // 本机原图能不能用：大小和登记的一致；原来那份备份有指纹时，先算本机指纹，对不上就不用（省一次白传）
            val local = localItem?.takeIf { item ->
                val real = realSize(item)
                (real == null || real == item.sizeBytes) &&
                    (e.hash.isEmpty() || runCatching { sha256(item) }.getOrNull() == e.hash)
            }
            var source: Pair<Long, () -> InputStream>
            var newId: String
            if (local != null) {
                var reader: HashingInputStream? = null
                source = local.sizeBytes to { HashingInputStream(open(local), local.sizeBytes).also { reader = it } }
                newId = retrying { drive.upload(uploadFile(e.name, e.album, e.mime, source.first, e.takenAt, encrypt, key, source.second)) }
                val h = reader?.hexIfComplete()
                if (h == null || (e.hash.isNotEmpty() && h != e.hash)) {
                    // 上传过程中本机文件变了：这份作废，同一次就改用网盘上的旧文件转换，不让整轮卡住
                    deleteNowOrLater(e.provider, newId)
                    source = remoteSource()
                    newId = retrying { drive.upload(uploadFile(e.name, e.album, e.mime, source.first, e.takenAt, encrypt, key, source.second)) }
                }
            } else {
                // 本机已经删了（或者本机那份和备份对不上），用网盘上的旧文件
                source = remoteSource()
                newId = retrying { drive.upload(uploadFile(e.name, e.album, e.mime, source.first, e.takenAt, encrypt, key, source.second)) }
            }
            // 缩略图也换成新形式（加密时不能留明文缩略图）
            val oldThumbFile = File(thumbDir, e.hash.ifEmpty { e.provider.name + "-" + e.remoteId } + ".jpg")
            val newThumbFile = File(thumbDir, e.hash.ifEmpty { e.provider.name + "-" + newId } + ".jpg")
            if (oldThumbFile != newThumbFile && oldThumbFile.isFile) oldThumbFile.renameTo(newThumbFile)
            if (e.thumb.isNotEmpty()) deleteNowOrLater(e.provider, e.thumb)
            val newThumb = if (newThumbFile.isFile) runCatching { uploadThumb(e.provider, newThumbFile, encrypt, key) }.getOrDefault("") else ""
            group.forEach { records.put(it.copy(remoteId = newId, encrypted = encrypt, size = source.first, thumb = newThumb)) }
            // 旧文件告诉系统照片选择器已删除，不然那边会留一份打不开的
            rememberPickerDeleted(group.take(1))
            // 旧形式的文件（比如开启加密后的明文原图）一定要删掉：这次删不掉就留在队列里，下轮接着删
            discardRemote(e.provider, e.remoteId, "")
        } finally {
            raw.delete()
            plain.delete()
        }
    }

    /** 把本机的密钥包传到网盘（换手机时要用）。网盘上已经有另一套的密钥包时不覆盖（用户放弃过的除外）。 */
    private suspend fun uploadKeyBundle(connected: Set<CloudProvider>) {
        val bundle = vault.bundleJson ?: return
        val mine = vault.vaultId
        for (p in connected) {
            if (names.getBoolean(KEY_VAULT_UPLOADED_PREFIX + p.name, false)) continue
            try {
                val drive = drive(p)
                val existing = retrying { drive.findFile(KEYBUNDLE_PATH) }
                if (existing != null) {
                    val out = java.io.ByteArrayOutputStream()
                    retrying { out.reset(); drive.download(existing, out) }
                    val text = String(out.toByteArray())
                    val theirs = runCatching { KeyBundle.parse(text).vaultId }.getOrNull()
                    if (theirs == mine) {
                        addVaultAccount(p)
                        continue
                    }
                    if ((theirs ?: "") != names.getString(KEY_REMOTE_ABANDONED + p.name, null)) {
                        // 另一套密钥：先停下来让用户决定
                        names.edit().putString(KEY_REMOTE_BUNDLE + p.name, text).apply()
                        refreshUnlockState()
                        continue
                    }
                }
                val bytes = bundle.toByteArray()
                retrying { drive.upload(UploadFile(KEYBUNDLE_PATH, "application/json", bytes.size.toLong(), System.currentTimeMillis(), replace = true) { bytes.inputStream() }) }
                addVaultAccount(p)
            } catch (_: IOException) {
            }
        }
    }

    // ---------- 系统照片选择器（别的 App 选图时显示网盘里的照片） ----------

    /** 选择器里的一项。[localUri] 不为空表示手机里也有这张，选择器会去重。 */
    class PickerItem(val id: String, val entry: CloudEntry, val generation: Long, val localUri: android.net.Uri?)

    private fun pickerId(p: CloudProvider, remoteId: String) = "${p.ordinal}_$remoteId"

    /** 当前网盘里的全部照片（同一份内容只出现一次）。直接读数据库，选择器在后台线程调用。 */
    fun pickerItems(): List<PickerItem> {
        val all = records.entries()
        val localIds = existingMediaIds(all.map { it.mediaId }.filter { it > 0 })
        return all.groupBy { it.provider to it.remoteId }.values
            .map { group -> group.firstOrNull { it.mediaId in localIds } ?: group.first() }
            .distinctBy { it.hash.ifEmpty { it.provider.name + it.remoteId } }
            .map { r ->
                val local = r.mediaId.takeIf { it in localIds }?.let {
                    val base = if (r.mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    android.content.ContentUris.withAppendedId(base, it)
                }
                PickerItem(
                    pickerId(r.provider, r.remoteId),
                    CloudEntry(r.provider, r.remoteId, r.encrypted, r.name, r.album, r.mime, r.takenAt, r.size, r.hash, r.mediaId, r.modified, r.thumb),
                    r.uploadedAt.coerceAtLeast(1),
                    local,
                )
            }
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
            CloudEntry(r.provider, r.remoteId, r.encrypted, r.name, r.album, r.mime, r.takenAt, r.size, r.hash, r.mediaId, r.modified, r.thumb)
        }
    }

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
        backupNow(manual = true)
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

    private fun thumbKey(e: CloudEntry) = e.hash.ifEmpty { e.provider.name + "-" + e.remoteId }

    /** 网盘照片的缩略图：先用本机存的，没有就把原图下回来现做一张。 */
    suspend fun thumbnail(e: CloudEntry): File = withContext(Dispatchers.IO) {
        val f = File(thumbDir, thumbKey(e) + ".jpg")
        if (f.isFile && f.length() > 0) return@withContext f
        // 设了仅 Wi‑Fi 时，用流量不拉缩略图
        if (_settings.value.wifiOnly && isMetered()) throw IOException(tr(R.string.thumb_after_wifi))
        if (e.thumb.isNotEmpty()) {
            val out = java.io.ByteArrayOutputStream()
            retrying { out.reset(); drive(e.provider).download(e.thumb, out) }
            val raw = out.toByteArray()
            val jpeg = if (e.encrypted) Zylc.decrypt(raw, vault.wrapKey() ?: throw IOException(tr(R.string.e2ee_unlock_first_short)), BlobKind.THUMBNAIL) else raw
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

    /** 把网盘里的原图下载（加密的顺便解密）到缓存，返回本地文件。 */
    suspend fun original(e: CloudEntry): File = withContext(Dispatchers.IO) {
        val lock = synchronized(downloadLocks) { downloadLocks.getOrPut(e.key) { Mutex() } }
        lock.withLock {
            viewDir.mkdirs()
            val ext = e.name.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "bin"
            val target = File(viewDir, "${e.provider.name}-${e.remoteId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.$ext")
            if (target.isFile && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            val tmp = File(viewDir, target.name + ".part")
            try {
                retrying { tmp.outputStream().use { drive(e.provider).download(e.remoteId, it) } }
                if (e.encrypted) {
                    val k = vault.wrapKey() ?: throw IOException(tr(R.string.e2ee_photo_locked))
                    tmp.inputStream().use { input -> target.outputStream().use { Zylc.decryptStream(input, k, it) } }
                    tmp.delete()
                } else if (!tmp.renameTo(target)) {
                    throw IOException(tr(R.string.cache_write_failed))
                }
            } catch (ex: Exception) {
                tmp.delete()
                target.delete()
                throw ex
            }
            trimViewCache()
            target
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
    suspend fun restore(list: List<CloudEntry>, repo: MediaRepository): Int = withContext(Dispatchers.IO) { exclusive { restoreLocked(list, repo) } }

    private suspend fun restoreLocked(list: List<CloudEntry>, repo: MediaRepository): Int {
        var ok = 0
        val all = records.entries()
        for (e in list.distinctBy { it.hash.ifEmpty { it.key } }) {
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
    ) = withContext(Dispatchers.IO) { exclusive { editLocked(e, name, takenAt, location, clearLocation) } }

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
                val key = if (e.encrypted) vault.wrapKey() ?: throw IOException(tr(R.string.e2ee_unlock_first_short)) else null
                val drive = drive(e.provider)
                val size = tmp.length()
                val newId = retrying { drive.upload(uploadFile(newName, e.album, e.mime, size, takenAt ?: e.takenAt, e.encrypted, key) { tmp.inputStream() }) }
                val hash = fileSha(tmp)
                group.forEach { records.put(it.copy(remoteId = newId, name = newName, takenAt = takenAt ?: it.takenAt, size = size, hash = hash)) }
                rememberPickerDeleted(group.take(1))
                discardRemote(e.provider, e.remoteId, "")
                // 改好的文件直接放进查看缓存，缩略图换成新的
                File(viewDir, "${e.provider.name}-${newId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.${ext.ifEmpty { "bin" }}").let { tmp.copyTo(it, overwrite = true) }
                src.delete()
                File(thumbDir, thumbKey(e) + ".jpg").delete()
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
    suspend fun deleteFromCloud(list: List<CloudEntry>): Int = withContext(Dispatchers.IO) { exclusive { deleteLocked(list) } }

    private suspend fun deleteLocked(list: List<CloudEntry>): Int {
        var ok = 0
        val all = records.entries()
        val targets = list.flatMap { e ->
            all.filter { r -> (e.hash.isNotEmpty() && r.hash == e.hash) || (r.provider == e.provider && r.remoteId == e.remoteId) }
        }.distinctBy { it.provider to it.remoteId }
        for (r in targets) {
            // 删不掉（断网）的记进待删除队列，下次联网接着删；在相册里马上就看不到了
            discardRemote(r.provider, r.remoteId, r.thumb)
            records.removeRemote(r.provider, r.remoteId)
            rememberPickerDeleted(listOf(r))
            File(thumbDir, r.hash.ifEmpty { r.provider.name + "-" + r.remoteId } + ".jpg").delete()
            ok++
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
    private suspend fun uploadThumb(p: CloudProvider, f: File, encrypt: Boolean, key: ByteArray?): String {
        val plain = f.readBytes()
        val bytes = if (encrypt) Zylc.encrypt(plain, BlobKind.THUMBNAIL, key ?: throw IOException(tr(R.string.e2ee_encryption_key_missing))) else plain
        val name = ByteArray(12).also { SecureRandom().nextBytes(it) }.toHex()
        val path = if (encrypt) listOf(CloudConfig.ENCRYPTED_FOLDER, "t-$name.zylc") else listOf(THUMB_FOLDER, "$name.jpg")
        return retrying {
            drive(p).upload(UploadFile(path, if (encrypt) "application/octet-stream" else "image/jpeg", bytes.size.toLong(), System.currentTimeMillis()) { bytes.inputStream() })
        }
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

    private fun markIndexDirty() = names.edit()
        .putBoolean(KEY_INDEX_DIRTY, true)
        .putInt(KEY_INDEX_PENDING, names.getInt(KEY_INDEX_PENDING, 0) + 1)
        .putLong(KEY_INDEX_VERSION, names.getLong(KEY_INDEX_VERSION, 0) + 1)
        .apply()

    /** 从网盘读回来的备份目录（加密和不加密的两份合在一起）。 */
    private class PulledIndex(
        val items: List<JSONObject>,
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
    ) {
        val exists get() = encId != null || plainId != null
    }

    /**
     * 读网盘上的备份目录。读不到（断网、目录损坏、加密目录还没解锁）就抛异常，调用方据此不去覆盖它。
     * 加密目录在这台手机没有密钥、而且用户已经放弃以前的加密备份（或网盘里根本没有密钥包）时跳过。
     */
    private suspend fun pullIndex(p: CloudProvider, key: ByteArray?): PulledIndex {
        val drive = drive(p)
        val encId = retrying { drive.findFile(INDEX_ENCRYPTED) }
        val plainId = retrying { drive.findFile(INDEX_PLAIN) }
        suspend fun download(id: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            retrying { out.reset(); drive.download(id, out) }
            return out.toByteArray()
        }
        val docs = ArrayList<JSONObject>()
        var encMerged = false
        val abandoned = names.contains(KEY_REMOTE_ABANDONED + p.name)
        if (encId != null) {
            val opened = if (key != null) {
                try {
                    JSONObject(String(Zylc.decrypt(download(encId), key, BlobKind.INDEX_SNAPSHOT)))
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    // 用现在的密钥解不开：用户放弃了的那套旧密钥加密的，或者网盘里根本没有能解开它的密钥包，跳过它（不删）。
                    // 还没确认过网盘里的密钥包时不能跳过
                    val noBundle = names.getBoolean(KEY_REMOTE_CHECKED + p.name, false) && !names.contains(KEY_REMOTE_BUNDLE + p.name)
                    if (!abandoned && !noBundle) throw IOException(tr(R.string.e2ee_index_cannot_open), e)
                    null
                }
            } else {
                null
            }
            if (opened != null) {
                docs += opened
                encMerged = true
            } else if (key == null && (names.contains(KEY_REMOTE_BUNDLE + p.name) || !names.getBoolean(KEY_REMOTE_CHECKED + p.name, false))) {
                names.edit().putBoolean(KEY_INDEX_LOCKED + p.name, true).apply()
                throw IOException(tr(R.string.e2ee_index_locked))
            }
        }
        if (plainId != null) docs += JSONObject(String(download(plainId)))
        // 新的在前：同一个文件两份里都有时用新的那份
        docs.sortByDescending { it.optLong("seq") }
        val seq = docs.maxOfOrNull { it.optLong("seq") } ?: 0L
        // 防回滚：网盘上的目录不能比这台手机上次看到的还旧（旧版本写的目录没有序号，不查）
        if (docs.any { it.has("seq") } && seq < names.getLong(KEY_INDEX_SEQ + p.name, 0)) throw IOException(tr(R.string.backup_index_older))
        val deleted = HashSet<String>()
        docs.forEach { d -> d.optJSONArray("del")?.let { a -> for (i in 0 until a.length()) deleted += a.optString(i) } }
        val seen = HashSet<String>()
        val items = ArrayList<JSONObject>()
        docs.forEach { d ->
            val arr = d.optJSONArray("items") ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = o.optString("r")
                if (r.isNotEmpty() && r !in deleted && seen.add(r)) items += o
            }
        }
        val devices = items.associate { it.optString("r") to it.optString("d") }.filterValues { it.isNotEmpty() }
        // 同一个文件可能几台手机都在用（相同照片只存一份）：只要有一份记录不是这台手机的就算共享
        val foreign = HashSet<String>()
        docs.forEach { d ->
            val arr = d.optJSONArray("items") ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dev = o.optString("d")
                if (dev.isNotEmpty() && dev != deviceId) foreign += o.optString("r")
            }
        }
        return PulledIndex(items, deleted, docs.firstNotNullOfOrNull { it.optJSONObject("settings") }, seq, encId, plainId, encMerged, devices, foreign)
    }

    /**
     * 把网盘目录合进本机记录：别的手机删掉的文件，本机记录也删；本机没有的文件，加成“只在网盘里”的记录。
     * 返回有没有改动。
     */
    private fun mergePulled(p: CloudProvider, pulled: PulledIndex): Boolean {
        val mine = records.entries().filter { it.provider == p }
        val known = mine.mapTo(HashSet()) { it.remoteId }
        val gone = tombstones(p)
        var changed = false
        for (r in pulled.deleted) {
            if (r in known) {
                records.removeRemote(p, r)
                changed = true
            }
        }
        records.addTombstones(p, pulled.deleted - gone, TOMBSTONE_MAX)
        // 别的手机的记录也在用的文件：这台手机替换旧版本时不能删
        records.markShared(p, pulled.foreign)
        for (o in pulled.items) {
            val remoteId = o.getString("r")
            if (remoteId in known || remoteId in gone) continue
            records.put(
                RecordEntry(
                    // 不是这台手机的 MediaStore id：用负数，不会和本机照片混淆
                    mediaId = syntheticId(p, remoteId),
                    provider = p, modified = 0, size = o.optLong("s"), remoteId = remoteId, encrypted = o.optBoolean("e"),
                    name = o.optString("n"), album = o.optString("a"), mime = o.optString("m"), takenAt = o.optLong("t"),
                    hash = o.optString("h"), thumb = o.optString("th"),
                ),
            )
            changed = true
        }
        return changed
    }

    /**
     * 把备份目录传到网盘（换手机时靠它找回照片）。目录可能有几 MB，所以不是每轮都传：
     * 距上次超过 30 分钟、或者积累了 200 处改动、或者 [force]（加密开关、每次备份任务结束、离开 App 时）才传。
     * 传之前先读回网盘上的目录合并（别的手机新备份的不会被冲掉）；读不回来就不传，绝不覆盖。
     * [key] 用来读加密目录，开着加密（[want]）时也用它加密；开着加密却没有密钥时不传，不会退回传明文。
     */
    private suspend fun uploadIndex(connected: Set<CloudProvider>, want: Boolean, key: ByteArray?, force: Boolean = false): Set<CloudProvider> {
        if (!names.getBoolean(KEY_INDEX_DIRTY, true)) return emptySet()
        val since = System.currentTimeMillis() - names.getLong(KEY_INDEX_AT, 0)
        if (!force && since < INDEX_INTERVAL_MS && names.getInt(KEY_INDEX_PENDING, 0) < INDEX_BATCH) return emptySet()
        if (want && key == null) return emptySet()
        val version = names.getLong(KEY_INDEX_VERSION, 0)
        // 这次上传成功、而且网盘上已经没有加密目录（没有，或者合并后删掉了）的网盘
        val noEncrypted = HashSet<CloudProvider>()
        var allOk = true
        var merged = false
        for (p in connected) {
            if (!isReady(p)) {
                allOk = false
                continue
            }
            try {
                val drive = drive(p)
                val pulled = pullIndex(p, key)
                if (mergePulled(p, pulled)) merged = true
                val mine = records.entries().filter { it.provider == p }
                val arr = org.json.JSONArray()
                mine.distinctBy { it.remoteId }.forEach {
                    arr.put(
                        JSONObject().put("r", it.remoteId).put("e", it.encrypted).put("n", it.name).put("a", it.album)
                            .put("m", it.mime).put("t", it.takenAt).put("s", it.size).put("h", it.hash).put("th", it.thumb)
                            .put("d", if (it.mediaId > 0) deviceId else pulled.devices[it.remoteId].orEmpty()),
                    )
                }
                // 只带本机表里的（已经合并了读回来的、并按时间裁剪过）
                val del = org.json.JSONArray().apply { tombstones(p).forEach { put(it) } }
                // 设置也跟着账号走（不含密钥）。这台手机还没决定要不要恢复以前的设置时，先原样保留网盘上那份
                val settings = names.getString(KEY_REMOTE_SETTINGS, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: JSONObject().put("backup", settingsStore.encode(_settings.value)).also { st ->
                        runCatching { extras?.export() }.getOrNull()?.let { st.put("gallery", it) }
                    }
                val seq = maxOf(pulled.seq, names.getLong(KEY_INDEX_SEQ + p.name, 0)) + 1
                val plain = JSONObject().put("v", 2).put("seq", seq).put("items", arr).put("del", del).put("settings", settings).toString().toByteArray()
                // 开了端到端加密时目录也加密，网盘上看不出有哪些照片
                val encrypt = want && key != null
                val (path, data) = if (encrypt) INDEX_ENCRYPTED to Zylc.encrypt(plain, BlobKind.INDEX_SNAPSHOT, key!!) else INDEX_PLAIN to plain
                val newId = retrying { drive.upload(UploadFile(path, "application/octet-stream", data.size.toLong(), System.currentTimeMillis(), replace = true) { data.inputStream() }) }
                // 以前版本留下的改了名的旧目录（“index 1.zylc”）
                names.getString(KEY_INDEX_ID + p.name, null)?.takeIf { it != newId && it != pulled.encId && it != pulled.plainId }
                    ?.let { old -> runCatching { drive.delete(old) } }
                names.edit().putString(KEY_INDEX_ID + p.name, newId).putLong(KEY_INDEX_SEQ + p.name, seq).apply()
                // 另一种形式的旧目录：已经读到并合并进来了才删。加密目录只在本机拿着密钥、这个网盘也没有加密记录时才删
                if (encrypt) {
                    pulled.plainId?.takeIf { it != newId }?.let { id -> runCatching { drive.delete(id) } }
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
            }
        }
        if (merged) reloadRecords()
        // 上传期间又有改动（版本号变了）就不清“有改动”的标记，下次接着传
        if (allOk && names.getLong(KEY_INDEX_VERSION, 0) == version) {
            names.edit().putBoolean(KEY_INDEX_DIRTY, false).putInt(KEY_INDEX_PENDING, 0).putLong(KEY_INDEX_AT, System.currentTimeMillis()).apply()
        }
        return noEncrypted
    }

    /**
     * 第一次连上某个网盘账号时，从网盘里的目录把以前备份过的照片找回来。
     * 是否恢复过只看“已检查”标记；读失败就不设标记，下一轮再试（这期间也不会往这个网盘传东西）。
     */
    private suspend fun restoreIndex(p: CloudProvider, key: ByteArray?) {
        val flag = KEY_INDEX_CHECKED + p.name
        if (names.getBoolean(flag, false)) return
        // 加密目录还没解锁：不要每轮都重新下载，等解锁后再读
        if (key == null && names.getBoolean(KEY_INDEX_LOCKED + p.name, false)) return
        val pulled = pullIndex(p, key)
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
                        val opened = try {
                            cipher.inputStream().buffered().use { input -> plain.outputStream().buffered().use { Zylc.decryptStream(input, aiKey, it) } }
                            true
                        } catch (e: app.zyll.crypto.ZyllCryptoException) {
                            if (!names.contains(KEY_REMOTE_ABANDONED + p.name)) continue
                            false
                        }
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
            records.put(r.copy(mediaId = match.id, modified = if (r.hash.isEmpty() || verified) match.modifiedAt else 0L))
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
            _encryption.update { it.copy(enabled = false, waitingAccounts = emptyList()) }
        } else {
            _encryption.update { it.copy(waitingAccounts = tags.map { t -> tagLabel(t) }) }
        }
    }

    /**
     * 看看网盘里有没有以前的加密备份（只要有密钥包就算）。每个账号查一次；没查成功之前，这个网盘不传任何东西。
     * - 和本机是同一套密钥：没事；
     * - 本机没有密钥、或者是另一套：记下来，要用户解锁（或放弃）之后才继续。
     */
    private suspend fun checkRemoteVault(p: CloudProvider) {
        if (names.getBoolean(KEY_REMOTE_CHECKED + p.name, false)) return
        val drive = drive(p)
        val id = retrying { drive.findFile(KEYBUNDLE_PATH) }
        if (id != null) {
            val out = java.io.ByteArrayOutputStream()
            retrying { out.reset(); drive.download(id, out) }
            val text = String(out.toByteArray())
            val remoteId = runCatching { KeyBundle.parse(text).vaultId }.getOrNull()
            when {
                remoteId != null && remoteId == vault.vaultId -> addVaultAccount(p)
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

    private fun uploadFile(
        name: String, album: String, mime: String, size: Long, takenAt: Long,
        encrypt: Boolean, key: ByteArray?, open: () -> InputStream,
    ): UploadFile {
        if (!encrypt) {
            return UploadFile(
                path = listOf(album.ifBlank { "未命名" }.replace('/', '_'), name.ifBlank { "photo" }.replace('/', '_')),
                mimeType = mime.ifBlank { "application/octet-stream" },
                sizeBytes = size,
                modifiedAt = takenAt,
                open = open,
            )
        }
        val k = key ?: throw IOException(tr(R.string.e2ee_encryption_key_missing))
        // 加密后文件名是随机的，网盘上看不出是什么照片
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        val cipherLen = ZylcHeader(Zylc.DEFAULT_CHUNK_LOG2, BlobKind.ORIGINAL, ByteArray(16), ByteArray(12), ByteArray(48), size).totalLen
        return UploadFile(
            path = listOf(CloudConfig.ENCRYPTED_FOLDER, "$id.zylc"),
            mimeType = "application/octet-stream",
            sizeBytes = cipherLen,
            modifiedAt = takenAt,
            open = { ZylcEncryptingInputStream(open(), size, BlobKind.ORIGINAL, k) },
        )
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
    private suspend fun replaceRecord(e: RecordEntry) {
        val old = records.get(e.mediaId, e.provider)
        records.put(e)
        if (old == null || old.remoteId == e.remoteId) return
        if (old.modified == 0L) {
            records.put(old.copy(mediaId = syntheticId(old.provider, old.remoteId)))
        } else if (records.byRemote(old.provider, old.remoteId).isEmpty() && !records.isShared(old.provider, old.remoteId)) {
            // 别的手机也在用的（共享）不删：那可能是它唯一的备份
            discardRemote(old.provider, old.remoteId, old.thumb)
        }
    }

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
        runCatching { retrying { drive(p).delete(id) } }.onSuccess { records.dropDelete(p, id) }
        _encryption.update { it.copy(pendingCleanup = records.pendingDeleteCount()) }
    }

    private fun addTombstone(p: CloudProvider, remoteId: String) = records.addTombstones(p, listOf(remoteId), TOMBSTONE_MAX)

    /** 这台手机知道已经删掉的网盘文件（某个网盘）。 */
    private fun tombstones(p: CloudProvider): Set<String> = records.tombstones(p)

    /** 把待删除队列里的网盘文件删掉（每轮最多一批）。 */
    private suspend fun processDeletes(connected: Set<CloudProvider>) {
        for ((p, id) in records.pendingDeletes(DELETE_BATCH)) {
            if (p !in connected) continue
            runCatching { retrying { drive(p).delete(id) } }.onSuccess { records.dropDelete(p, id) }
        }
        _encryption.update { it.copy(pendingCleanup = records.pendingDeleteCount()) }
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
        private const val NEW_MEDIA = "zyll-backup-new-media"
        private const val KEY_VAULT_UPLOADED_PREFIX = "vaultUploaded-"
        private const val KEY_FORCED = "forcedBackup"
        private const val KEY_EXTRA_ACCOUNTS = "extraAccounts"
        private const val KEY_PICKER_DELETED = "pickerDeleted"
        private const val KEY_INDEX_DIRTY = "indexDirty"
        private const val KEY_INDEX_PENDING = "indexPending"
        private const val KEY_INDEX_AT = "indexUploadedAt"
        private const val INDEX_INTERVAL_MS = 30L * 60_000
        private const val INDEX_BATCH = 200
        private const val KEY_INDEX_ID = "indexId-"
        private const val KEY_INDEX_CHECKED = "indexChecked-"
        private val INDEX_PLAIN = listOf("index.json")
        private val INDEX_ENCRYPTED = listOf(CloudConfig.ENCRYPTED_FOLDER, "index.zylc")
        private val KEYBUNDLE_PATH = listOf(CloudConfig.ENCRYPTED_FOLDER, "keybundle.json")
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
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_ACCOUNT_ID = "accountId-"
        private const val KEY_ACCOUNT_LABEL = "accountLabel-"
        private const val KEY_INDEX_VERSION = "indexVersion"
        private const val KEY_INDEX_SEQ = "indexSeq-"
        private const val TOMBSTONE_MAX = 20_000
        private const val DELETE_BATCH = 200
        private const val VERIFY_BATCH = 100
        private const val BACKFILL_HASH = 300
        private const val BACKFILL_THUMB = 60
        private const val THUMB_PX = 384
        private val GPS_TAGS = listOf(
            androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE, androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE_REF,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE, androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE_REF,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE, androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
        )
        private const val ACCOUNT_TTL_MS = 30L * 60_000
        private const val VIEW_CACHE_BYTES = 1L shl 30

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
