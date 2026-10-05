package com.mamre.billing.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mamre.billing.data.api.FakeCredentials
import com.mamre.billing.data.startup.AppStartup
import com.mamre.billing.domain.auth.AreaState
import com.mamre.billing.ui.EntryGate
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.MamreLogo
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.theme.Spacing
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DEBUG ONLY. The demo login in front of the app (the release build has no gate and starts at the Sales Home). The two
 * demo accounts are in FakeCredentials. Signing in opens the area the account belongs to; Log out in Settings closes it.
 * It is a stand-in for nothing real: there is no session, no token and no server.
 */
@Singleton
class DemoLoginGate @Inject constructor(
    private val areaState: AreaState,
    private val startup: AppStartup,
) : EntryGate {
    override val route: String = "login"

    private val _open = MutableStateFlow(false)
    override val open: StateFlow<Boolean> = _open.asStateFlow()

    /** False while the sample data is still being put into the database in the background. */
    val ready: StateFlow<Boolean> get() = startup.extrasDone

    /** Returns false for anything but one of the demo accounts, and for every account until the sample data is ready. */
    fun signIn(username: String, password: String): Boolean {
        if (!ready.value) return false
        val account = FakeCredentials.find(username.trim(), password) ?: return false
        areaState.open(account.area)
        _open.value = true
        return true
    }

    override fun register(builder: NavGraphBuilder) {
        builder.composable(route) {
            val isReady by ready.collectAsStateWithLifecycle()
            DemoLoginScreen(ready = isReady, onSignIn = ::signIn)
        }
    }

    override fun close() {
        _open.value = false
    }
}

@Composable
fun DemoLoginScreen(ready: Boolean, onSignIn: (String, String) -> Boolean) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            MamreLogo(size = 96.dp, modifier = Modifier.padding(bottom = Spacing.sm))
            Text("Demo login", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                "Debug build only. Sample data.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LabeledTextField(
            label = "Username",
            value = username,
            onValueChange = { username = it; error = null },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        LabeledTextField(
            label = "Password",
            value = password,
            onValueChange = { password = it; error = null },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (!ready) {
            Text("Preparing the sample data. This takes a moment on the first start.", style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        PrimaryButton(
            text = "Sign in",
            onClick = {
                if (!onSignIn(username, password)) {
                    error = "Wrong username or password."
                    password = ""
                }
            },
            enabled = ready && username.isNotBlank() && password.isNotEmpty(),
        )
    }
}
