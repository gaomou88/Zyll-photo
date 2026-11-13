package app.zyll.gallery.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.zyll.gallery.R
import app.zyll.media.tr

/** 用户协议和隐私政策的版本：条款有实质改动时加一，所有人会再被问一次。 */
private const val CONSENT_VERSION = 1
private const val PREFS = "legal-consent"
private const val KEY = "accepted_version"
private const val LEGAL_BASE = "https://photo.zyll.top/"

fun hasLegalConsent(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0) >= CONSENT_VERSION

/**
 * 第一次打开时先请用户同意《使用条款》和《隐私政策》，同意前不显示 [content]（相册界面不出来）。注意：Play 内购查询在 MainActivity.onCreate 里就开始了，不受这里拦。
 * 点“不同意”调用 [onDecline]（一般是关掉页面）；对话框不能点外面或按返回关掉。
 */
@Composable
fun LegalConsentGate(onDecline: () -> Unit, onAccepted: () -> Unit = {}, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var accepted by remember { mutableStateOf(hasLegalConsent(context)) }
    if (accepted) {
        content()
        return
    }
    val suffix = tr(R.string.legal_page_suffix)
    val open = { page: String ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$LEGAL_BASE$page$suffix.html"))) }
        Unit
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(tr(R.string.consent_title)) },
        text = {
            // 链接放在正文前面，小屏、大字体下也能看到；内容能滚动
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { open("terms") }) { Text(tr(R.string.about_terms)) }
                    TextButton(onClick = { open("privacy") }) { Text(tr(R.string.about_privacy)) }
                }
                Text(tr(R.string.consent_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, CONSENT_VERSION).apply()
                accepted = true
                onAccepted()
            }) { Text(tr(R.string.consent_agree)) }
        },
        dismissButton = { TextButton(onClick = onDecline) { Text(tr(R.string.consent_decline)) } },
    )
}

