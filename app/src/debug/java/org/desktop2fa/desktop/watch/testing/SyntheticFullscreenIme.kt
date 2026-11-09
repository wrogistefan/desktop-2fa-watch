package org.desktop2fa.desktop.watch.testing

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/** Disposable instrumented-test IME. Always uses Android's native extract editor. */
class SyntheticFullscreenIme : InputMethodService() {
    override fun onEvaluateFullscreenMode(): Boolean = true
    override fun onEvaluateInputViewShown(): Boolean = true
    // Exercise providers that ignore the app's NO_EXTRACT_UI hint, using the
    // framework's real extract editor and actions rather than a mock callback.
    override fun onUpdateExtractingVisibility(info: EditorInfo?) { setExtractViewShown(true) }
    override fun onCreateInputView(): View = TextView(this).apply { text = "Synthetic keyboard" }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        fullscreen = isFullscreenMode
        active = true
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        active = false
        super.onFinishInputView(finishingInput)
    }
    companion object {
        @Volatile var active = false
        @Volatile var fullscreen = false
    }
}
