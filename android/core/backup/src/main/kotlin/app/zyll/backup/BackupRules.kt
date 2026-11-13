package app.zyll.backup

/** 支持的网盘类型。 */
enum class DriveKind(val label: String) {
    GoogleDrive("Google Drive"),
    OneDrive("OneDrive"),
}

/**
 * 一个网盘账号（槽位）：网盘类型 + 序号。每种网盘可以登录多个账号。
 *
 * 序号 0 的 [name] 和 [ordinal] 与旧版枚举完全一样（"GoogleDrive" = 0、"OneDrive" = 1），
 * 本地存的记录、设置和网盘里的数据都按 [name] 记，所以升级后不用迁移。
 * 额外账号的 [name] 是 "GoogleDrive2"、"OneDrive3" 这样，只含字母和数字，可以放进文件名和 URI。
 */
class CloudProvider private constructor(val kind: DriveKind, val slot: Int) : Comparable<CloudProvider> {
    val name: String = if (slot == 0) kind.name else kind.name + (slot + 1)
    val label: String = if (slot == 0) kind.label else "${kind.label} ${slot + 1}"
    /** 稳定的编号：序号 0 的两个网盘是 0 和 1，和旧版枚举一致。 */
    val ordinal: Int = slot * KINDS + kind.ordinal

    override fun equals(other: Any?) = other is CloudProvider && other.kind == kind && other.slot == slot
    override fun hashCode() = ordinal
    override fun toString() = name
    override fun compareTo(other: CloudProvider) = ordinal.compareTo(other.ordinal)

    companion object {
        private val KINDS = DriveKind.entries.size
        /** 每种网盘最多的账号数（防止写坏的数据造出无数个槽位）。 */
        const val MAX_SLOTS = 10

        private val pool = java.util.concurrent.ConcurrentHashMap<Int, CloudProvider>()

        fun of(kind: DriveKind, slot: Int): CloudProvider {
            require(slot in 0 until MAX_SLOTS) { "slot out of range: $slot" }
            return pool.getOrPut(slot * KINDS + kind.ordinal) { CloudProvider(kind, slot) }
        }

        val GoogleDrive: CloudProvider = of(DriveKind.GoogleDrive, 0)
        val OneDrive: CloudProvider = of(DriveKind.OneDrive, 0)

        @Volatile private var extra: Set<CloudProvider> = emptySet()

        /** 当前所有账号槽位：两个默认槽位 + 已添加的额外账号，按 [ordinal] 排序。 */
        val entries: List<CloudProvider> get() = (listOf(GoogleDrive, OneDrive) + extra).sorted()

        /** 设置额外账号（App 启动时从本地存储读出来）。序号 0 的槽位会被忽略。 */
        @Synchronized fun setExtra(list: Collection<CloudProvider>) { extra = list.filter { it.slot > 0 }.toSet() }

        @Synchronized fun register(p: CloudProvider) { if (p.slot > 0) extra = extra + p }

        @Synchronized fun unregister(p: CloudProvider) { extra = extra - p }

        /** 按 [name] 找回槽位；不在 [entries] 里也能找回（读旧记录用）。 */
        fun fromName(name: String): CloudProvider? {
            val kind = DriveKind.entries.firstOrNull { name.startsWith(it.name) } ?: return null
            val rest = name.removePrefix(kind.name)
            if (rest.isEmpty()) return of(kind, 0)
            val n = rest.toIntOrNull() ?: return null
            return if (n in 2..MAX_SLOTS) of(kind, n - 1) else null
        }

        fun fromOrdinal(ordinal: Int): CloudProvider? {
            if (ordinal < 0) return null
            val slot = ordinal / KINDS
            return if (slot < MAX_SLOTS) of(DriveKind.entries[ordinal % KINDS], slot) else null
        }

        /** 这种网盘下一个空着的槽位；满了返回 null。 */
        fun nextFree(kind: DriveKind): CloudProvider? {
            val used = entries.filter { it.kind == kind }.map { it.slot }.toSet()
            return (0 until MAX_SLOTS).firstOrNull { it !in used }?.let { of(kind, it) }
        }
    }
}

/**
 * 能登录几个网盘账号。
 * - Play 免费版：Google 和微软各一个（[perKind] = 1，[total] = 2）
 * - Play Pro：Google Drive、OneDrive 各最多 4 个
 * - GitHub 开源版：一共只能一个（[total] = 1）
 */
