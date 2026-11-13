package app.zyll.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import app.zyll.ai.AiSearch
import app.zyll.feature.gallery.AiAccess
import app.zyll.feature.gallery.GalleryApp
import app.zyll.feature.gallery.GalleryController
import app.zyll.feature.gallery.GalleryHooks
import app.zyll.feature.gallery.SensitiveFilter
import app.zyll.feature.gallery.ui.GalleryImages
import app.zyll.feature.gallery.ui.ZyllTheme
import app.zyll.gallery.flavor.FlavorServices
import app.zyll.cloud.CloudBackup
import app.zyll.gallery.settings.BackupScreen
import app.zyll.gallery.settings.EncryptionScreen
import app.zyll.gallery.settings.MePage
import app.zyll.gallery.settings.SensitiveScreen
import app.zyll.gallery.settings.SettingsScreen
import app.zyll.gallery.settings.AboutScreen
import app.zyll.media.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/** 进程内共享的对象：媒体库和 AI 只各建一份，旋转屏幕、重进 Activity 都不重建。 */
object AppGraph {
    @Volatile private var controller: GalleryController? = null
    private var backup: CloudBackupIndicator? = null
    private var currentActivity = WeakReference<Activity>(null)

    /** 已经建好的就返回，没建好不建（系统照片选择器的调用线程上不能做这么重的事）。 */
    fun controllerIfBuilt(): GalleryController? = controller

    fun setActivity(a: Activity?) {
        currentActivity = WeakReference(a)
    }

    @Synchronized
    fun controller(context: Context): GalleryController = controller ?: run {
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ai = AiSearch(app, FlavorServices.modelSources(app) { currentActivity.get() }, scope)
        GalleryController(app, MediaRepository(app), ai, scope, SensitiveFilter(app, ai, scope)).also { c ->
            controller = c
            // 网盘：本机删掉的已备份照片继续显示在相册里
            val b = CloudBackupIndicator(CloudBackup.get(app), c, scope)
            backup = b
            GalleryImages.cloudFiles = b
            c.attachCloud(b.cloudItems)
            FlavorServices.attachPicker(app, c)
            // 所有界面（相册、编辑器、裁剪）都算在内：全部退到后台才算离开 App
            (app as android.app.Application).registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = AppGraph.onActivityStarted(app)
                override fun onActivityStopped(activity: Activity) = AppGraph.onActivityStopped(app)
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }

    private var visible = 0

    /** 有界面在前台 / 全部退到后台：后台时暂停建索引、停止监听媒体库，并补传备份目录。 */
    private var backgrounded = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun onActivityStarted(context: Context) {
        visible++
        if (backgrounded) {
            backgrounded = false
            controller(context).onAppForeground()
        }
    }

    fun onActivityStopped(context: Context) {
        visible = (visible - 1).coerceAtLeast(0)
        val app = context.applicationContext
        // 稍等一下再判断：旋转屏幕时旧界面停了、新界面马上就会起来，不算退到后台
        main.postDelayed({
            if (visible == 0 && !backgrounded) {
                backgrounded = true
                controller(app).onAppBackground()
                CloudBackup.get(app).flushIndex()
            }
        }, 1500)
    }

    fun backup(context: Context): CloudBackupIndicator {
        controller(context)
        return backup!!
    }
}

class MainActivity : ComponentActivity() {
    private var mePage by mutableStateOf<MePage?>(null)
    /** 加密页是从备份页点进来的：返回时回到备份页，不是“我的”首页。 */
    private var encryptionFromBackup = false
    private val openMe = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val openCloudOnly = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val openUri = MutableSharedFlow<android.net.Uri>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var openedForView = false

