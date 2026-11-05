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
    fun wrapKey(): ByteArray? {
        val stored = prefs.getString(KEY_WRAP, null) ?: return null
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

    /** 核对解锁密码（关闭加密、在新设备恢复时用）。密码不对会抛异常。 */
    fun checkPassword(password: String) {
        val json = bundleJson ?: throw IllegalStateException(tr(R.string.e2ee_never_enabled))
        KeyBundle.unlockWithPassword(json, password)
    }

    /** 关闭加密并且旧备份都转换完以后，删掉本机的密钥。 */
    fun forget() {
        prefs.edit().remove(KEY_BUNDLE).remove(KEY_WRAP).putBoolean(KEY_WANT, false).commit()
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
    }
}

/** 本机保存的加密密钥打不开了（硬件密钥失效）。用密码或恢复密钥重新解锁就能修好。 */
class VaultBrokenException(cause: Throwable) : java.io.IOException(tr(R.string.e2ee_local_key_broken), cause)
