package app.zyll.gallery.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * App 的固定指纹：当前安装包签名证书的 SHA-256。
 * 它由签名密钥唯一决定，谁用别的密钥重新签名，这里就会变一个样，
 * 所以能当作「这份是不是原版」的凭据，显示在关于页里。
 */
object AppFingerprint {
    /** 形如 "A1:B2:C3:…"，拿不到时返回 null。 */
    fun sha256(context: Context): String? {
        val signatures = try {
            val pm = context.packageManager
            val pkg = context.packageName
            if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo ?: return null
                // 当前证书：多签名取 apkContentsSigners，单签名取历史里的最新一条（轮换过也显示现在的）
                if (signers.hasMultipleSigners()) signers.apkContentsSigners else signers.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
        } catch (_: Exception) {
            null
        } ?: return null
        val cert = signatures.lastOrNull()?.toByteArray() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /** 关于页只放前几段，省得占一整行。 */
    fun short(full: String, groups: Int = 8): String {
        val parts = full.split(":")
        return if (parts.size <= groups) full else parts.take(groups).joinToString(":") + "…"
    }
}
