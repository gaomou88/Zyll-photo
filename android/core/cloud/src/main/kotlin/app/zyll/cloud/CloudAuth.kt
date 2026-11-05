package app.zyll.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.zyll.backup.CloudProvider
import app.zyll.backup.DriveKind
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import app.zyll.media.tr
import app.zyll.core.cloud.R

/** 登录失效（用户撤销了授权、刷新令牌过期），需要重新连接。 */
class NotSignedInException(provider: CloudProvider) : IOException(tr(R.string.auth_sign_in_again, provider.label))

/**
 * 用 AppAuth（浏览器 Custom Tab + PKCE）登录网盘，令牌存在本机私有存储里。
 * 不用 Google Play 服务，GitHub 版和 Play 版共用。
 */
class CloudAuth(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cloud-auth", Context.MODE_PRIVATE)
    private val service by lazy { AuthorizationService(app) }
    // 令牌状态放在内存里，同一个网盘同时只做一次刷新：
    // 以前设置页、后台备份各自从磁盘读一份旧状态、同时去刷新令牌，后写的会覆盖先写的，偶尔把好的令牌冲掉
    private val cache = java.util.concurrent.ConcurrentHashMap<CloudProvider, AuthState>()
    private val locks = java.util.concurrent.ConcurrentHashMap<CloudProvider, Mutex>()

    fun isSignedIn(p: CloudProvider): Boolean = load(p)?.let { it.isAuthorized && hasScopes(p, it) } == true

    /** 登录过，但权限范围变了（比如改成了隐藏的应用数据区），需要重新连接一次。 */
    fun needsReconnect(p: CloudProvider): Boolean = load(p)?.let { it.isAuthorized && !hasScopes(p, it) } == true

    private fun hasScopes(p: CloudProvider, s: AuthState): Boolean {
        val granted = s.scope?.split(' ')?.toSet() ?: return true // 有的服务不回传 scope，当作满足
        // offline_access、openid 之类的不一定回传，只检查真正存文件的那个
        val needed = CloudConfig.of(p).scopes.first()
        return granted.any { it.equals(needed, ignoreCase = true) || it.endsWith("/" + needed.substringAfterLast('/'), ignoreCase = true) }
    }

    /** 打开登录页的 Intent，用 startActivityForResult 启动，结果交给 [complete]。 */
    fun signInIntent(p: CloudProvider): Intent {
        val c = CloudConfig.of(p)
        check(c.configured) { tr(R.string.cloud_not_supported, p.label) }
        val config = AuthorizationServiceConfiguration(Uri.parse(c.authEndpoint), Uri.parse(c.tokenEndpoint))
        val request = AuthorizationRequest.Builder(config, c.clientId, ResponseTypeValues.CODE, Uri.parse(c.redirectUri))
            .setScopes(c.scopes)
            .setPrompt(if (p.kind == DriveKind.GoogleDrive) "consent select_account" else "select_account")
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** 处理登录页返回的结果，换取令牌。 */
    suspend fun complete(p: CloudProvider, data: Intent?) {
        if (data == null) throw IOException(tr(R.string.auth_cancelled))
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        val state = AuthState(response, error)
        if (response == null) throw IOException(error?.errorDescription ?: error?.error ?: tr(R.string.auth_incomplete))
        suspendCancellableCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { token, ex ->
                state.update(token, ex)
                if (token != null) cont.resume(Unit) else cont.resumeWithException(IOException(ex?.errorDescription ?: tr(R.string.auth_token_failed)))
            }
        }
        save(p, state)
    }

    /** 拿一个有效的访问令牌，过期了会自动刷新。 */
    suspend fun accessToken(p: CloudProvider): String = locks.getOrPut(p) { Mutex() }.withLock {
        val state = load(p) ?: throw NotSignedInException(p)
        suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(service) { token, _, ex ->
                save(p, state)
                when {
                    token != null -> cont.resume(token)
                    ex?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR -> cont.resumeWithException(NotSignedInException(p))
                    else -> cont.resumeWithException(IOException(ex?.errorDescription ?: tr(R.string.auth_no_network)))
                }
            }
        }
    }

    /** 网盘接口返回 401：下次取令牌时强制刷新。 */
    fun invalidate(p: CloudProvider) {
        load(p)?.needsTokenRefresh = true
    }

    /** 当前保存的登录状态（重新连接前先存一份，失败时可以原样放回）。 */
    fun savedState(p: CloudProvider): String? = prefs.getString(p.name, null)

    /** 放回 [savedState] 存下的登录状态；为 null 时等于退出登录。 */
    fun restoreState(p: CloudProvider, json: String?) {
        cache.remove(p)
        if (json == null) prefs.edit().remove(p.name).apply() else prefs.edit().putString(p.name, json).apply()
    }

    fun signOut(p: CloudProvider) {
        cache.remove(p)
        prefs.edit().remove(p.name).apply()
    }

    private fun load(p: CloudProvider): AuthState? = cache[p]
        ?: prefs.getString(p.name, null)?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }?.also { cache[p] = it }

    private fun save(p: CloudProvider, state: AuthState) {
        cache[p] = state
        prefs.edit().putString(p.name, state.jsonSerializeString()).apply()
    }
}
