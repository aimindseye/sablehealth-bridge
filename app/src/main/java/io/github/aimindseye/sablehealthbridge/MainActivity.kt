package io.github.aimindseye.sablehealthbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.SLEEP_SCOPE
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

internal data class Preview(
    val session: SleepSession,
    val decision: Decision,
)

internal data class ScreenState(
    val connected: Boolean = false,
    val permissions: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Connect the Google account used by Fitbit Air.",
    val preview: Preview? = null,
    val sessionIndex: Int = 0,
    val sessionCount: Int = 0,
    val heldCount: Int = 0,
    val diagnostics: String = "",
)

class MainActivity : ComponentActivity() {
    private val health by lazy { ConnectSleep(this) }
    private val auth by lazy { Identity.getAuthorizationClient(this) }
    private val controller: BridgeController by lazy { BridgeController(lifecycleScope, services) }
    private val consent =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                runCatching { auth.getAuthorizationResultFromIntent(requireNotNull(result.data)) }
                    .onSuccess(::acceptAuthorization)
                    .onFailure { controller.authorizationFailed("Google authorization not completed") }
            } else {
                controller.authorizationFailed("Google authorization cancelled")
            }
        }
    private val healthConsent: ActivityResultLauncher<Set<String>> =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { controller.permissionsResult(it.containsAll(SLEEP_PERMISSIONS)) }

    private val services: BridgeServices =
        object : BridgeServices {
            override fun authorize() = launchAuthorization()

            override fun healthAvailable(): Boolean = health.available()

            override fun requestPermissions() = healthConsent.launch(SLEEP_PERMISSIONS)

            override suspend fun batch(access: String): SleepBatch =
                withContext(Dispatchers.IO) {
                    retrySleepRead {
                        readSleepBatch(Instant.now(), ZoneId.systemDefault()) { url -> fetchPage(url, access) }
                    }
                }

            override suspend fun inspect(session: SleepSession): Decision = health.inspect(session)

            override suspend fun apply(
                session: SleepSession,
                decision: Decision,
            ): String = health.apply(session, decision)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BridgeTheme {
                BridgeScreen(
                    controller.screen,
                    ScreenActions(
                        controller::authorize,
                        controller::requestHealth,
                        controller::preview,
                        controller::applyPreview,
                        { controller.forget() },
                        about = { startActivity(Intent(this, PrivacyActivity::class.java)) },
                        previous = { controller.selectRelative(-1) },
                        next = { controller.selectRelative(1) },
                    ),
                )
            }
        }
    }

    private fun launchAuthorization() {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SLEEP_SCOPE))).build()
        auth
            .authorize(request)
            .addOnSuccessListener(this) { result -> resolveAuthorization(result) }
            .addOnFailureListener(this) { controller.authorizationFailed() }
    }

    private fun resolveAuthorization(result: AuthorizationResult) {
        if (result.hasResolution()) {
            val pending = result.pendingIntent
            if (pending == null) {
                controller.authorizationFailed("Authorization unavailable")
            } else {
                runCatching { consent.launch(IntentSenderRequest.Builder(pending).build()) }
                    .onFailure { controller.authorizationFailed("Unable to open authorization") }
            }
        } else {
            acceptAuthorization(result)
        }
    }

    private fun acceptAuthorization(result: AuthorizationResult) {
        controller.acceptAuthorization(result.grantedScopes.orEmpty(), result.accessToken)
    }

    override fun onDestroy() {
        controller.forget(force = true)
        super.onDestroy()
    }
}

class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BridgeTheme { PrivacyScreen { finish() } } }
    }
}
