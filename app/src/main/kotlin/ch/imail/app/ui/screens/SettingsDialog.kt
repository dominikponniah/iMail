package ch.imail.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ch.imail.app.ImailApp
import ch.imail.app.MailViewModel
import ch.imail.app.push.MailPushService

@Composable
internal fun SettingsDialog(vm: MailViewModel, lockEnabled: Boolean, onToggleLock: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ImailApp
    var lock by remember { mutableStateOf(lockEnabled) }
    var push by remember { mutableStateOf(app.settings.pushEnabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vm.repo?.account?.email.orEmpty()) },
        text = {
            Column {
                SwitchRow("Benachrichtigungen bei neuer E-Mail", push) { push = it; app.settings.pushEnabled = it; if (it) MailPushService.start(ctx) else MailPushService.stop(ctx) }
                SwitchRow("App-Sperre (Biometrie / Gerätecode)", lock) { lock = it; onToggleLock(it) }
                Text("Für zuverlässige Benachrichtigungen die Akku-Optimierung für iMail in den Android-Einstellungen ausschalten.")
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Fertig") } },
        dismissButton = { TextButton({ onDismiss(); vm.signOut() }) { Text("Abmelden") } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = androidx.compose.ui.Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
