package ch.imail.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.imail.app.MailViewModel
import ch.imail.app.PhonePane
import ch.imail.app.ui.components.GroupedSection
import ch.imail.app.ui.components.InsetDivider
import ch.imail.app.ui.components.LargeTitle
import ch.imail.app.ui.components.NavText
import ch.imail.app.ui.components.SearchField
import ch.imail.app.ui.components.SenderAvatar
import ch.imail.app.ui.components.UnreadDot
import ch.imail.app.ui.theme.IosType
import ch.imail.app.ui.theme.LocalIos
import ch.imail.core.mail.FolderRole
import ch.imail.core.mail.MailFolder
import ch.imail.core.mail.MessageSummary
import java.text.DateFormat
import java.util.Date

/** iPhone: stack navigation. iPad / large screens: 2 columns (list | detail), 3 columns (folders | list | detail) when wide. */
@Composable
fun MailApp(vm: MailViewModel, width: WindowWidthSizeClass, lockEnabled: Boolean, onToggleLock: (Boolean) -> Unit) {
    var composing by remember { mutableStateOf<ComposeSeed?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val wide = width != WindowWidthSizeClass.Compact
    val threeColumns = width == WindowWidthSizeClass.Expanded

    BackHandler(enabled = !wide && vm.phonePane != PhonePane.FOLDERS) {
        if (vm.phonePane == PhonePane.DETAIL) vm.closeMessage() else vm.phonePane = PhonePane.FOLDERS
    }

    Box(Modifier.fillMaxSize()) {
        val folders = @Composable { m: Modifier -> FolderPane(vm, m, onSettings = { showSettings = true }) }
        val list = @Composable { m: Modifier -> MessageListPane(vm, m, onCompose = { composing = ComposeSeed() },
            onOpen = { msg -> if (vm.inDrafts) vm.editDraft(msg.uid) { composing = it } else vm.openMessage(msg.uid) }, onBack = if (!threeColumns) ({ vm.phonePane = PhonePane.FOLDERS }) else null) }
        val detail = @Composable { m: Modifier -> DetailPane(vm, m, onReply = { composing = it }, showBack = !wide) }

        if (!wide) {
            when (vm.phonePane) {
                PhonePane.FOLDERS -> folders(Modifier.fillMaxSize())
                PhonePane.LIST -> list(Modifier.fillMaxSize())
                PhonePane.DETAIL -> detail(Modifier.fillMaxSize())
            }
        } else if (vm.phonePane == PhonePane.FOLDERS && !threeColumns) {
            folders(Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize()) {
                if (threeColumns) { folders(Modifier.width(280.dp).fillMaxHeight()); VerticalRule() }
                list(Modifier.width(if (threeColumns) 380.dp else 340.dp).fillMaxHeight()); VerticalRule()
                detail(Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = LocalIos.current.blue)
    }

    composing?.let { seed -> ComposeSheet(vm, seed, onDismiss = { composing = null }) }
    if (showSettings) SettingsDialog(vm, lockEnabled, onToggleLock, onDismiss = { showSettings = false })
    vm.notice?.let { msg ->
        AlertDialog(onDismissRequest = { vm.notice = null }, confirmButton = { TextButton({ vm.notice = null }) { Text("OK") } }, title = { Text("Vorgemerkt") }, text = { Text(msg) })
    }
    vm.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.error = null }, confirmButton = { TextButton({ vm.error = null }) { Text("OK") } },
            title = { Text("Hinweis") }, text = { Text(msg) },
        )
    }
}

@Composable private fun VerticalRule() { Box(Modifier.width(0.5.dp).fillMaxHeight().background(LocalIos.current.separator)) }

