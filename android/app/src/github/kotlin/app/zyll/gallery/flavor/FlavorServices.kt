package app.zyll.gallery.flavor

import app.zyll.gallery.R
import app.zyll.media.tr
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.zyll.backup.AccountLimits
import androidx.compose.runtime.Composable
import app.zyll.ai.ModelSource
import app.zyll.gallery.Entitlements
import app.zyll.gallery.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** GitHub 版：纯开源免费，无内购、无修图、无云端媒体接入。 */
object FlavorServices {
    val DISTRIBUTION: String get() = tr(R.string.distribution_github)

    const val HAS_CLOUD_MEDIA = false

    /** GitHub 版一共只能登录一个网盘账号。 */
    /** 开源版没有任何 AI 功能。 */
    const val HAS_AI = false

    fun accountLimits(@Suppress("UNUSED_PARAMETER") pro: Boolean): AccountLimits = AccountLimits.SINGLE

    fun entitlements(@Suppress("UNUSED_PARAMETER") context: Context): Entitlements = object : Entitlements {
        override val proUnlocked: StateFlow<Boolean> = MutableStateFlow(false)
        override val purchasePending: StateFlow<Boolean> = MutableStateFlow(false)
        override val price: StateFlow<String?> = MutableStateFlow(null)
        override val hasUpgradePath = false
        override val hasEditor = false
        override fun purchase(activity: Activity) = false
        override suspend fun restore() = RestoreOutcome.NothingFound
    }

    fun cloudMediaActive(@Suppress("UNUSED_PARAMETER") context: Context) = false

    @Composable
    fun CloudMedia(@Suppress("UNUSED_PARAMETER") entitlements: Entitlements, @Suppress("UNUSED_PARAMETER") onClose: () -> Unit) = Unit

    /** 开源版没有 AI：不提供任何模型来源。 */
    fun modelSources(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") activity: () -> Activity?): List<ModelSource> =
        emptyList()

    /** GitHub 版不含编辑器。 */
    fun editorIntent(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") uri: Uri): Intent? = null
}