    private fun handleView(intent: Intent?): Boolean {
        val uri = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || uri == null) return false
        // 别的 App 让我们“打开”一个地址：不能借这个读到 App 自己的私有文件（缓存里解密后的网盘照片、模型、密钥等）
        if (!safeToView(uri)) {
            // 不说一声的话，用户只看到相册首页，不知道文件为什么没打开
            android.widget.Toast.makeText(this, app.zyll.media.tr(R.string.view_unsupported_file), android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        openedForView = true
        openUri.tryEmit(uri)
        return true
    }

    private fun safeToView(uri: android.net.Uri): Boolean = when (uri.scheme?.lowercase()) {
        "content" -> {
            // 自己的 FileProvider / 内容提供者指向的是私有目录，外面传进来的一律不认。
            // 按 host 比（authority 可以带“0@”这种用户前缀绕过前缀比较），host 为空的也不认
            val host = uri.host?.lowercase()
            host != null && host != packageName.lowercase() && !host.startsWith(packageName.lowercase() + ".")
        }
        "file" -> {
            val path = runCatching { java.io.File(uri.path ?: "").canonicalPath }.getOrNull()
            val privateDirs = listOfNotNull(dataDir, filesDir, cacheDir, codeCacheDir, noBackupFilesDir, externalCacheDir, getExternalFilesDir(null))
                .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            path != null && privateDirs.none { path == it || path.startsWith("$it/") }
        }
        else -> false
    }

    private var entitlements: Entitlements? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        lastNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        super.onCreate(savedInstanceState)
        // 系统回收后重建：回到原来的设置子页面
        savedInstanceState?.getString(STATE_ME_PAGE)?.let { name -> mePage = MePage.entries.firstOrNull { it.name == name } }
        encryptionFromBackup = savedInstanceState?.getBoolean(STATE_ENC_FROM_BACKUP) ?: false
        GalleryImages.install(this)
        val entitlements = FlavorServices.entitlements(applicationContext).also { this.entitlements = it }
        val controller = AppGraph.controller(this)
        val cloud = CloudBackup.get(this)
        // Pro 决定：能登录几个网盘账号（Play 免费版各一个、Pro 多个，GitHub 版一共一个）、能不能用端侧 AI
        val aiLocked = MutableStateFlow(FlavorServices.HAS_AI && PRO_LOCK_ENABLED && !entitlements.proUnlocked.value)
        lifecycleScope.launch {
            entitlements.proUnlocked.collect {
                val pro = !PRO_LOCK_ENABLED || it
                cloud.limits = FlavorServices.accountLimits(pro)
                aiLocked.value = FlavorServices.HAS_AI && !pro
                controller.ai.setAllowed(FlavorServices.HAS_AI && pro)
                // 系统照片选择器里的网盘照片也是 Pro 功能
                cloud.pickerLocked = !pro
            }
        }
        // 同意使用条款之前不在后台处理数据：定时备份、补跑一轮都等同意以后（见 LegalConsentGate 的 onAccepted）
        if (app.zyll.gallery.settings.hasLegalConsent(this)) {
            cloud.schedule()
            // 打开 App 时补跑一轮；之后的新照片由系统媒体库变化触发，合并一段时间再备份，省电
            if (savedInstanceState == null && cloud.settings.value.enabled) cloud.backupNow()
        } else {
            cloud.stopBackgroundWork()
        }
        handleView(intent)
        // 重建时 intent 还是原来那个，设置子页面已经从保存的状态恢复：不再重新打开
        if (savedInstanceState == null) openPageFor(intent)

        val hooks = GalleryHooks(
            // 修图是 Play 版功能，免费用户也能用（Pro 只解锁多账号、端侧 AI 和接入云端媒体）
            onEdit = if (entitlements.hasEditor) ({ item ->
                FlavorServices.editorIntent(this, item.uri)?.let(::startActivity)
            }) else null,
            settings = { padding ->
                AnimatedContent(
                    targetState = mePage,
                    transitionSpec = {
                        val forward = targetState != null
                        val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
                        (slideInHorizontally(spec) { if (forward) it / 3 else -it / 8 } + fadeIn(tween(260))) togetherWith
                            (slideOutHorizontally(spec) { if (forward) -it / 8 else it / 3 } + fadeOut(tween(160)))
                    },
                    label = "mePage",
                ) { p ->
                    when (p) {
                        null -> SettingsScreen(controller.ai, controller.repo, cloud, controller.sensitive, controller, entitlements, padding, onOpen = { mePage = it })
                        MePage.CloudMedia -> {
                            BackHandler { mePage = null }
                            FlavorServices.CloudMedia(entitlements, onClose = { mePage = null })
                        }
                        MePage.Backup -> {
                            BackHandler { mePage = null }
                            val library by controller.library.collectAsState()
                            BackupScreen(cloud, library.albums, padding, onBack = { mePage = null }, onOpenEncryption = {
                                encryptionFromBackup = true
                                mePage = MePage.Encryption
                            }, onOpenCloudOnly = {
                                mePage = null
                                openCloudOnly.tryEmit(Unit)
                            })
                        }
                        MePage.Encryption -> {
                            val back = {
                                mePage = if (encryptionFromBackup) MePage.Backup else null
                                encryptionFromBackup = false
                            }
                            BackHandler { back() }
                            EncryptionScreen(cloud, padding, onBack = back)
                        }
                        MePage.Sensitive -> {
                            BackHandler { mePage = null }
                            SensitiveScreen(
                                controller.sensitive, controller.ai, padding, onBack = { mePage = null },
                                pickerSetting = { FlavorServices.PickerBlurSetting(controller.sensitive, entitlements, onOpenCloudMedia = { mePage = MePage.CloudMedia }) },
                                onUnlock = { if (!entitlements.purchase(this@MainActivity)) Toast.makeText(this@MainActivity, getString(R.string.pro_unavailable), Toast.LENGTH_LONG).show() },
                            )
                        }
                        MePage.Shared -> {
                            BackHandler { mePage = null }
                            app.zyll.gallery.settings.SharedAlbumsScreen(cloud, padding, onBack = { mePage = null }, onOpenBackup = { mePage = MePage.Backup })
                        }
                        MePage.About -> {
                            BackHandler { mePage = null }
                            AboutScreen(entitlements, padding, onBack = { mePage = null })
                        }
                    }
                }
            },
            backup = AppGraph.backup(this),
            onOpenBackup = { mePage = MePage.Backup },
            onOpenShared = { mePage = MePage.Shared },
            // 只用一次：处理完就清掉，界面重建时不会又跳到“我的”
            openMe = openMe.onEach { openMe.resetReplayCache() },
            openCloudOnly = openCloudOnly,
            meSubpage = androidx.compose.runtime.derivedStateOf { mePage != null },
            openUri = openUri,
            onExternalDone = { if (openedForView) finish() },
            ai = if (FlavorServices.HAS_AI) {
                AiAccess(
                    locked = aiLocked,
                    onUnlock = {
                        if (!entitlements.purchase(this)) Toast.makeText(this, getString(R.string.pro_unavailable), Toast.LENGTH_LONG).show()
                    },
                )
            } else {
                null
            },
        )

        setContent {
            ZyllTheme {
                Surface(Modifier.fillMaxSize()) {
                    // 第一次打开先同意使用条款和隐私政策；不同意就关掉
                    app.zyll.gallery.settings.LegalConsentGate(
                        onDecline = { cloud.stopBackgroundWork(); finishAndRemoveTask() },
                        onAccepted = {
                            cloud.schedule()
                            if (cloud.settings.value.enabled) cloud.backupNow()
                        },
                    ) {
                        GalleryApp(controller, hooks)
                        app.zyll.gallery.settings.PendingRecoveryKey(cloud)
                        // 删除全部备份数据在后台做完时，不管在哪个页面都提示一次
                        app.zyll.gallery.settings.WipeFinishedNotice(cloud)
                        // 开着分享功能的 Google 账号还没授权读写可见文件夹：哪个页面都强制弹出，必须授权或断开
                        app.zyll.gallery.settings.SharingScopeGate(cloud)
                    }
                }
            }
        }
    }