@Composable
private fun FolderPane(vm: MailViewModel, modifier: Modifier, onSettings: () -> Unit) {
    val c = LocalIos.current
    Column(modifier.background(c.background)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { NavText("Einstellungen", onClick = onSettings) }
        LargeTitle("Postfächer")
        LazyColumn {
            item {
                GroupedSection(header = "Konten") {
                    vm.accounts.forEachIndexed { i, a ->
                        if (i > 0) InsetDivider(start = 56)
                        Row(
                            Modifier.fillMaxWidth().clickable { if (a.id != vm.activeId) vm.switchAccount(a.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SenderAvatar(a.email, a.displayName.ifBlank { null }, size = 32.dp, lookupLogo = false)
                            Text(a.email, style = IosType.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f))
                            if (a.id == vm.activeId) Text("✓", style = IosType.body, color = c.blue)
                        }
                    }
                    InsetDivider()
                    Text("Konto hinzufügen", style = IosType.body, color = c.blue, modifier = Modifier.fillMaxWidth().clickable { vm.addingAccount = true }.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            item {
                GroupedSection {
                    vm.folders.forEachIndexed { i, f ->
                        if (i > 0) InsetDivider(start = 56)
                        FolderRow(f, selected = f.fullName == vm.folder) { vm.selectFolder(f.fullName) }
                    }
                }
            }
        }
    }
}

private fun icon(role: FolderRole): ImageVector = when (role) {
    FolderRole.INBOX -> Icons.Filled.Inbox
    FolderRole.SENT -> Icons.Filled.Send
    FolderRole.DRAFTS -> Icons.Filled.Drafts
    FolderRole.TRASH -> Icons.Filled.Delete
    FolderRole.ARCHIVE -> Icons.Filled.Archive
    FolderRole.JUNK -> Icons.Filled.Report
    FolderRole.OTHER -> Icons.Filled.Folder
}

@Composable
private fun FolderRow(f: MailFolder, selected: Boolean, onClick: () -> Unit) {
    val c = LocalIos.current
    Row(
        Modifier.fillMaxWidth().background(if (selected) c.fill else c.cell).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon(f.role), null, tint = c.blue, modifier = Modifier.size(24.dp))
        Text(
            when (f.role) { FolderRole.INBOX -> "Eingang"; FolderRole.SENT -> "Gesendet"; FolderRole.DRAFTS -> "Entwürfe"; FolderRole.TRASH -> "Papierkorb"; FolderRole.ARCHIVE -> "Archiv"; FolderRole.JUNK -> "Spam"; else -> f.displayName },
            style = IosType.body, color = c.label, modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        if (f.unread > 0) Text(f.unread.toString(), style = IosType.body, color = c.secondaryLabel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageListPane(vm: MailViewModel, modifier: Modifier, onCompose: () -> Unit, onOpen: (MessageSummary) -> Unit, onBack: (() -> Unit)?) {
    val c = LocalIos.current
    val title = vm.folders.firstOrNull { it.fullName == vm.folder }?.let { f ->
        if (f.role == FolderRole.INBOX) "Eingang" else f.displayName
    } ?: "Eingang"
    Column(modifier.background(c.background)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (onBack != null) NavText("‹ Postfächer") { onBack() } else Box(Modifier.size(1.dp))
            Row { NavText("Aktualisieren") { vm.loadMessages() }; NavText("Neu", bold = true, onClick = onCompose) }
        }
        LargeTitle(title)
        if (vm.offline || vm.pending > 0) Text(
            (if (vm.offline) "Offline – gespeicherte E-Mails" else "Synchronisiere…") + if (vm.pending > 0) " · ${vm.pending} vorgemerkt" else "", style = IosType.footnote, color = c.secondaryLabel,
            modifier = Modifier.fillMaxWidth().background(c.fill).padding(horizontal = 16.dp, vertical = 6.dp),
        )
        SearchField(vm.query, vm::search, "Suchen")
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(vm.messages, key = { it.uid }) { m ->
                val state = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    when (v) {
                        SwipeToDismissBoxValue.EndToStart -> { vm.delete(m.uid); true }
                        SwipeToDismissBoxValue.StartToEnd -> { vm.toggleSeen(m); false }
                        else -> false
                    }
                })
                SwipeToDismissBox(state, backgroundContent = {
                    val dir = state.dismissDirection
                    Box(
                        Modifier.fillMaxSize().background(if (dir == SwipeToDismissBoxValue.EndToStart) c.red else c.blue).padding(horizontal = 24.dp),
                        contentAlignment = if (dir == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
                    ) { Text(if (dir == SwipeToDismissBoxValue.EndToStart) "Löschen" else if (m.seen) "Ungelesen" else "Gelesen", color = androidx.compose.ui.graphics.Color.White, style = IosType.headline) }
                }) {
                    MessageRow(m, selected = vm.open?.summary?.uid == m.uid) { onOpen(m) }
                }
                InsetDivider(start = 72)
            }
            if (vm.messages.isEmpty() && !vm.loading) item { Text("Keine Nachrichten", style = IosType.body, color = c.secondaryLabel, modifier = Modifier.padding(24.dp)) }
        }
    }
}

@Composable
private fun MessageRow(m: MessageSummary, selected: Boolean, onClick: () -> Unit) {
    val c = LocalIos.current
    Row(
        Modifier.fillMaxWidth().background(if (selected) c.fill else c.cell).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(16.dp).padding(top = 14.dp)) { if (!m.seen) UnreadDot() }
        SenderAvatar(m.from?.address.orEmpty(), m.from?.name, size = 44.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.from?.display ?: "Unbekannt", style = IosType.headline, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(shortDate(m.dateMs), style = IosType.subheadline, color = c.secondaryLabel, modifier = Modifier.padding(start = 8.dp))
            }
            Text(m.subject.ifBlank { "(Kein Betreff)" }, style = IosType.subheadline, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.preview, style = IosType.subheadline, color = c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun shortDate(ms: Long): String {
    if (ms == 0L) return ""
    val d = Date(ms)
    val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).let { it.format(d) == it.format(Date()) }
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(d) else DateFormat.getDateInstance(DateFormat.SHORT).format(d)
}
