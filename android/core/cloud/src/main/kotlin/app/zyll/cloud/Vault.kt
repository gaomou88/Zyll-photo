package app.zyll.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.zyll.crypto.KeyBundle
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import app.zyll.media.tr
import app.zyll.core.cloud.R

/**
 * 端到端加密的密钥。
 *
 * - 密钥包（用解锁密码和恢复密钥各包一份主密钥）存在本机，也会上传到网盘，换手机时用密码或恢复密钥解开；
 * - 为了后台备份时不用每次输密码，主密钥的“包裹子密钥”再用 Android Keystore 里不可导出的硬件密钥加密后存在本机。
 * 网盘服务商只看得到密文。
 */
internal class Vault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud-vault", Context.MODE_PRIVATE)

    /** 用户要求的状态：true = 全部加密，false = 全部不加密。 */
    var wantEncrypted: Boolean
        get() = prefs.getBoolean(KEY_WANT, false)
        set(v) {
            prefs.edit().putBoolean(KEY_WANT, v).commit()
        }

    val bundleJson: String? get() = prefs.getString(KEY_BUNDLE, null)

    /** 本机密钥包的 vaultId（区分不同的两套密钥）。 */
    val vaultId: String? get() = bundleJson?.let { runCatching { KeyBundle.parse(it).vaultId }.getOrNull() }

    /**
     * 本机保存的包裹密钥；没有开启过加密时为 null。
     * 硬件密钥失效（比如系统清掉了锁屏凭据）时抛 [VaultBrokenException]，这时可以用密码重新解锁。
     */
    fun wrapKey(): ByteArray? = unseal(prefs.getString(KEY_WRAP, null))

    private fun unseal(stored: String?): ByteArray? {
        if (stored == null) return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, raw, 0, 12))
            cipher.doFinal(raw, 12, raw.size - 12)
        } catch (e: Exception) {
            throw VaultBrokenException(e)
        }
    }

    /** 新建密钥包并开启加密，返回要让用户抄下来的恢复密钥。 */
    fun create(password: String): String {
        val created = KeyBundle.create(password)
        save(created.json, created.vault.wrapKey, want = true)
        return created.recoveryKeyDisplay
    }

    /** 采用一个已经解开的密钥包（换手机后解锁网盘里的、或者本机硬件密钥失效后用密码修复），并开启加密。 */
    fun adopt(bundle: String, wrapKey: ByteArray, want: Boolean = true) = save(bundle, wrapKey, want)

    /**
     * 换上同一套密钥的新版密钥包（改了密码或换了恢复密钥）。本机封装的包裹密钥不变：主密钥没变，它也不会变。
     * 不是同一套（vaultId 不同）时抛异常。
     */
    fun replaceBundle(bundle: String) {
        val cur = vaultId ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
        require(KeyBundle.parse(bundle).vaultId == cur) { "vault mismatch" }
        if (!prefs.edit().putString(KEY_BUNDLE, bundle).commit()) throw java.io.IOException(tr(R.string.e2ee_save_key_failed))
    }

    /**
     * 还没确认抄下的恢复密钥，用硬件密钥封装后存在本机：App 在用户抄下之前被系统杀掉，下次打开还能再显示。
     * 用户确认后马上删掉（传 null）。
     */
    fun setPendingRecovery(display: String?) {
        val edit = prefs.edit()
        if (display == null) edit.remove(KEY_PENDING_RECOVERY)
        else edit.putString(KEY_PENDING_RECOVERY, Base64.encodeToString(seal(display.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        edit.commit()
    }

    /** 存着的还没确认的恢复密钥；没有或者解不开（硬件密钥失效）时为 null。 */
    fun pendingRecovery(): String? = runCatching { unseal(prefs.getString(KEY_PENDING_RECOVERY, null))?.toString(Charsets.UTF_8) }.getOrNull()

    /** 核对解锁密码（关闭加密、在新设备恢复时用）。密码不对会抛异常。 */
    fun checkPassword(password: String) {
        val json = bundleJson ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
        KeyBundle.unlockWithPassword(json, password)
    }

    /** 关闭加密并且旧备份都转换完以后，删掉本机的密钥（连同以前的另一套）。 */
    fun forget() {
        val edit = prefs.edit().remove(KEY_BUNDLE).remove(KEY_WRAP).remove(KEY_PENDING_RECOVERY).putBoolean(KEY_WANT, false)
        oldKeyIds().forEach { edit.remove(KEY_OLD_BUNDLE + it).remove(KEY_OLD_WRAP + it) }
        edit.remove(KEY_OLD_IDS).commit()
    }

    // ---- 以前的另一套密钥 ----
    // 用户接入了一个用另一套密钥加密过的网盘账号、并用那套的密码解开以后，那套密钥暂存在这里：
    // 那个账号里的照片照样能看，后台再把它们逐个换成当前这套密钥；换完就删掉。

    /** 暂存着的旧密钥（vaultId）。 */
    fun oldKeyIds(): Set<String> = prefs.getStringSet(KEY_OLD_IDS, emptySet())!!.toSet()

    /** 某套旧密钥的包裹密钥；没有时为 null，硬件密钥失效时抛 [VaultBrokenException]。 */
    fun oldKey(id: String): ByteArray? = if (id in oldKeyIds()) unseal(prefs.getString(KEY_OLD_WRAP + id, null)) else null

    fun oldBundle(id: String): String? = prefs.getString(KEY_OLD_BUNDLE + id, null)

    /** 暂存一套已经解开的旧密钥。 */
    fun addOldKey(bundle: String, vaultId: String, wrapKey: ByteArray) {
        val ok = prefs.edit()
            .putString(KEY_OLD_BUNDLE + vaultId, bundle)
            // 不能像 save() 那样失败时重建硬件密钥：当前这套也是用它封装的，删了就解不开了
            .putString(KEY_OLD_WRAP + vaultId, Base64.encodeToString(seal(wrapKey), Base64.NO_WRAP))
            .putStringSet(KEY_OLD_IDS, oldKeyIds() + vaultId)
            .commit()
        if (!ok) throw java.io.IOException(tr(R.string.e2ee_save_key_failed))
    }

    fun dropOldKey(id: String) {
        prefs.edit().remove(KEY_OLD_BUNDLE + id).remove(KEY_OLD_WRAP + id).putStringSet(KEY_OLD_IDS, oldKeyIds() - id).commit()
    }

    /**
     * 用户选了保留以前那套：把旧密钥 [id] 换成当前密钥，当前这套变成“旧的”（等着被换掉）。一次写入，不会只换一半。
     */
    fun swapWithOld(id: String) {
        val oldBundle = oldBundle(id) ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
        val oldWrap = prefs.getString(KEY_OLD_WRAP + id, null) ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
        val curBundle = bundleJson ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
        val curWrap = prefs.getString(KEY_WRAP, null) ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
        val curId = vaultId ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
        val ok = prefs.edit()
            .putString(KEY_BUNDLE, oldBundle).putString(KEY_WRAP, oldWrap)
            .remove(KEY_OLD_BUNDLE + id).remove(KEY_OLD_WRAP + id)
            .putString(KEY_OLD_BUNDLE + curId, curBundle).putString(KEY_OLD_WRAP + curId, curWrap)
            .putStringSet(KEY_OLD_IDS, oldKeyIds() - id + curId)
            .commit()
        if (!ok) throw java.io.IOException(tr(R.string.e2ee_save_key_failed))
    }

    /** 密钥和“要不要加密”一次写进去（commit），不会出现只写了一半的情况。 */
    private fun save(bundle: String, wrapKey: ByteArray, want: Boolean) {
        val blob = try {
            seal(wrapKey)
        } catch (e: Exception) {
            // 硬件密钥坏了：删掉重建一把再试
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
            seal(wrapKey)
        }
        val ok = prefs.edit()
            .putString(KEY_BUNDLE, bundle)
            .putString(KEY_WRAP, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putBoolean(KEY_WANT, want)
            .commit()
        if (!ok) throw java.io.IOException(tr(R.string.e2ee_save_key_failed))
    }

    private fun seal(wrapKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        return cipher.iv + cipher.doFinal(wrapKey)
    }

    private fun deviceKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "zyll-cloud-vault"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_WANT = "wantEncrypted"
        const val KEY_BUNDLE = "bundle"
        const val KEY_WRAP = "wrapKey"
        const val KEY_OLD_IDS = "oldKeyIds"
        const val KEY_OLD_BUNDLE = "oldBundle-"
        const val KEY_OLD_WRAP = "oldWrap-"
        const val KEY_PENDING_RECOVERY = "pendingRecovery"
    }
}

/** 本机保存的加密密钥打不开了（硬件密钥失效）。用密码或恢复密钥重新解锁就能修好。 */
class VaultBrokenException(cause: Throwable) : java.io.IOException(tr(R.string.e2ee_local_key_broken), cause)
