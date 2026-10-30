package dev.belta.macropad.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.belta.macropad.model.Dictation
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.RAISED
import dev.belta.macropad.ui.Ui.Companion.RED
import dev.belta.macropad.ui.Ui.Companion.TEXT
import java.util.Locale

/**
 * Dictation for the computer (a Citrix session without a clipboard, say): what's said fills a
 * text box, spoken punctuation ("coma", "punto y aparte"…) as marks ([Dictation]); the box can be
 * edited, then Type sends it to the window in front ([onType], Enter after it if ticked). The
 * microphone keeps listening, phrase after phrase, until it's tapped off or the panel closes.
 * What's left untyped comes back next time ([onDraft]).
 */
class DictationPanel(
    private val ui: Ui, private val draft: String,
    private val onDraft: (String) -> Unit,
    private val onType: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(ui.context)
    private var listening = false
    private var typed = false

    private val field = EditText(ui.context).apply {
        setText(draft)
        setSelection(text.length)
        hint = "Speak, or type here"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 6
        maxLines = 12
        gravity = Gravity.TOP
        typeface = ui.fontBody
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        background = ui.roundRect(RAISED, ui.dp(12).toFloat())
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
    }
    /** What's being heard, before it's final. */
    private val partial = TextView(ui.context).apply {
        typeface = ui.fontHint
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(4))
    }
    private val mic = ui.chip("Listening", "mic", RED) { if (listening) stop() else listen() }
    private val enter = CheckBox(ui.context).apply {
        text = "Enter after"
        setTextColor(TEXT)
    }

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ui.context.packageName)

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { partial.text = "Listening…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial.text = it }
            }

            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { phrase ->
                    field.setText(Dictation.join(field.text.toString(), phrase))
                    field.setSelection(field.text.length)
                }
                partial.text = ""
                if (listening) start() // the next phrase
            }

            override fun onError(error: Int) {
                when (error) {
                    // Silence, or nothing understood: keep listening.
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (listening) start()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
                        if (listening) handler.postDelayed({ if (listening) start() }, 400)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stop("The microphone isn't allowed for the macropad")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        stop("Speech needs the internet (or Google's offline speech for this language)")
                    else -> stop("The microphone stopped ($error); tap it to go on")
                }
            }
        })
    }

    fun show() {
        val content = LinearLayout(ui.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(field)
            addView(partial)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(mic.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
                addView(ui.chip("Clear", "delete", null) { field.setText("") })
                addView(android.view.View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(enter)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(56)))
        }
        val dialog = ui.dialog()
            .setTitle("Dictate")
            .setView(content)
            .setPositiveButton("Type") { _, _ ->
                val text = field.text.toString().trimEnd()
                if (text.isEmpty()) return@setPositiveButton
                typed = true
                onType(text + if (enter.isChecked) "\n" else "")
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            listening = false
            handler.removeCallbacksAndMessages(null)
            recognizer.destroy()
            onDraft(if (typed) "" else field.text.toString())
        }
        dialog.show()
        dialog.window?.setLayout((ui.context.resources.displayMetrics.widthPixels * 0.7).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        listen()
    }

    private fun listen() {
        listening = true
        showMic()
        start()
    }

    private fun start() {
        runCatching { recognizer.cancel(); recognizer.startListening(intent) }
            .onFailure { stop("Speech isn't available: ${it.message}") }
    }

    private fun stop(why: String? = null) {
        listening = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer.cancel() }
        partial.text = why ?: ""
        showMic()
    }

    /** The mic chip: red "Listening" while on, a plain "Mic off" while not. */
    private fun showMic() {
        val ink = if (listening) Ui.inkOn(RED) else TEXT
        mic.background = ui.pressable(if (listening) RED else RAISED, ui.dp(12).toFloat())
        (mic.getChildAt(0) as android.widget.ImageView).imageTintList = android.content.res.ColorStateList.valueOf(ink)
        (mic.getChildAt(1) as TextView).apply {
            text = if (listening) "Listening" else "Mic off"
            setTextColor(ink)
        }
    }
}
