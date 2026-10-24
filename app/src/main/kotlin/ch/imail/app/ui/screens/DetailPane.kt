package ch.imail.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ch.imail.app.MailViewModel
import ch.imail.app.ui.components.NavText
import ch.imail.app.ui.components.SenderAvatar
import ch.imail.app.ui.theme.IosType
import ch.imail.app.ui.theme.LocalIos
import ch.imail.core.mail.AttachmentInfo
import ch.imail.core.mail.MessageBody
import ch.imail.core.security.FileNameSanitizer
import ch.imail.core.security.HtmlSanitizer
import ch.imail.core.security.LinkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DetailPane(vm: MailViewModel, modifier: Modifier, onReply: (ComposeSeed) -> Unit, showBack: Boolean) {
    val c = LocalIos.current
    val msg = vm.open
    Column(modifier.background(c.cell)) {
        if (msg == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Keine Nachricht ausgewählt", style = IosType.title3, color = c.tertiaryLabel)
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (showBack) NavText("‹ Zurück") { vm.closeMessage() } else Text("")
            Row {
                NavText("Antworten") { onReply(ComposeSeed.reply(msg, all = false)) }
                NavText("Alle") { onReply(ComposeSeed.reply(msg, all = true)) }
                NavText("Weiterleiten") { onReply(ComposeSeed.forward(msg)) }
                NavText("Löschen", destructive = true) { vm.delete(msg.summary.uid) }
            }
        }
        Header(msg)
        Body(vm, msg, Modifier.weight(1f))
        Attachments(vm, msg)
    }
}

@Composable
private fun Header(msg: MessageBody) {
    val c = LocalIos.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        SenderAvatar(msg.summary.from?.address.orEmpty(), msg.summary.from?.name, size = 48.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(msg.summary.from?.display ?: "Unbekannt", style = IosType.headline, color = c.label)
            Text("An: " + msg.to.joinToString { it.display }, style = IosType.footnote, color = c.secondaryLabel, maxLines = 2)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(msg.summary.dateMs)), style = IosType.footnote, color = c.secondaryLabel)
        }
    }
    Text(msg.summary.subject.ifBlank { "(Kein Betreff)" }, style = IosType.title3, color = c.label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Body(vm: MailViewModel, msg: MessageBody, modifier: Modifier) {
    val c = LocalIos.current
    val scope = rememberCoroutineScope()
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val html = msg.html
    if (html == null) {
        Text(msg.text.orEmpty(), style = IosType.body, color = c.label, modifier = modifier.padding(16.dp))
        return
    }
    val sanitized = remember(msg.summary.uid, vm.allowRemoteImages, vm.inlineImages) {
        HtmlSanitizer.sanitize(html, vm.allowRemoteImages) { cid -> vm.inlineImages[cid] }
    }
    Column(modifier) {
        if (sanitized.blockedRemoteContent > 0 && !vm.allowRemoteImages) {
            Row(Modifier.fillMaxWidth().background(c.fill).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Externe Bilder blockiert", style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.weight(1f))
                NavText("Bilder laden") { vm.allowRemoteImages = true }
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setGeolocationEnabled(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        setSupportZoom(false)
                        builtInZoomControls = false
                    }
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.hasGesture() || request.isRedirect) {
                                val url = request.url.toString()
                                if (LinkPolicy.isSafe(url)) pendingLink = url
                            }
                            return true // the WebView itself never navigates
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            // Only data: (inline) and, if the user allowed it, https images may load.
                            val ok = request.url.scheme == "data" || (vm.allowRemoteImages && request.url.scheme == "https" && request.method == "GET")
                            return if (ok) null else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                }
            },
            update = { web ->
                web.loadDataWithBaseURL(null, wrap(sanitized.html, vm.allowRemoteImages), "text/html", "utf-8", null)
            },
            onRelease = { it.destroy() },
        )
    }
    pendingLink?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text("Link öffnen?") },
            text = { Text(LinkPolicy.displayHost(url)?.let { "Dieser Link führt zu $it.\n\n$url" } ?: url) },
            confirmButton = { TextButton({ pendingLink = null; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) } }) { Text("Öffnen") } },
            dismissButton = { TextButton({ pendingLink = null }) { Text("Abbrechen") } },
        )
    }
}

private fun wrap(body: String, remote: Boolean): String {
    val img = if (remote) "data: https:" else "data:"
    return """<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src $img; style-src 'unsafe-inline'">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<style>body{font:17px -apple-system,Roboto,sans-serif;margin:12px;word-wrap:break-word;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{max-width:100%}
@media (prefers-color-scheme:dark){body{color:#fff;background:transparent}a{color:#0a84ff}}</style></head><body>$body</body></html>"""
}

@Composable
private fun Attachments(vm: MailViewModel, msg: MessageBody) {
    val files = msg.attachments.filter { !it.inline || it.contentId == null }
    if (files.isEmpty()) return
    val c = LocalIos.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<AttachmentInfo?>(null) }
    var warn by remember { mutableStateOf<AttachmentInfo?>(null) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    val ctx = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val data = bytes
        if (uri != null && data != null) scope.launch(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) } }
        bytes = null
    }
    fun save(a: AttachmentInfo) {
        scope.launch {
            try { bytes = vm.attachment(a.index); pending = a; saver.launch(FileNameSanitizer.sanitize(a.fileName)) }
            catch (e: Exception) { vm.error = if (e is ch.imail.core.mail.AttachmentTooLargeException) "Der Anhang ist zu gross." else "Anhang konnte nicht geladen werden." }
        }
    }
    Column(Modifier.fillMaxWidth().background(c.background).padding(8.dp)) {
        files.forEach { a ->
            Text("📎 ${a.fileName}  (${a.size / 1024} KB)", style = IosType.subheadline, color = c.blue,
                modifier = Modifier.fillMaxWidth().clickable { if (FileNameSanitizer.isDangerous(a.fileName)) warn = a else save(a) }.padding(8.dp))
        }
    }
    warn?.let { a ->
        AlertDialog(
            onDismissRequest = { warn = null }, title = { Text("Ausführbare Datei") },
            text = { Text("„${a.fileName}“ kann Schadsoftware enthalten. Nur speichern, wenn du dem Absender vertraust. Die Datei wird nicht automatisch geöffnet.") },
            confirmButton = { TextButton({ warn = null; save(a) }) { Text("Trotzdem speichern") } },
            dismissButton = { TextButton({ warn = null }) { Text("Abbrechen") } },
        )
    }
}
