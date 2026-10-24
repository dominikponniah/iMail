package ch.imail.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.imail.app.MailViewModel
import ch.imail.app.ui.components.FieldRow
import ch.imail.app.ui.components.GroupedSection
import ch.imail.app.ui.components.InsetDivider
import ch.imail.app.ui.components.LargeTitle
import ch.imail.app.ui.components.NavText
import ch.imail.app.ui.components.PillButton
import ch.imail.app.ui.theme.IosType
import ch.imail.app.ui.theme.LocalIos
import ch.imail.core.logo.FreemailDomains
import ch.imail.core.mail.Account
import ch.imail.core.mail.ConnectionSecurity
import ch.imail.core.mail.ServerConfig

@Composable
fun SetupScreen(vm: MailViewModel) {
    val c = LocalIos.current
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf("") }
    var imapPort by remember { mutableStateOf("993") }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("465") }
    var smtpSecurity by remember { mutableStateOf(ConnectionSecurity.SSL_TLS) }

    val domain = FreemailDomains.domainOf(email)
    fun suggest() {
        if (domain != null) {
            if (imapHost.isBlank()) imapHost = "imap.$domain"
            if (smtpHost.isBlank()) smtpHost = "smtp.$domain"
        }
    }
    val port = imapPort.toIntOrNull()
    val sport = smtpPort.toIntOrNull()
    val valid = domain != null && password.isNotEmpty() && imapHost.isNotBlank() && smtpHost.isNotBlank() &&
        port in 1..65535 && sport in 1..65535

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            LargeTitle("Konto hinzufügen")
            GroupedSection(footer = "Zugangsdaten werden verschlüsselt im Android Keystore gespeichert. Verbindungen sind ausschliesslich TLS-gesichert.") {
                FieldRow("Name", name, { name = it }, "Optional")
                InsetDivider()
                FieldRow("E-Mail", email, { email = it.trim(); suggest() }, "name@firma.ch")
                InsetDivider()
                FieldRow("Passwort", password, { password = it }, "Erforderlich", secret = true)
            }
            GroupedSection(header = "Posteingangsserver (IMAP)") {
                FieldRow("Server", imapHost, { imapHost = it.trim() }, "imap.example.com")
                InsetDivider()
                FieldRow("Port", imapPort, { imapPort = it.filter(Char::isDigit).take(5) }, "993")
            }
            GroupedSection(header = "Postausgangsserver (SMTP)", footer = "465 = SSL/TLS, 587 = STARTTLS (wird erzwungen). Klartext ist nicht möglich.") {
                FieldRow("Server", smtpHost, { smtpHost = it.trim() }, "smtp.example.com")
                InsetDivider()
                FieldRow("Port", smtpPort, {
                    smtpPort = it.filter(Char::isDigit).take(5)
                    smtpSecurity = if (smtpPort == "587") ConnectionSecurity.STARTTLS else ConnectionSecurity.SSL_TLS
                }, "465")
                InsetDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sicherheit", style = IosType.body, color = c.label, modifier = Modifier.weight(1f))
                    NavText("SSL/TLS", bold = smtpSecurity == ConnectionSecurity.SSL_TLS) { smtpSecurity = ConnectionSecurity.SSL_TLS }
                    NavText("STARTTLS", bold = smtpSecurity == ConnectionSecurity.STARTTLS) { smtpSecurity = ConnectionSecurity.STARTTLS }
                }
            }
            vm.error?.let { Text(it, style = IosType.footnote, color = c.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
            Column(Modifier.padding(16.dp)) {
                if (vm.setupBusy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                else PillButton("Anmelden", enabled = valid) {
                    vm.error = null
                    vm.signIn(
                        Account(
                            email = email, username = email, displayName = name.trim(),
                            imap = ServerConfig(imapHost, port!!, if (port == 143) ConnectionSecurity.STARTTLS else ConnectionSecurity.SSL_TLS),
                            smtp = ServerConfig(smtpHost, sport!!, smtpSecurity),
                        ),
                        password,
                    )
                }
            }
        }
    }
}