data class AccountLimits(val perKind: Int, val total: Int) {
    /** 已登录 [signedIn] 时，还能不能再登录一个 [kind] 的账号。 */
    fun canAdd(kind: DriveKind, signedIn: Collection<CloudProvider>): Boolean =
        signedIn.size < total && signedIn.count { it.kind == kind } < perKind

    /**
     * 已登录的账号里哪些在限额以内（可以备份）。按 [order] 先后算（默认按槽位；App 传用户排的顺序，
     * 排在前面的先保留），超出的（比如 Pro 退款后多出来的账号）保留登录但暂停备份。
     */
    fun allowed(signedIn: Collection<CloudProvider>, order: List<CloudProvider> = signedIn.sorted()): Set<CloudProvider> {
        val set = signedIn.toSet()
        val kept = ArrayList<CloudProvider>()
        for (p in order.filter { it in set } + signedIn.sorted().filter { it !in order }) if (p !in kept && canAdd(p.kind, kept)) kept += p
        return kept.toSet()
    }

    companion object {
        val FREE = AccountLimits(perKind = 1, total = 2)
        val PRO = AccountLimits(perKind = 4, total = 8)
        val SINGLE = AccountLimits(perKind = 1, total = 1)
    }
}

/**
 * 没有单独指定的相簿怎么备份。不管哪种方式，同一张照片在同一种网盘里只放一份
 * （比如登录了 3 个 Google Drive，照片只进其中一个）。
 */
enum class BackupMode {
    /** 每种网盘各放一份：Google Drive 一份、OneDrive 一份，各自放进排序最靠前、还有空间的那个账号。 */
    Mirror,

    /** 一共只放一份：放进排序第一个还有空间的账号，满了顺延到下一个。 */
    Overflow,
}

/** 某个相簿的单独规则。 */
sealed interface AlbumRule {
    /** 这个相簿不备份。 */
    data object Skip : AlbumRule

    /**
     * 这个相簿每种网盘各放一份（和 [BackupMode.Mirror] 一样，按用户排序选账号）。
     * 不存具体账号，以后加了新账号也跟着生效。
     */
    data object EachKind : AlbumRule

    /**
     * 这个相簿只备份到这些网盘（满了也不换别的盘）。每种网盘只该有一个账号；
     * 旧数据里同一种网盘列了几个时，按排序只用第一个放得下的。
     */
    data class To(val providers: Set<CloudProvider>) : AlbumRule
}

data class BackupSettings(
    val enabled: Boolean = false,
    val mode: BackupMode = BackupMode.Overflow,
    /**
     * 用户给已连接网盘排的顺序（第一个就是首选）。没列出的账号排在后面，按槽位先后。
     * 备份时总是先用排在前面、还有空间的账号。
     */
    val order: List<CloudProvider> = emptyList(),
    /** 按相簿（bucketId）单独指定；没列出的相簿按 [mode]。 */
    val albumRules: Map<Long, AlbumRule> = emptyMap(),
    val includeVideos: Boolean = true,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    /** 以后新出现的相簿是否自动备份。关掉时新相簿会被设成“不备份”。 */
    val backupNewAlbums: Boolean = true,
    /** 已经见过的相簿（用来判断哪些是新相簿）。 */
    val knownAlbums: Set<Long> = emptySet(),
) {
    /** 首选网盘：排序里的第一个。 */
    val primary: CloudProvider get() = order.firstOrNull() ?: CloudProvider.GoogleDrive

    /** 把 [all] 按用户的排序排好（没排过的放在后面，按槽位先后）。 */
    fun ranked(all: Collection<CloudProvider>): List<CloudProvider> {
        val set = all.toSet()
        return order.filter { it in set }.distinct() + set.filter { it !in order }.sorted()
    }
}

/** 一个网盘此刻的状态。[freeBytes] 为 null 表示查不到剩余空间（当作够用）。 */
data class DriveStatus(val provider: CloudProvider, val connected: Boolean, val freeBytes: Long?)

/** 等待备份的一项。 */
data class BackupCandidate(val mediaId: Long, val bucketId: Long, val sizeBytes: Long, val isVideo: Boolean)

/** 一项该去哪儿。 */
sealed interface Route {
    /**
     * 上传到这些盘（可能为多个）。[full]：按规则也该放一份、但已经满了的盘（比如“每种网盘各一份”时其中一种满了），
     * 界面上要提示“XX 已满”，不能悄悄少放一份。
     */
    data class Upload(val providers: List<CloudProvider>, val full: List<CloudProvider> = emptyList()) : Route

    /** 按规则不需要备份。 */
    data object Skipped : Route

