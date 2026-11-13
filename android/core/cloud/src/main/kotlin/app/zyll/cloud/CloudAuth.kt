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

    /**
     * [exchange] 换来、还没保存的授权里有没有主权限。服务没回传 scope 时当作有（和 [hasScopes] 一样）。
     * Google 授权页可以单独取消某一项：取消了主权限的令牌不能存，否则好好的账号会变成“需要重新连接”。
     */
    fun pendingHasMainScope(p: CloudProvider, s: AuthState): Boolean = hasScopes(p, s)

    /** [exchange] 换来的授权里有没有某个额外权限（比如 drive.file）。没回传 scope 时当作没有。 */
    fun pendingHasScope(s: AuthState, scope: String): Boolean =
        s.scope?.split(' ')?.any { it.equals(scope, ignoreCase = true) } == true

    /** 是否授权了某个额外的权限（比如分享功能要的 drive.file）。服务没回传 scope 时当作没有，保险起见让用户重新连接一次。 */
    fun hasScope(p: CloudProvider, scope: String): Boolean {
        val granted = load(p)?.takeIf { it.isAuthorized }?.scope?.split(' ') ?: return false
        return granted.any { it.equals(scope, ignoreCase = true) }
    }

    private fun hasScopes(p: CloudProvider, s: AuthState): Boolean {
        val granted = s.scope?.split(' ')?.toSet() ?: return true // 有的服务不回传 scope，当作满足
        // offline_access、openid 之类的不一定回传，只检查真正存文件的那个
        val needed = CloudConfig.of(p).scopes.first()
        return granted.any { it.equals(needed, ignoreCase = true) || it.endsWith("/" + needed.substringAfterLast('/'), ignoreCase = true) }
    }

    /** 打开登录页的 Intent，用 startActivityForResult 启动，结果交给 [complete]。 */
    fun signInIntent(p: CloudProvider, extraScopes: List<String> = emptyList(), loginHint: String? = null): Intent {
        val c = CloudConfig.of(p)
        check(c.configured) { tr(R.string.cloud_not_supported, p.label) }
        val config = AuthorizationServiceConfiguration(Uri.parse(c.authEndpoint), Uri.parse(c.tokenEndpoint))
        val request = AuthorizationRequest.Builder(config, c.clientId, ResponseTypeValues.CODE, Uri.parse(c.redirectUri))
            .setScopes((c.scopes + extraScopes).distinct())
            .setPrompt(if (p.kind == DriveKind.GoogleDrive) "consent select_account" else "select_account")
            // 再授权、重新连接时把原来的账号告诉登录页，预先选中它，少选错
            .apply { if (!loginHint.isNullOrBlank()) setLoginHint(loginHint) }
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /**
     * 处理登录页返回的结果，换取令牌，但先不保存：调用方用 [AuthState.getAccessToken] 确认登的是哪个账号，
     * 对了再 [commit]。这样核对期间别的任务拿到的还是原来那个账号的令牌。
     */
    suspend fun exchange(p: CloudProvider, data: Intent?): AuthState {
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
        return state
    }

    /** 保存 [exchange] 换来、核对过的登录状态。 */
    fun commit(p: CloudProvider, state: AuthState) = save(p, state)

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
        ?: prefs.getString(p.name, null)?.let { stored ->
            val json = TokenSeal.open(stored) ?: return@let null
            runCatching { AuthState.jsonDeserialize(json) }.getOrNull()?.also { st ->
                // 旧版本明文存的：读到后马上换成加密存
                if (!TokenSeal.isSealed(stored)) prefs.edit().putString(p.name, TokenSeal.seal(json)).apply()
            }
        }?.also { cache[p] = it }

    private fun save(p: CloudProvider, state: AuthState) {
        cache[p] = state
        prefs.edit().putString(p.name, TokenSeal.seal(state.jsonSerializeString())).apply()
    }
}

/**
 * 登录状态（含刷新令牌）用 Android Keystore 里不可导出的硬件密钥加密后再存，root 后翻配置文件也拿不到明文令牌。
 * 硬件密钥失效时解不开，等于没登录（界面上要重新连接），不影响备份记录。
 */
internal object TokenSeal {
    private const val ALIAS = "zyll-cloud-auth"
    private const val PREFIX = "k1:"

    fun isSealed(stored: String) = stored.startsWith(PREFIX)

    fun seal(json: String): String = runCatching {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        PREFIX + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }.getOrElse { json } // 极少数机型 Keystore 用不了：退回原来的明文存法，不让登录失败

    /** 解开存着的状态；旧版本的明文原样返回；解不开返回 null。 */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        return runCatching {
            val raw = android.util.Base64.decode(stored.removePrefix(PREFIX), android.util.Base64.NO_WRAP)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
