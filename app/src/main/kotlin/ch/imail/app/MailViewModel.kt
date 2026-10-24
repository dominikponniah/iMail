package ch.imail.app

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.imail.app.push.MailPushService
import ch.imail.app.push.PollWorker
import ch.imail.core.mail.Account
import ch.imail.core.mail.AuthType
import ch.imail.core.mail.FolderRole
import ch.imail.core.mail.MailFolder
import ch.imail.core.mail.MailService
import ch.imail.core.mail.MessageBody
import ch.imail.core.mail.MessageSummary
import ch.imail.core.mail.OutgoingAttachment
import ch.imail.core.mail.OutgoingMail
import ch.imail.core.mail.SecretProvider
import ch.imail.core.oauth.OAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationService

enum class PhonePane { FOLDERS, LIST, DETAIL }

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as ImailApp

    var accounts by mutableStateOf(graph.credentials.all().map { it.account }); private set
    var activeId by mutableStateOf(graph.settings.activeAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id); private set
    var addingAccount by mutableStateOf(false)

    val account: Account? get() = accounts.firstOrNull { it.id == activeId }
    private val service: MailService? get() = activeId?.let { graph.service(it) }

    var folders by mutableStateOf<List<MailFolder>>(emptyList()); private set
    var folder by mutableStateOf("INBOX"); private set
    var messages by mutableStateOf<List<MessageSummary>>(emptyList()); private set
    var query by mutableStateOf(""); private set
    var loading by mutableStateOf(false); private set
    var offline by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var open by mutableStateOf<MessageBody?>(null); private set
    var phonePane by mutableStateOf(PhonePane.LIST)
    var allowRemoteImages by mutableStateOf(false)
    /** Content-ID -> data: URI for small inline images of the open message (memory only). */
    var inlineImages by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var setupBusy by mutableStateOf(false); private set
    private var searchJob: Job? = null

    val inDrafts get() = folders.firstOrNull { it.fullName == folder }?.role == FolderRole.DRAFTS

    init { if (account != null) { startBackground(); refreshAll() } }

    // --- accounts ----------------------------------------------------------------------------------------------------

    /** Verifies IMAP and SMTP (TLS, certificate, credentials) before anything is stored. */
    fun signInWithPassword(account: Account, password: String) {
        setupBusy = true; error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { MailService(account, SecretProvider { password }, null).testConnection() }
                addAccount(account, password)
            } catch (e: Exception) { error = friendly(e) } finally { setupBusy = false }
        }
    }

    fun authorizationIntent(p: OAuthProvider, service: AuthorizationService): Intent = graph.oauth.authorizationIntent(p, service)
    fun isOAuthConfigured(p: OAuthProvider) = graph.oauth.isConfigured(p)

    fun completeOAuth(p: OAuthProvider, data: Intent, service: AuthorizationService) {
        setupBusy = true; error = null
        viewModelScope.launch {
            try {
                val (account, secret) = graph.oauth.complete(p, data, service)
                graph.credentials.upsert(account, secret) // needed so the token provider can refresh
                try {
                    withContext(Dispatchers.IO) { MailService(account, graph.secretFor(account.id), null).testConnection() }
                } catch (e: Exception) { graph.credentials.remove(account.id); throw e }
                finishAdd(account)
            } catch (e: Exception) { error = friendly(e) } finally { setupBusy = false }
        }
    }

    private fun addAccount(account: Account, secret: String) { graph.credentials.upsert(account, secret); finishAdd(account) }

    private fun finishAdd(account: Account) {
        accounts = graph.credentials.all().map { it.account }
        addingAccount = false
        MailPushService.restart(graph); PollWorker.schedule(graph)
        switchAccount(account.id)
    }

    fun switchAccount(id: String) {
        activeId = id; graph.settings.activeAccountId = id
        folders = emptyList(); messages = emptyList(); open = null; query = ""; folder = "INBOX"; offline = false
        phonePane = PhonePane.LIST
        refreshAll()
    }

    fun removeAccount(id: String) {
        graph.credentials.remove(id); graph.forgetAccount(id)
        accounts = graph.credentials.all().map { it.account }
        if (accounts.isEmpty()) { MailPushService.stop(graph); PollWorker.cancel(graph); activeId = null; folders = emptyList(); messages = emptyList(); open = null }
        else { MailPushService.restart(graph); if (activeId == id) switchAccount(accounts.first().id) }
    }

    private fun startBackground() { MailPushService.start(graph); PollWorker.schedule(graph) }

    // --- loading -----------------------------------------------------------------------------------------------------

    fun refreshAll() { loadFolders(); loadMessages() }

    fun loadFolders() = launchLoading {
        val s = service ?: return@launchLoading
        val r = withContext(Dispatchers.IO) { s.folders() }
        folders = r.value; offline = r.offline
    }

    fun loadMessages() = launchLoading {
        val s = service ?: return@launchLoading
        val f = folder; val q = query.ifBlank { null }
        val r = withContext(Dispatchers.IO) { s.messages(f, q) }
        if (f == folder) { messages = r.value; offline = r.offline }
        if (!r.offline && q == null && folders.firstOrNull { it.fullName == f }?.role == FolderRole.INBOX) {
            launch(Dispatchers.IO) { runCatching { s.prefetch(f, r.value) } } // bodies for offline reading
        }
    }

    fun selectFolder(name: String) { folder = name; query = ""; open = null; phonePane = PhonePane.LIST; loadMessages() }

    fun search(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(350); loadMessages() }
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    fun openMessage(uid: Long) {
        allowRemoteImages = false
        launchLoading {
            val s = service ?: return@launchLoading
            val f = folder
            val body = withContext(Dispatchers.IO) { s.message(f, uid).value }
            inlineImages = withContext(Dispatchers.IO) { loadInline(s, f, body) }
            open = body; phonePane = PhonePane.DETAIL
            if (!body.summary.seen) markSeen(uid, true)
        }
    }

    fun closeMessage() { open = null; inlineImages = emptyMap(); phonePane = PhonePane.LIST }

    /** Raster images only (no SVG), max 1 MB each, so inline content cannot carry script or exhaust memory. */
    private fun loadInline(s: MailService, f: String, body: MessageBody): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (a in body.attachments) {
            val cid = a.contentId ?: continue
            if (!a.mimeType.startsWith("image/") || a.mimeType.contains("svg") || a.size > 1_000_000) continue
            runCatching { s.attachment(f, body.summary.uid, a.index) }.getOrNull()?.let {
                out[cid] = "data:${a.mimeType};base64," + android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
            }
        }
        return out
    }

    /** Updates the list immediately; the server flag is synced best-effort (and the local cache always). */
    private fun markSeen(uid: Long, seen: Boolean) {
        messages = messages.map { if (it.uid == uid) it.copy(seen = seen) else it }
        val s = service ?: return; val f = folder
        viewModelScope.launch(Dispatchers.IO) { runCatching { s.markSeen(f, uid, seen) } }
    }

    fun toggleSeen(m: MessageSummary) = markSeen(m.uid, !m.seen)

    fun delete(uid: Long) = launchLoading {
        val s = service ?: return@launchLoading; val f = folder
        withContext(Dispatchers.IO) { s.delete(f, uid) }
        messages = messages.filterNot { it.uid == uid }
        if (open?.summary?.uid == uid) closeMessage()
    }

    suspend fun attachment(index: Int): ByteArray {
        val s = service!!; val f = folder; val uid = open!!.summary.uid
        return withContext(Dispatchers.IO) { s.attachment(f, uid, index) }
    }

    // --- writing -----------------------------------------------------------------------------------------------------

    /** Loads a draft (including its attachments) so it can be edited again. */
    fun editDraft(uid: Long, onReady: (ch.imail.app.ui.screens.ComposeSeed) -> Unit) = launchLoading {
        val s = service ?: return@launchLoading; val f = folder
        val seed = withContext(Dispatchers.IO) {
            val b = s.message(f, uid).value
            val files = b.attachments.filter { !it.inline || it.contentId == null }.mapNotNull { a ->
                runCatching { OutgoingAttachment(a.fileName, a.mimeType, s.attachment(f, uid, a.index)) }.getOrNull()
            }
            ch.imail.app.ui.screens.ComposeSeed(
                to = b.to.joinToString(", ") { it.address }, cc = b.cc.joinToString(", ") { it.address },
                subject = b.summary.subject, body = b.text ?: b.html?.let { org.jsoup.Jsoup.parse(it).text() }.orEmpty(),
                inReplyTo = null, references = b.references, attachments = files, draftFolder = f, draftUid = uid,
            )
        }
        onReady(seed)
    }

    /** Saves/updates the draft on the server and returns its new UID. */
    suspend fun saveDraft(mail: OutgoingMail, replaceUid: Long?): Long? {
        val s = service ?: return null
        return try {
            withContext(Dispatchers.IO) { s.saveDraft(mail, replaceUid) }.also { if (inDrafts) loadMessages() }
        } catch (e: Exception) { error = friendly(e); replaceUid }
    }

    suspend fun discardDraft(folder: String, uid: Long) {
        val s = service ?: return
        runCatching { withContext(Dispatchers.IO) { s.delete(folder, uid) } }
        if (this.folder == folder) messages = messages.filterNot { it.uid == uid }
    }

    fun send(mail: OutgoingMail, draft: Pair<String, Long>?, onDone: () -> Unit, onFail: () -> Unit) {
        val s = service ?: return onFail()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { s.send(mail, draft) }
                if (draft != null && folder == draft.first) messages = messages.filterNot { it.uid == draft.second }
                onDone()
            } catch (e: Exception) { error = friendly(e); onFail() }
        }
    }

    private fun launchLoading(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        viewModelScope.launch {
            loading = true
            try { block() } catch (e: Exception) { error = friendly(e) } finally { loading = false }
        }
    }

    /** Never shows server banners or stack traces to the user. */
    private fun friendly(e: Exception): String = when {
        e is jakarta.mail.AuthenticationFailedException -> "Anmeldung fehlgeschlagen. Bitte Zugangsdaten prüfen bzw. Konto neu anmelden."
        e is javax.net.ssl.SSLException || e.cause is javax.net.ssl.SSLException -> "Sichere Verbindung nicht möglich (Zertifikat oder TLS ungültig)."
        e is java.net.UnknownHostException || e.cause is java.net.UnknownHostException -> "Server nicht gefunden."
        e is java.net.SocketTimeoutException || e is java.net.ConnectException || e.cause is java.net.ConnectException -> "Keine Verbindung zum Server."
        e is IllegalArgumentException -> e.message ?: "Ungültige Eingabe."
        e is ch.imail.core.mail.AttachmentTooLargeException -> "Der Anhang ist zu gross."
        e is IllegalStateException && account?.auth == AuthType.OAUTH2 -> e.message ?: "Anmeldung fehlgeschlagen."
        else -> "Vorgang fehlgeschlagen."
    }
}
