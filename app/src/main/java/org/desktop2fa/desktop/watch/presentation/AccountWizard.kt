package org.desktop2fa.desktop.watch.presentation

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text

@Composable
fun AccountWizard(
    state: AccountWizardState,
    onEdit: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var revealSecret by remember { mutableStateOf(false) }
    val submit = {
        keyboard?.hide()
        focus.clearFocus(force = true)
        if (!state.isSaving) onSubmit()
    }
    BackHandler {
        if (!state.isSaving) {
            keyboard?.hide()
            focus.clearFocus(force = true)
            if (state.step == AccountStep.SERVICE) onCancel() else onBack()
        }
    }

    key(state.step) {
        SingleFieldStep(
            heading = state.step.heading,
            progress = "${state.step.ordinal + 1} of 3",
            value = state.value,
            placeholder = when (state.step) {
                AccountStep.SERVICE -> "e.g. GitHub"
                AccountStep.ACCOUNT -> "e.g. demo@example.com"
                AccountStep.SECRET -> "Base32 setup key"
            },
            onEdit = onEdit,
            error = state.error,
            enabled = !state.isSaving,
            secret = state.step == AccountStep.SECRET,
            keyboardType = when (state.step) {
                AccountStep.SERVICE -> KeyboardType.Ascii
                AccountStep.ACCOUNT -> KeyboardType.Email
                AccountStep.SECRET -> if (revealSecret) KeyboardType.Ascii else KeyboardType.Password
            },
            revealSecret = revealSecret,
            onToggleSecret = { revealSecret = !revealSecret },
            submitLabel = if (state.isSaving) "Saving…"
                else if (state.step == AccountStep.SECRET) "Save Account" else "Next",
            onSubmit = submit,
            onBack = if (state.step != AccountStep.SERVICE) ({
                keyboard?.hide()
                focus.clearFocus(force = true)
                onBack()
            }) else null,
            onCancel = {
                keyboard?.hide()
                focus.clearFocus(force = true)
                onCancel()
            }
        )
    }
}

/** One scrollable layout root; IME insets shrink the viewport around the active field. */
@Composable
private fun SingleFieldStep(
    heading: String,
    progress: String,
    value: String,
    placeholder: String,
    onEdit: (String) -> Unit,
    error: String?,
    enabled: Boolean,
    secret: Boolean,
    keyboardType: KeyboardType,
    revealSecret: Boolean,
    onToggleSecret: () -> Unit,
    submitLabel: String,
    onSubmit: () -> Unit,
    onBack: (() -> Unit)?,
    onCancel: () -> Unit
) {
    val errorRequester = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(error, imeBottom) {
        if (error != null) {
            withFrameNanos { }
            errorRequester.bringIntoView()
        }
    }
    WatchTextInput(heading) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A))
                .imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(progress, color = Color(0xFF9CA3AF))
            Text(heading, color = Color.White, textAlign = TextAlign.Center)
            OutlinedTextField(
                value = value,
                onValueChange = onEdit,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(placeholder, color = Color(0xFF9CA3AF), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                },
                trailingIcon = if (secret) ({
                    TextButton(onClick = onToggleSecret, enabled = enabled) {
                        Text(if (revealSecret) "Hide" else "Show", color = Color(0xFF93C5FD))
                    }
                }) else null,
                enabled = enabled,
                singleLine = true,
                isError = error != null,
                visualTransformation = if (secret && !revealSecret) PasswordVisualTransformation()
                    else VisualTransformation.None,
                // WatchTextInput also disables suggestions at the Android EditorInfo boundary.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = keyboardType,
                    imeAction = if (secret) ImeAction.Done else ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { onSubmit() }, onDone = { onSubmit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    errorTextColor = Color.White,
                    disabledTextColor = Color(0xFF9CA3AF),
                    focusedBorderColor = Color(0xFF3B82F6),
                    unfocusedBorderColor = Color(0xFF9CA3AF)
                )
            )
            if (error != null) {
                Text(error, color = Color(0xFFFCA5A5), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(errorRequester)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
            Button(onClick = onSubmit, enabled = enabled) { Text(submitLabel) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) {
                    TextButton(onClick = onBack, enabled = enabled) { Text("Back", color = Color.White) }
                }
                TextButton(onClick = onCancel, enabled = enabled) { Text("Cancel", color = Color.White) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Keep the step title available to IMEs that use their own full-screen editor. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WatchTextInput(heading: String, content: @Composable () -> Unit) {
    val interceptor = remember(heading) {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(PlatformTextInputMethodRequest { attributes ->
                val connection = request.createInputConnection(attributes)
                attributes.hintText = heading
                attributes.fieldName = heading
                attributes.imeOptions = attributes.imeOptions or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                attributes.inputType = (attributes.inputType and
                    (InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                        InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE).inv()) or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                connection
            })
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}
