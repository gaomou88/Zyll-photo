package app.zyll.gallery.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zyll.gallery.BuildConfig
import app.zyll.gallery.Entitlements
import app.zyll.gallery.flavor.FlavorServices
import app.zyll.media.tr
import app.zyll.gallery.R

private const val GITHUB_URL = "https://github.com/gaomou88"
private const val SITE_URL = "https://zyll.top/"
private const val WEB_URL = "https://photo.zyll.top/"

/** Play 商店的兑换页：在 Play 里输入兑换代码。兑换成功后回到 App 时 MainActivity.onResume 会重新查购买记录。 */
internal const val PLAY_REDEEM_URL = "https://play.google.com/redeem"

/**
 * 打开兑换页：指定交给 Play 商店，不让系统（比如三星手机）把链接交给浏览器。
 * 这台手机没有 Play 商店、或者 Play 不认这个地址时，才退回网页。
 */
internal fun openPlayRedeem(context: android.content.Context) {
    val uri = Uri.parse(PLAY_REDEEM_URL)
    val play = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending")
    if (context !is android.app.Activity) play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(play)
    } catch (_: android.content.ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
    } catch (_: SecurityException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}

@Composable
fun AboutScreen(entitlements: Entitlements, padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val hasEditor = entitlements.hasEditor
    val pro by entitlements.proUnlocked.collectAsState()
    val open = { url: String -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }; Unit }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = padding.calculateBottomPadding() + 24.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.common_back)) }
            Text(tr(R.string.settings_group_about), style = MaterialTheme.typography.titleLarge)
        }

        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                contentAlignment = Alignment.Center,
            ) {
                Text("Z", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
            Text(tr(R.string.app_display_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 14.dp))
            Text(
                tr(R.string.about_version, BuildConfig.VERSION_NAME, FlavorServices.DISTRIBUTION),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                tr(R.string.about_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            Column {
                AboutRow(Icons.Outlined.Person, tr(R.string.about_developer), "JINGHUI GAO")
                AboutRow(Icons.Outlined.Storefront, tr(R.string.about_operator), "Zyll")
                AboutRow(Icons.Outlined.Public, tr(R.string.about_website), "zyll.top", link = true) { open(SITE_URL) }
                AboutRow(Icons.Outlined.Language, tr(R.string.about_web_app), "photo.zyll.top", link = true) { open(WEB_URL) }
                AboutRow(Icons.Outlined.Code, "GitHub", "github.com/gaomou88", link = true) { open(GITHUB_URL) }
                // 网页上的法律页面按语言分文件：简体没有后缀，其他是 .zh-Hant / .en / .ja
                val legal = tr(R.string.legal_page_suffix)
                AboutRow(Icons.Outlined.PrivacyTip, tr(R.string.about_privacy), "", link = true) { open("${WEB_URL}privacy$legal.html") }
                AboutRow(Icons.Outlined.Description, tr(R.string.about_terms), "", link = true) { open("${WEB_URL}terms$legal.html") }
                // 只有 Play 版能用 Google Play 兑换代码；已经是 Pro 就不用再显示
                if (entitlements.hasUpgradePath && !pro) {
                    AboutRow(Icons.Outlined.Redeem, tr(R.string.about_redeem), "Google Play", link = true) { openPlayRedeem(context) }
                }
                val fingerprint = remember { AppFingerprint.sha256(context) }
                if (fingerprint != null) {
                    AboutRow(Icons.Outlined.Fingerprint, tr(R.string.about_signature), AppFingerprint.short(fingerprint))
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr(R.string.about_open_source), style = MaterialTheme.typography.titleSmall)
                val credits = buildList {
                    addAll(listOf("Coil", "Telephoto", "Media3", "AppAuth"))
                    if (FlavorServices.HAS_AI) addAll(listOf("ONNX Runtime", "Chinese-CLIP"))
                    if (hasEditor) addAll(listOf("uCrop", "GPUImage"))
                }
                Text(
                    tr(R.string.about_credits, credits.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            tr(R.string.about_copyright),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

@Composable
private fun AboutRow(icon: ImageVector, label: String, value: String, link: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 14.dp).weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (link) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (link) Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}
