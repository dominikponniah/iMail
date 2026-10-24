package ch.imail.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.imail.app.MailViewModel
import ch.imail.app.ui.components.FieldRow
import ch.imail.app.ui.components.InsetDivider
import ch.imail.app.ui.components.NavText
import ch.imail.app.ui.theme.IosType
import ch.imail.app.ui.theme.LocalIos
import ch.imail.core.mail.MessageBody
import ch.imail.core.mail.OutgoingAttachment
import ch.imail.core.mail.OutgoingMail
import kotlinx.coroutines.launch

data class ComposeSeed(
    val to: String = "", val cc: String = "", val subject: String = "", val body: String = "",
    val inReplyTo: String? = null, val references: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
    /** Set when an existing server-side draft is being edited. */
    val draftFolder: String? = null, val draftUid: Long? = null,
) {
    companion object {
        fun reply(m: MessageBody, all: Boolean): ComposeSeed {
            val from = m.summary.from?.address.orEmpty()
            val cc = if (all) (m.to + m.cc).map { it.address }.filter { !it.equals(from, true) }.joinToString(", ") else ""
            return ComposeSeed(from, cc, prefix("Re: ", m.summary.subject), quote(m), m.messageId, listOfNotNull(m.references, m.messageId).joinToString(" ").ifBlank { null })
        }
        fun forward(m: MessageBody) = ComposeSeed(subject = prefix("Fwd: ", m.summary.subject), body = quote(m))
        private fun prefix(p: String, s: String) = if (s.startsWith(p, true)) s else p + s
        private fun quote(m: MessageBody): String {
            val text = m.text ?: m.html?.let { org.jsoup.Jsoup.parse(it).text() }.orEmpty()
            return "\n\n" + (m.summary.from?.display ?: "") + " schrieb:\n" + text.lines().joinToString("\n") { "> $it" }
        }
    }
}

private const val MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024

/** Reads at most [max] bytes (API 26 compatible replacement for readNBytes). */
private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeSheet(vm: MailViewModel, seed: ComposeSeed, onDismiss: () -> Unit) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf(seed.to) }
    var cc by remember { mutableStateOf(seed.cc) }
    var subject by remember { mutableStateOf(seed.subject) }
    var body by remember { mutableStateOf(seed.body) }
    var sending by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var draftUid by remember { mutableStateOf(seed.draftUid) }
    val draftFolder = seed.draftFolder ?: vm.folders.firstOrNull { it.role == ch.imail.core.mail.FolderRole.DRAFTS }?.fullName
    val files = remember { mutableStateListOf<OutgoingAttachment>().also { it.addAll(seed.attachments) } }

    fun split(s: String) = s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    fun mail() = OutgoingMail(split(to), split(cc), emptyList(), subject, body, files.toList(), seed.inReplyTo, seed.references)
    val dirty = to.isNotBlank() || cc.isNotBlank() || subject.isNotBlank() || body.isNotBlank() || files.isNotEmpty()
    val changed = to != seed.to || cc != seed.cc || subject != seed.subject || body != seed.body || files.size != seed.attachments.size

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { q -> if (q.moveToFirst()) q.getString(q.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "attachment"
        val data = ctx.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_ATTACHMENT_BYTES + 1) }
        if (data == null || data.size > MAX_ATTACHMENT_BYTES) vm.error = "Der Anhang ist zu gross (max. 15 MB)."
        else files += OutgoingAttachment(name, ctx.contentResolver.getType(uri) ?: "application/octet-stream", data)
    }

    fun close() { if (changed && dirty) confirmClose = true else onDismiss() }

    ModalBottomSheet(onDismissRequest = ::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.cell) {
        Column(Modifier.fillMaxHeight(0.95f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NavText("Abbrechen", onClick = ::close)
                Text("Neue Nachricht", style = IosType.headline, color = c.label, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                NavText("Senden", bold = true, enabled = !sending && to.isNotBlank()) {
                    sending = true
                    vm.send(mail(), draftUid?.let { u -> draftFolder?.let { it to u } }, onDone = onDismiss, onFail = { sending = false })
                }
            }
            InsetDivider(0)
            FieldRow("An:", to, { to = it }, "empfaenger@example.com")
            InsetDivider()
            FieldRow("Cc:", cc, { cc = it })
            InsetDivider()
            FieldRow("Betreff:", subject, { subject = it })
            InsetDivider()
            files.forEachIndexed { i, f ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📎 ${f.fileName} (${f.bytes.size / 1024} KB)", style = IosType.subheadline, color = c.label, modifier = Modifier.weight(1f))
                    NavText("Entfernen", destructive = true) { files.removeAt(i) }
                }
            }
            NavText("Anhang hinzufügen") { picker.launch("*/*") }
            androidx.compose.foundation.text.BasicTextField(
                value = body, onValueChange = { body = it }, textStyle = IosType.body.copy(color = c.label),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.blue), modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }

    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("Entwurf") },
        text = { Text("Möchtest du diese Nachricht als Entwurf sichern?") },
        confirmButton = {
            TextButton({ confirmClose = false; scope.launch { vm.saveDraft(mail(), draftUid); onDismiss() } }) { Text("Entwurf sichern") }
        },
        dismissButton = {
            Row {
                TextButton({ confirmClose = false }) { Text("Weiter bearbeiten") }
                TextButton({
                    confirmClose = false
                    scope.launch { val u = draftUid; val f = draftFolder; if (u != null && f != null) vm.discardDraft(f, u); onDismiss() }
                }) { Text("Löschen") }
            }
        },
    )
}
