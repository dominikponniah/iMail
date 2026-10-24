package ch.imail.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.imail.app.data.MailRepository
import ch.imail.app.push.MailPushService
import ch.imail.app.push.PollWorker
import ch.imail.core.mail.Account
import ch.imail.core.mail.FolderRole
import ch.imail.core.mail.ImapClient
import ch.imail.core.mail.MailFolder
import ch.imail.core.mail.MessageBody
import ch.imail.core.mail.MessageSummary
import ch.imail.core.mail.OutgoingMail
import ch.imail.core.mail.SmtpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PhonePane { FOLDERS, LIST, DETAIL }

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as ImailApp
    var repo by mutableStateOf(graph.credentials.load()?.let { MailRepository(it) }); private set

    var folders by mutableStateOf<List<MailFolder>>(emptyList()); private set
    var folder by mutableStateOf("INBOX"); private set
    var messages by mutableStateOf<List<MessageSummary>>(emptyList()); private set
    var query by mutableStateOf(""); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var open by mutableStateOf<MessageBody?>(null); private set
    var phonePane by mutableStateOf(PhonePane.LIST)
    var allowRemoteImages by mutableStateOf(false)
    /** Content-ID -> data: URI for small inline images of the open message (memory only). */
    var inlineImages by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var setupBusy by mutableStateOf(false); private set
    private var searchJob: Job? = null

    init { if (repo != null) startBackground().also { refreshAll() } }

    /** Verifies IMAP and SMTP (TLS, certificate, credentials) before anything is stored. */
    fun signIn(account: Account, password: String) {
        setupBusy = true; error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ImapClient(account, password).testConnection()
                    SmtpClient(account, password).testConnection()
                    graph.credentials.save(account, password)
                }
                repo = MailRepository(graph.credentials.load()!!)
                startBackground()
                refreshAll()
            } catch (e: Exception) {
                error = friendly(e)
            } finally { setupBusy = false }
        }
    }

    fun signOut() {
        MailPushService.stop(graph); PollWorker.cancel(graph)
        graph.credentials.clear()
        repo = null; folders = emptyList(); messages = emptyList(); open = null
    }

    private fun startBackground() { MailPushService.start(graph); PollWorker.schedule(graph) }

    fun refreshAll() { loadFolders(); loadMessages() }

    fun loadFolders() = launchLoading { repo?.let { folders = it.folders() } }

    fun loadMessages() = launchLoading { repo?.let { messages = it.messages(folder, query.ifBlank { null }) } }

    fun selectFolder(name: String) { folder = name; query = ""; open = null; phonePane = PhonePane.LIST; loadMessages() }

    fun search(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(350); loadMessages() }
    }

    fun openMessage(uid: Long) {
        allowRemoteImages = false
        launchLoading {
            val body = repo!!.message(folder, uid)
            inlineImages = loadInline(body)
            open = body; phonePane = PhonePane.DETAIL
            messages = messages.map { if (it.uid == uid) it.copy(seen = true) else it }
        }
    }

    fun closeMessage() { open = null; inlineImages = emptyMap(); phonePane = PhonePane.LIST }

    /** Raster images only (no SVG), max 1 MB each, so inline content cannot carry script or exhaust memory. */
    private suspend fun loadInline(body: MessageBody): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (a in body.attachments) {
            val cid = a.contentId ?: continue
            if (!a.mimeType.startsWith("image/") || a.mimeType.contains("svg") || a.size > 1_000_000) continue
            runCatching { repo!!.attachment(folder, body.summary.uid, a.index) }.getOrNull()?.let {
                out[cid] = "data:${a.mimeType};base64," + android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
            }
        }
        return out
    }

    fun toggleSeen(m: MessageSummary) = launchLoading {
        repo!!.setSeen(folder, m.uid, !m.seen)
        messages = messages.map { if (it.uid == m.uid) it.copy(seen = !m.seen) else it }
    }

    fun delete(uid: Long) = launchLoading {
        repo!!.delete(folder, uid)
        messages = messages.filterNot { it.uid == uid }
        if (open?.summary?.uid == uid) closeMessage()
    }

    fun send(mail: OutgoingMail, onDone: () -> Unit) {
        viewModelScope.launch {
            try { repo!!.send(mail); onDone() } catch (e: Exception) { error = friendly(e) }
        }
    }

    suspend fun attachment(index: Int): ByteArray = repo!!.attachment(folder, open!!.summary.uid, index)

    val inboxRole get() = folders.firstOrNull { it.role == FolderRole.INBOX }

    private fun launchLoading(block: suspend () -> Unit) {
        viewModelScope.launch {
            loading = true
            try { block() } catch (e: Exception) { error = friendly(e) } finally { loading = false }
        }
    }

    /** Never shows server banners or stack traces to the user. */
    private fun friendly(e: Exception): String = when {
        e is jakarta.mail.AuthenticationFailedException -> "Anmeldung fehlgeschlagen. Bitte Benutzername und Passwort prüfen."
        e is javax.net.ssl.SSLException || e.cause is javax.net.ssl.SSLException -> "Sichere Verbindung nicht möglich (Zertifikat oder TLS ungültig)."
        e is java.net.UnknownHostException || e.cause is java.net.UnknownHostException -> "Server nicht gefunden."
        e is java.net.SocketTimeoutException || e is java.net.ConnectException || e.cause is java.net.ConnectException -> "Keine Verbindung zum Server."
        e is IllegalArgumentException -> e.message ?: "Ungültige Eingabe."
        e is ch.imail.core.mail.AttachmentTooLargeException -> "Der Anhang ist zu gross."
        else -> "Vorgang fehlgeschlagen."
    }
}
