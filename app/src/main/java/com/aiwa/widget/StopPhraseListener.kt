package com.aiwa.widget
import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

private val STOP_PHRASE_REGEX = Regex("""\bc['\s]?est\s+bon,?\s+vas[\s-]?y\b[!.\s]*$""", RegexOption.IGNORE_CASE)

private fun stripStopPhrase(text: String): String? {
    val match = STOP_PHRASE_REGEX.find(text) ?: return null
    return text.substring(0, match.range.first).trim()
}

/**
 * Reported live, twice, insistently: "le micro enregistre et envoie
 * lorsqu'il entend 'c'est bon vas-y'" — NOT as soon as the speaker
 * pauses to think, which is what the previous implementation actually
 * did: Android's one-shot RecognizerIntent (ACTION_RECOGNIZE_SPEECH via
 * a launched Activity) returns its result the moment ITS OWN internal
 * silence-detection decides you've stopped talking — there is no way
 * to tell that API "keep listening, I'm not done". This wraps the raw
 * SpeechRecognizer API instead: every time a listening pass ends
 * (silence, timeout, or a real result with no stop phrase in it), it
 * just restarts listening and keeps accumulating, until a pass's own
 * text actually contains the stop phrase.
 *
 * HONEST LIMIT: raw SpeechRecognizer has no default visible dialog the
 * way the one-shot RecognizerIntent Activity did (that dialog is
 * Google's own app's UI, not something this API provides) — a caller
 * MUST show its own "still listening" indicator, or the user has no
 * way to know whether they're still being heard between passes.
 */
class StopPhraseListener(
    context: Context,
    private val onFinalText: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onGiveUp: () -> Unit,
) : RecognitionListener {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private val accumulated = StringBuilder()
    private var consecutiveSilentPasses = 0
    private var stopped = false

    init {
        recognizer.setRecognitionListener(this)
    }

    fun start() {
        if (stopped) return
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    fun cancel() {
        stopped = true
        recognizer.destroy()
    }

    private fun finishWith(text: String) {
        stopped = true
        recognizer.destroy()
        if (text.isNotBlank()) onFinalText(text) else onGiveUp()
    }

    override fun onResults(results: Bundle) {
        if (stopped) return
        val heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        val beforeStopPhrase = stripStopPhrase(heard)
        if (beforeStopPhrase != null) {
            if (accumulated.isNotEmpty() && beforeStopPhrase.isNotEmpty()) accumulated.append(" ")
            accumulated.append(beforeStopPhrase)
            finishWith(accumulated.toString().trim())
            return
        }
        if (heard.isNotBlank()) {
            consecutiveSilentPasses = 0
            if (accumulated.isNotEmpty()) accumulated.append(" ")
            accumulated.append(heard)
            onPartial(accumulated.toString())
        } else {
            consecutiveSilentPasses++
        }
        // Three fully silent passes in a row (no speech at all, not just
        // "no stop phrase yet") means the user walked away or gave up —
        // without this, a listener that's never told the stop phrase
        // would otherwise restart itself forever.
        if (consecutiveSilentPasses >= 3) {
            finishWith(accumulated.toString().trim())
        } else {
            start()
        }
    }

    override fun onPartialResults(partialResults: Bundle) {
        if (stopped) return
        val partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        val combined = (accumulated.toString() + " " + partial).trim()
        onPartial(combined)
    }

    override fun onError(error: Int) {
        if (stopped) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveSilentPasses++
                if (consecutiveSilentPasses >= 3) finishWith(accumulated.toString().trim()) else start()
            }
            else -> finishWith(accumulated.toString().trim())
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
