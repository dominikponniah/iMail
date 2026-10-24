package ch.imail.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ch.imail.app.ui.components.NavText
import ch.imail.app.ui.screens.MailApp
import ch.imail.app.ui.screens.SetupScreen
import ch.imail.app.ui.theme.IosTheme
import ch.imail.app.ui.theme.IosType
import ch.imail.app.ui.theme.LocalIos
import ch.imail.core.oauth.OAuthProvider
import net.openid.appauth.AuthorizationService

class MainActivity : FragmentActivity() {
    private val vm: MailViewModel by viewModels()
    private var locked by mutableStateOf(false)

    private var authService: AuthorizationService? = null
    private var pendingProvider: OAuthProvider? = null
    private val oauthResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val p = pendingProvider ?: return@registerForActivityResult
        val data = result.data
        val service = authService
        if (data != null && service != null) vm.completeOAuth(p, data, service) else vm.error = "Anmeldung abgebrochen."
    }

    private fun startOAuth(p: OAuthProvider) {
        pendingProvider = p
        val service = authService ?: AuthorizationService(this).also { authService = it }
        oauthResult.launch(vm.authorizationIntent(p, service))
    }

    override fun onDestroy() {
        authService?.dispose()
        super.onDestroy()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ImailApp
        locked = app.settings.appLockEnabled
        applySecureFlag(app.settings.appLockEnabled)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            IosTheme {
                val size = calculateWindowSizeClass(this)
                Box(Modifier.fillMaxSize().background(LocalIos.current.background).systemBarsPadding()) {
                    when {
                        locked -> LockScreen()
                        vm.account == null -> SetupScreen(vm, ::startOAuth, onCancel = null)
                        vm.addingAccount -> SetupScreen(vm, ::startOAuth, onCancel = { vm.addingAccount = false })
                        else -> MailApp(vm, size.widthSizeClass, onToggleLock = { toggleLock(it) }, lockEnabled = app.settings.appLockEnabled)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if ((application as ImailApp).settings.appLockEnabled) locked = true
    }

    override fun onStart() {
        super.onStart()
        if (vm.account != null) vm.flushPending()
        if (locked) authenticate()
    }

    @androidx.compose.runtime.Composable
    private fun LockScreen() {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(getString(R.string.unlock_title), style = IosType.title3, color = LocalIos.current.label)
            NavText("Entsperren") { authenticate() }
        }
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Without any device lock the app lock cannot protect anything; keep it locked instead of failing open.
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked = false }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.unlock_title)).setSubtitle(getString(R.string.unlock_subtitle))
                .setAllowedAuthenticators(authenticators).build(),
        )
    }

    private fun toggleLock(enabled: Boolean) {
        (application as ImailApp).settings.appLockEnabled = enabled
        applySecureFlag(enabled)
    }

    /** With the app lock on, the window is hidden from screenshots and the recent-apps preview. */
    private fun applySecureFlag(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
