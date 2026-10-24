package ch.imail.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import ch.imail.app.BuildConfig
import ch.imail.core.mail.Account
import ch.imail.core.mail.AuthType
import ch.imail.core.mail.SecretProvider
import ch.imail.core.oauth.OAuthProvider
import ch.imail.core.oauth.OAuthProviders
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * OAuth2 sign-in (authorization code + PKCE in the system browser tab, via AppAuth) for providers that no longer allow passwords.
 * Only the refresh token state is stored (encrypted); access tokens are fetched on demand for every IMAP/SMTP connection.
 * Client IDs are public-client identifiers that you register with Google / Microsoft; see README.
 */
class OAuthManager(private val context: Context, private val store: CredentialStore) {
    private val refreshLock = Any()

    fun clientId(p: OAuthProvider): String = when (p.id) {
        "google" -> BuildConfig.GOOGLE_CLIENT_ID
        "microsoft" -> BuildConfig.MICROSOFT_CLIENT_ID
        else -> ""
    }

    fun isConfigured(p: OAuthProvider) = clientId(p).isNotBlank()

    private fun redirectUri(p: OAuthProvider): Uri = when (p.id) {
        "google" -> Uri.parse("com.googleusercontent.apps.${clientId(p).removeSuffix(".apps.googleusercontent.com")}:/oauth2redirect")
        else -> Uri.parse("ch.imail.app:/oauth2redirect")
    }

    fun authorizationIntent(p: OAuthProvider, service: AuthorizationService): Intent {
        val config = AuthorizationServiceConfiguration(Uri.parse(p.authorizationEndpoint), Uri.parse(p.tokenEndpoint))
        val request = AuthorizationRequest.Builder(config, clientId(p), ResponseTypeValues.CODE, redirectUri(p))
            .setScopes(p.scopes)
            .setPrompt("select_account") // AppAuth adds a random state and a PKCE S256 code challenge by default
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Exchanges the authorization result for tokens and returns the new account with its encrypted-at-rest secret. */
    suspend fun complete(p: OAuthProvider, data: Intent, service: AuthorizationService): Pair<Account, String> {
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) throw IllegalStateException(error?.errorDescription ?: "Anmeldung abgebrochen")
        val tokens = suspendCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { r, e ->
                if (r != null) cont.resume(r) else cont.resumeWithException(e ?: IllegalStateException("Token-Austausch fehlgeschlagen"))
            }
        }
        val email = tokens.idToken?.let { OAuthProviders.emailFromIdToken(it) }
            ?: throw IllegalStateException("E-Mail-Adresse konnte nicht ermittelt werden")
        val state = AuthState(response, tokens, null)
        val account = Account(
            email = email, username = email, imap = p.imap, smtp = p.smtp,
            auth = AuthType.OAUTH2, oauthProvider = p.id,
        )
        return account to state.jsonSerializeString()
    }

    /** Blocking provider of a fresh access token (refreshes via the stored refresh token when expired). Call off the main thread. */
    fun tokenProvider(accountId: String) = SecretProvider {
        synchronized(refreshLock) {
            val stored = store.get(accountId) ?: throw IllegalStateException("Konto nicht gefunden")
            val state = AuthState.jsonDeserialize(stored.secret)
            val service = AuthorizationService(context)
            try {
                val latch = CountDownLatch(1)
                var token: String? = null
                var failure: AuthorizationException? = null
                state.performActionWithFreshTokens(service) { access, _, ex -> token = access; failure = ex; latch.countDown() }
                if (!latch.await(30, TimeUnit.SECONDS)) throw java.net.SocketTimeoutException("Token-Aktualisierung zu langsam")
                failure?.let { throw jakarta.mail.AuthenticationFailedException("OAuth: ${it.errorDescription ?: it.error}") }
                store.updateSecret(accountId, state.jsonSerializeString())
                token ?: throw jakarta.mail.AuthenticationFailedException("OAuth: kein Token")
            } finally { service.dispose() }
        }
    }
}
