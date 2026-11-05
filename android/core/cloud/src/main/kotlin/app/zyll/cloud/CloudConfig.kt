package app.zyll.cloud

import app.zyll.backup.CloudProvider
import app.zyll.backup.DriveKind
import app.zyll.core.cloud.BuildConfig

/**
 * 两个网盘的 OAuth 参数。客户端 ID 由构建时注入（见 core/cloud/build.gradle.kts），仓库里留空。
 *
 * - Google：在 Google Cloud Console 建 “Android” 类型的 OAuth 客户端，包名 com.zyll.photo，
 *   填签名证书 SHA-1，并在“高级设置”里打开“自定义 URI 方案”。只申请 drive.file 权限：
 *   App 只能看到自己上传的文件，不需要 Google 的敏感权限审核。
 * - OneDrive：在 Microsoft Entra 管理中心注册应用，“支持的帐户类型”选所有 Microsoft 帐户（含个人帐户），
 *   平台选“Android”，包名 com.zyll.photo + 签名哈希，得到的重定向 URI 是 msauth://com.zyll.photo/<签名哈希>。
 *   委托的权限只要 Files.ReadWrite.AppFolder、offline_access、User.Read。
 */
data class ProviderConfig(
    val provider: CloudProvider,
    val clientId: String,
    val authEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String,
    val scopes: List<String>,
) {
    val configured: Boolean get() = clientId.isNotBlank() && redirectUri.isNotBlank()
}

object CloudConfig {
    /** 网盘里放备份的文件夹名。 */
    const val ROOT_FOLDER = "Zyll 相册"

    /** 加密备份的文件夹名（文件名是随机的，看不出内容）。 */
    const val ENCRYPTED_FOLDER = "vault"

    private const val SCHEME = BuildConfig.APP_ID
    private val ONEDRIVE_REDIRECT =
        BuildConfig.ONEDRIVE_SIGNATURE_HASH.takeIf { it.isNotBlank() }?.let { "msauth://${BuildConfig.APP_ID}/$it" } ?: ""

    val google = ProviderConfig(
        provider = CloudProvider.GoogleDrive,
        clientId = BuildConfig.GOOGLE_CLIENT_ID,
        authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        redirectUri = "$SCHEME:/oauth2redirect",
        // drive.appdata：文件放在用户网盘的“应用数据”隐藏区（和 WhatsApp 备份一样），
        // 占用用户的空间，但在网盘里看不到、删不到；用户可以在“管理存储空间”里整体清除。
        scopes = listOf("https://www.googleapis.com/auth/drive.appdata", "openid", "email"),
    )

    val oneDrive = ProviderConfig(
        provider = CloudProvider.OneDrive,
        clientId = BuildConfig.ONEDRIVE_CLIENT_ID,
        authEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        // Azure 里 Android 平台生成的重定向地址（按发布签名的哈希），要和登记的一字不差
        redirectUri = ONEDRIVE_REDIRECT,
        // 只能读写“应用/Zyll 相册”文件夹（OneDrive 没有完全隐藏的区域）
        scopes = listOf("Files.ReadWrite.AppFolder", "User.Read", "offline_access"),
    )

    fun of(p: CloudProvider): ProviderConfig = of(p.kind)

    fun of(kind: DriveKind): ProviderConfig = when (kind) {
        DriveKind.GoogleDrive -> google
        DriveKind.OneDrive -> oneDrive
    }
}
