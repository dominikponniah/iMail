package ch.imail.core.mail

import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Store
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import org.eclipse.angus.mail.imap.IMAPFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-hosted "push": keeps an IMAP IDLE connection to the inbox open and reports new messages.
 * [run] blocks until [stop] is called; connection loss is retried with exponential backoff.
 */
class ImapIdleWatcher internal constructor(
    private val client: ImapClient,
    private val folderName: String = "INBOX",
    private val onNewMail: (List<MessageSummary>) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val onConnected: () -> Unit = {},
) {
    constructor(client: ImapClient, onNewMail: (List<MessageSummary>) -> Unit, onError: (Throwable) -> Unit = {}) :
        this(client, "INBOX", onNewMail, onError)

    private val stopped = AtomicBoolean(false)
    @Volatile private var store: Store? = null

    fun stop() {
        stopped.set(true)
        runCatching { store?.close() }
    }

    fun run() {
        var backoffMs = 2_000L
        while (!stopped.get()) {
            try {
                idleOnce { backoffMs = 2_000L }
            } catch (t: Throwable) {
                if (stopped.get()) return
                onError(t)
            }
            if (stopped.get()) return
            Thread.sleep(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(300_000L)
        }
    }

    private fun idleOnce(onReady: () -> Unit) {
        val s = client.connect().also { store = it }
        val folder = s.getFolder(folderName) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        folder.addMessageCountListener(object : MessageCountAdapter() {
            override fun messagesAdded(e: MessageCountEvent) {
                val msgs: Array<Message> = e.messages
                runCatching { client.prefetch(folder, msgs) }
                val list = msgs.map { client.toSummary(folder, it) }.filter { !it.seen }
                if (list.isNotEmpty()) onNewMail(list)
            }
        })
        // Servers drop IDLE after ~30 min; touching the folder from another thread ends IDLE so we re-issue it.
        val keepAlive = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "imail-idle-keepalive").apply { isDaemon = true } }
        keepAlive.scheduleWithFixedDelay({ runCatching { folder.messageCount } }, 10, 10, TimeUnit.MINUTES)
        try {
            onReady()
            onConnected()
            while (!stopped.get() && s.isConnected) folder.idle()
        } finally {
            keepAlive.shutdownNow()
            runCatching { s.close() }
        }
    }
}
