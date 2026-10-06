package net.atlasauth.atlas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Prebuilt Jetpack Compose UI for Atlas — the native peers of `@atlas/js`'s
 * `<SignIn>` and `<UserButton>`. They drive the same [SignInFlow] /
 * [AtlasSessionState] the headless API exposes, so an app can drop them in for
 * the common case and still reach for the flow driver when it needs its own UI.
 *
 * These composables are deliberately unstyled beyond Material 3 defaults; pass a
 * [Modifier] to place and size them. The screens compile against Compose and are
 * driven by the unit-tested flow driver underneath — on-device rendering /
 * ceremony is verified in app QA, not here.
 */

/**
 * Remember an [AtlasSessionState] bound to [client] and kick off the first
 * [AtlasSessionState.reload]. Collect its [AtlasSessionState.status] to render
 * reactively:
 *
 * ```
 * val session = rememberAtlasSessionState(atlas)
 * when (val s = session.status.collectAsState().value) {
 *     is AtlasSessionStatus.SignedIn -> Home(s.user)
 *     AtlasSessionStatus.SignedOut   -> AtlasSignIn(atlas) { session.refresh() }
 *     AtlasSessionStatus.Loading     -> Splash()
 * }
 * ```
 */
@Composable
fun rememberAtlasSessionState(client: AtlasClient): AtlasSessionState {
    val scope = rememberCoroutineScope()
    val state = remember(client) { AtlasSessionState(client, scope) }
    LaunchedEffect(state) { state.reload() }
    return state
}

/**
 * A prebuilt sign-in screen driving a [SignInFlow]: identifier → password (or an
 * emailed code) → second factor → done. On completion it persists the session and
 * calls [onSignedIn] with the user. It renders whatever the server's current step
 * demands and never picks a step itself.
 *
 * @param client the Atlas client the flow runs on.
 * @param onSignedIn invoked with the signed-in user once the flow completes.
 * @param modifier layout modifier for the root column.
 */
@Composable
fun AtlasSignIn(
    client: AtlasClient,
    onSignedIn: (AtlasUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val flow = remember(client) { client.signInFlow() }

    var step by remember { mutableStateOf<FlowStep>(FlowStep.CollectIdentifier) }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var usingEmailCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Run a flow step, map a thrown AtlasException to the inline error, and —
    // when the attempt reaches `complete` — finish into a session.
    fun run(block: suspend () -> FlowStep) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val next = block()
                step = next
                if (next is FlowStep.Done) {
                    onSignedIn(flow.complete())
                }
            } catch (e: AtlasException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (step) {
            is FlowStep.CollectIdentifier -> {
                Text("Sign in")
                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { run { flow.create(identifier.trim()) } },
                    enabled = !busy && identifier.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }
            }

            is FlowStep.CollectFirstFactor -> {
                if (usingEmailCode) {
                    Text("Enter the code we emailed you")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { run { flow.attemptEmailCode(code.trim()) } },
                        enabled = !busy && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Verify") }
                } else {
                    Text("Enter your password")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { run { flow.attemptPassword(password) } },
                        enabled = !busy && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sign in") }
                    TextButton(
                        onClick = { run { usingEmailCode = true; flow.prepareEmailCode() } },
                        enabled = !busy,
                    ) { Text("Email me a code instead") }
                }
            }

            is FlowStep.CollectEmailCode -> {
                Text("Enter the code we emailed you")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { run { flow.attemptEmailCode(code.trim()) } },
                    enabled = !busy && code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Verify") }
            }

            is FlowStep.CollectSecondFactor -> {
                Text("Two-step verification")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Authentication code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { run { flow.attemptSecondFactor(code.trim()) } },
                    enabled = !busy && code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Verify") }
            }

            is FlowStep.Done -> Text("Signed in.")

            else -> Text("This sign-in step needs the full web flow or an app update.")
        }

        if (busy) {
            CircularProgressIndicator()
        }
        error?.let { Text(it) }
    }
}

/**
 * A prebuilt account button: shows the signed-in user and a sign-out action, or a
 * prompt when signed out. Collects the [state]'s status, so it re-renders when the
 * session changes.
 *
 * @param state the observable session (see [rememberAtlasSessionState]).
 * @param onSignIn tapped when signed out (open your sign-in screen).
 * @param modifier layout modifier for the root row.
 */
@Composable
fun AtlasUserButton(
    state: AtlasSessionState,
    onSignIn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val status by state.status.collectAsState()

    Row(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val s = status) {
            is AtlasSessionStatus.SignedIn -> {
                Text(displayName(s.user))
                TextButton(onClick = { scope.launch { state.signOut() } }) { Text("Sign out") }
            }
            AtlasSessionStatus.SignedOut -> {
                Text("Not signed in")
                TextButton(onClick = onSignIn) { Text("Sign in") }
            }
            AtlasSessionStatus.Loading -> {
                Text("Loading…")
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

/** A human label for a user — full name, else username, else the id. */
internal fun displayName(user: AtlasUser): String {
    val name = listOfNotNull(user.firstName, user.lastName).joinToString(" ").trim()
    return when {
        name.isNotEmpty() -> name
        !user.username.isNullOrBlank() -> user.username
        else -> user.id
    }
}