    /** 想备份，但目标盘都没连接。 */
    data object NoDrive : Route

    /** 想备份，但目标盘都满了。 */
    data class Full(val providers: List<CloudProvider>) : Route
}

/**
 * 决定每张照片备份到哪里。会在内部记账：分配出去的大小从剩余空间里扣掉，
 * 这样一批照片里后面的照片能正确地“溢出”到下一个账号。
 *
 * 同一张照片在同一种网盘里最多一份：已经在某个 Google Drive 账号里有了，就不会再传到另一个 Google Drive 账号。
 */
class BackupRouter(private val settings: BackupSettings, drives: List<DriveStatus>) {
    private val connected = drives.filter { it.connected }.map { it.provider }.toSet()
    private val free = drives.associate { it.provider to it.freeBytes }.toMutableMap()
    /** 按用户排序的全部账号（含没连上的）。 */
    private val ranked = settings.ranked(drives.map { it.provider })

    /** 每个盘留一点余量，不把用户的网盘塞到 100%。 */
    var reserveBytes: Long = 50L * 1024 * 1024

    /** [alreadyOn]：这张照片已经在哪些盘上有备份。 */
    fun route(item: BackupCandidate, alreadyOn: Set<CloudProvider> = emptySet()): Route {
        if (item.isVideo && !settings.includeVideos) return Route.Skipped
        return when (val rule = settings.albumRules[item.bucketId]) {
            AlbumRule.Skip -> Route.Skipped
            AlbumRule.EachKind -> perKind(item, alreadyOn, DriveKind.entries) { kind -> ranked.filter { it.kind == kind } }
            is AlbumRule.To -> {
                if (rule.providers.isEmpty()) return Route.Skipped
                perKind(item, alreadyOn, rule.providers.map { it.kind }.distinct()) { kind -> ranked.filter { it.kind == kind && it in rule.providers } + rule.providers.filter { it.kind == kind && it !in ranked }.sorted() }
            }
            null -> when (settings.mode) {
                BackupMode.Mirror -> perKind(item, alreadyOn, DriveKind.entries) { kind -> ranked.filter { it.kind == kind } }
                BackupMode.Overflow -> {
                    // 已经在任意一个盘上有了就算备份完成
                    if (alreadyOn.isNotEmpty()) Route.Upload(emptyList()) else single(item)
                }
            }
        }
    }

    /** 每种网盘各放一份：这种网盘里已经有了就跳过，没有就放进排在最前、放得下的那个账号。 */
    private fun perKind(item: BackupCandidate, alreadyOn: Set<CloudProvider>, kinds: List<DriveKind>, pool: (DriveKind) -> List<CloudProvider>): Route {
        val picks = ArrayList<CloudProvider>()
        val full = ArrayList<CloudProvider>()
        var satisfied = false
        var anyAccount = false
        for (kind in kinds) {
            val accounts = pool(kind)
            if (accounts.isEmpty()) continue
            anyAccount = true
            if (alreadyOn.any { it.kind == kind }) {
                satisfied = true
                continue
            }
            val online = accounts.filter { it in connected }
            if (online.isEmpty()) continue
            val target = online.firstOrNull { fits(it, item.sizeBytes) }
            if (target == null) full += online else picks += target
        }
        if (picks.isNotEmpty()) {
            picks.forEach { take(it, item.sizeBytes) }
            return Route.Upload(picks, full)
        }
        if (full.isNotEmpty()) return Route.Full(full)
        if (satisfied) return Route.Upload(emptyList())
        return if (anyAccount) Route.NoDrive else Route.Skipped
    }

    /** 一共只放一份：排在最前、放得下的那个账号。 */
    private fun single(item: BackupCandidate): Route {
        val candidates = ranked.filter { it in connected }
        if (candidates.isEmpty()) return Route.NoDrive
        val target = candidates.firstOrNull { fits(it, item.sizeBytes) } ?: return Route.Full(candidates)
        take(target, item.sizeBytes)
        return Route.Upload(listOf(target))
    }

    /** 上传时网盘说空间满了（剩余空间查不到的账号也会这样）：这一轮后面的照片不再往它放。 */
    fun markFull(p: CloudProvider) {
        free[p] = 0L
    }

    private fun fits(p: CloudProvider, size: Long): Boolean {
        val f = free[p] ?: return true
        return f - size >= reserveBytes
    }

    private fun take(p: CloudProvider, size: Long) {
        free[p]?.let { free[p] = it - size }
    }
}