    private var lastNightMode = 0

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 深色模式切换不再重建界面：系统栏图标颜色要跟着重新设一次。只在深浅色真的变了时设，转屏时不动（大图页自己管图标颜色）
        val night = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (night != lastNightMode) {
            lastNightMode = night
            enableEdgeToEdge()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mePage?.let { outState.putString(STATE_ME_PAGE, it.name) }
        outState.putBoolean(STATE_ENC_FROM_BACKUP, encryptionFromBackup)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleView(intent)
        openPageFor(intent)
    }

    /** 从系统照片选择器点进来：打开网盘设置，或（没解锁时）“接入云端媒体”页。 */
    private fun openPageFor(intent: Intent?) {
        val page = when (intent?.action) {
            ACTION_OPEN_BACKUP -> MePage.Backup
            ACTION_OPEN_CLOUD_MEDIA -> if (FlavorServices.HAS_CLOUD_MEDIA) MePage.CloudMedia else MePage.Backup
            else -> return
        }
        mePage = page
        openMe.tryEmit(Unit)
    }

    override fun onResume() {
        super.onResume()
        AppGraph.setActivity(this)
        entitlements?.refresh()
    }

    override fun onPause() {
        AppGraph.setActivity(null)
        super.onPause()
    }

    companion object {
        const val ACTION_OPEN_BACKUP = "com.zyll.photo.OPEN_BACKUP"
        const val ACTION_OPEN_CLOUD_MEDIA = "com.zyll.photo.OPEN_CLOUD_MEDIA"
        private const val STATE_ME_PAGE = "mePage"
        private const val STATE_ENC_FROM_BACKUP = "encFromBackup"
    }
}
