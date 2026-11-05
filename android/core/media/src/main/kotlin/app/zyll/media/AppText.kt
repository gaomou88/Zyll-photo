package app.zyll.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.annotation.StringRes

/**
 * 读界面文字。App 里的文字放在各模块的 res/values*（英文为默认，另有简中、繁中、日文），
 * 界面代码、后台任务和通知都通过 [tr] 取，不用分开处理 Composable 和普通代码。
 *
 * 切换语言时系统会重建 Activity，所以每次取到的都是当前语言。
 */
object AppText {
    @Volatile
    private var app: Context? = null

    val context: Context
        get() = checkNotNull(app) { "AppText is not initialized" }

    fun init(context: Context) {
        app = context.applicationContext ?: context
    }
}

fun tr(@StringRes id: Int): String = AppText.context.getString(id)

fun tr(@StringRes id: Int, vararg args: Any?): String = AppText.context.getString(id, *args)

/** 进程启动时最早运行，先于 Application、Activity 和后台任务，让 [AppText] 随时可用。 */
class AppTextInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(AppText::init)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
