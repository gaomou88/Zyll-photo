package app.zyll.gallery

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** Pro 锁：true 时多账号、端侧 AI 和接入云端媒体需要购买 pro_unlock。改成 false 可在测试时临时全部开放。 */
const val PRO_LOCK_ENABLED = true

/**
 * 付费功能的开关。main 里只依赖这个接口：
 * github 源集提供“永远免费、无升级入口”的实现，play 源集接入 Play Billing。
 * 业务代码里不出现 `if (BuildConfig.FLAVOR == ...)`。
 *
 * Play 版免费用户与 Pro 的区别：Pro 才能登录多个网盘账号、使用端侧 AI 模型、接入云端媒体。
 */
interface Entitlements {
    /** 是否已解锁 Pro。 */
    val proUnlocked: StateFlow<Boolean>

    /** 有一笔还没付款完成的购买（例如便利店付款）。 */
    val purchasePending: StateFlow<Boolean>

    /** 本地化价格，拿不到时为 null。 */
    val price: StateFlow<String?>

    /** 该发行版是否有付费升级入口（GitHub 版为 false）。 */
    val hasUpgradePath: Boolean

    /** 该发行版是否包含修图功能。 */
    val hasEditor: Boolean

    /** 打开购买界面；返回 false 表示暂时无法购买（没网或商品未上架）。 */
    fun purchase(activity: Activity): Boolean

    /** 恢复购买：重新向商店查询当前商店账号的购买记录。 */
    suspend fun restore(): RestoreOutcome

    /** 回到 App 时在后台重新查一次购买记录（退款、待付款完成不用重启 App 就能发现）。 */
    fun refresh() {}
}

enum class RestoreOutcome { Restored, Pending, NothingFound, Failed }

/** 当前是否可以使用 Pro 功能（开发阶段 [PRO_LOCK_ENABLED] 为 false 时永远可以）。 */
val Entitlements.proActive: Boolean get() = !PRO_LOCK_ENABLED || proUnlocked.value
