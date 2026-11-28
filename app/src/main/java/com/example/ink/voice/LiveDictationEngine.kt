package com.example.ink.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Pure-Kotlin incremental ("lazy loading") text buffer for live speech dictation.
 *
 * Ensures that spoken words appear progressively as the user speaks rather than dumping an
 * entire paragraph at once:
 * - Single-word partial updates render immediately with zero lag.
 * - Multi-word bursts from the speech engine are queued and revealed word-by-word (~50 ms cadence).
 * - ASR self-corrections update diverged prefix tokens cleanly without duplicating phrases.
 */
class LiveDictationBuffer(initialSeed: String = "") {

    private var committedPrefix: String = normalizeSpace(initialSeed)
    private var targetTokens: List<String> = emptyList()
    private var displayedTokens: MutableList<String> = mutableListOf()
    private var pendingCommitAfterReveal: Boolean = false

    fun reset(initialSeed: String = "") {
        committedPrefix = normalizeSpace(initialSeed)
        targetTokens = emptyList()
        displayedTokens.clear()
        pendingCommitAfterReveal = false
    }

    /**
     * Feeds a live partial hypothesis from [RecognitionListener.onPartialResults].
     * Advances at most one new word immediately and leaves remaining tail words queued for
     * progressive word-by-word reveal.
     */
    fun onPartialUtterance(rawPartial: String): String {
        val cleaned = normalizeSpace(rawPartial)
        if (cleaned.isEmpty()) return currentFullText()

        pendingCommitAfterReveal = false
        val nextTokens = cleaned.split(' ')
        targetTokens = nextTokens

        if (displayedTokens.isEmpty()) {
            displayedTokens.add(nextTokens.first())
            return currentFullText()
        }

        val commonLen = commonPrefixLength(displayedTokens, nextTokens)
        if (commonLen == displayedTokens.size) {
            if (nextTokens.size > displayedTokens.size) {
                displayedTokens.add(nextTokens[displayedTokens.size])
            }
        } else {
            // ASR revised an earlier partial word: snap cleanly to common prefix + revised word
            while (displayedTokens.size > commonLen) {
                displayedTokens.removeAt(displayedTokens.lastIndex)
            }
            if (commonLen < nextTokens.size) {
                displayedTokens.add(nextTokens[commonLen])
            }
        }
        return currentFullText()
    }

    /**
     * Feeds a finalized utterance from [RecognitionListener.onResults].
     * If new unrevealed words arrived in the final burst, queues them for progressive reveal
     * before committing into [committedPrefix].
     */
    fun onFinalUtterance(rawFinal: String): String {
        val cleaned = normalizeSpace(rawFinal)
        if (cleaned.isEmpty()) {
            flushPendingUtterance()
            return currentFullText()
        }
        val nextTokens = cleaned.split(' ')
        targetTokens = nextTokens

        val commonLen = commonPrefixLength(displayedTokens, nextTokens)
        if (commonLen == displayedTokens.size && nextTokens.size <= displayedTokens.size + 1) {
            // Everything (or at most 1 trailing word) is already on screen -> commit immediately
            committedPrefix = joinSegments(committedPrefix, cleaned)
            targetTokens = emptyList()
            displayedTokens.clear()
            pendingCommitAfterReveal = false
            return committedPrefix
        }

        // More than 1 new word arrived in final burst: reveal progressively, then commit
        while (displayedTokens.size > commonLen) {
            displayedTokens.removeAt(displayedTokens.lastIndex)
        }
        if (commonLen < nextTokens.size) {
            displayedTokens.add(nextTokens[commonLen])
        }
        pendingCommitAfterReveal = true
        return currentFullText()
    }

    /**
     * Reveals the next queued word (if any). Returns the updated text, or `null` when caught up.
     */
    fun stepRevealNextWord(): String? {
        if (displayedTokens.size >= targetTokens.size) {
            if (pendingCommitAfterReveal) {
                commitDisplayedIntoPrefix()
                return currentFullText()
            }
            return null
        }

        val commonLen = commonPrefixLength(displayedTokens, targetTokens)
        while (displayedTokens.size > commonLen) {
            displayedTokens.removeAt(displayedTokens.lastIndex)
        }
        if (commonLen < targetTokens.size) {
            displayedTokens.add(targetTokens[commonLen])
        }

        if (pendingCommitAfterReveal && displayedTokens.size >= targetTokens.size) {
            commitDisplayedIntoPrefix()
        }
        return currentFullText()
    }

    fun hasPendingWords(): Boolean {
        return displayedTokens.size < targetTokens.size || pendingCommitAfterReveal
    }

    /**
     * Immediately reveals and commits any queued words (used when stopping dictation or starting
     * a new utterance so no recognized text is ever lost).
     */
    fun flushPendingUtterance(): String {
        if (targetTokens.isNotEmpty()) {
            committedPrefix = joinSegments(committedPrefix, targetTokens.joinToString(" "))
        } else if (displayedTokens.isNotEmpty()) {
            committedPrefix = joinSegments(committedPrefix, displayedTokens.joinToString(" "))
        }
        targetTokens = emptyList()
        displayedTokens.clear()
        pendingCommitAfterReveal = false
        return committedPrefix
    }

    fun currentFullText(): String {
        if (displayedTokens.isEmpty()) return committedPrefix
        return joinSegments(committedPrefix, displayedTokens.joinToString(" "))
    }

    private fun commitDisplayedIntoPrefix() {
        if (targetTokens.isNotEmpty()) {
            committedPrefix = joinSegments(committedPrefix, targetTokens.joinToString(" "))
        }
        targetTokens = emptyList()
        displayedTokens.clear()
        pendingCommitAfterReveal = false
    }

    private fun commonPrefixLength(a: List<String>, b: List<String>): Int {
        val limit = minOf(a.size, b.size)
        var i = 0
        while (i < limit && a[i] == b[i]) {
            i++
        }
        return i
    }

    private fun joinSegments(prefix: String, tail: String): String {
        if (prefix.isEmpty()) return tail
        if (tail.isEmpty()) return prefix
        return "$prefix $tail"
    }

    private fun normalizeSpace(input: String): String {
        return input.trim().replace(MULTI_SPACE_REGEX, " ")
    }

    companion object {
        private val MULTI_SPACE_REGEX = Regex("\\s+")
    }
}

/**
 * Lifecycle-safe Android [SpeechRecognizer] wrapper that streams live partial dictation into
 * a target canvas text card as the user speaks.
 */
class LiveSpeechDictationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTextStreamed: (targetId: String, text: String) -> Unit,
    private val onPhraseCommitted: (targetId: String, text: String) -> Unit,
    private val onRmsChanged: (normalizedLevel: Float) -> Unit,
    private val onDictationStopped: (errorMessage: String?) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var buffer = LiveDictationBuffer()
    private var targetTextItemId: String? = null
    private var isListeningRequested = false
    private var consecutiveTimeouts = 0
    private var streamRevealJob: Job? = null
    private var restartJob: Job? = null

    fun start(targetId: String, initialSeed: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onDictationStopped("El dictado por voz no está disponible en este dispositivo.")
            return
        }

        stopInternal(notifyStopped = false)
        targetTextItemId = targetId
        buffer.reset(initialSeed)
        isListeningRequested = true
        consecutiveTimeouts = 0

        ensureRecognizer()
        startListeningCycle()
    }

    fun stop() {
        stopInternal(notifyStopped = true, errorMessage = null)
    }

    fun dispose() {
        stopInternal(notifyStopped = false)
        destroyRecognizer()
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val instance = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {
                consecutiveTimeouts = 0
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isListeningRequested) return
                // SpeechRecognizer rmsdB typically ranges from -2f (quiet) to 10f (loud)
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                onRmsChanged(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                onRmsChanged(0f)
            }

            override fun onError(error: Int) {
                if (!isListeningRequested) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        consecutiveTimeouts++
                        if (consecutiveTimeouts >= MAX_SILENT_TIMEOUTS) {
                            stopInternal(notifyStopped = true, errorMessage = null)
                        } else {
                            scheduleNextCycle()
                        }
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        stopInternal(
                            notifyStopped = true,
                            errorMessage = "Activa el permiso de micrófono para dictar en vivo."
                        )
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Brief pause before retrying if recognizer hadn't settled
                        scheduleNextCycle(delayMs = 240L)
                    }
                    else -> {
                        stopInternal(
                            notifyStopped = true,
                            errorMessage = mapErrorToSpanish(error)
                        )
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isListeningRequested) return
                val targetId = targetTextItemId ?: return
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (spoken.isNotBlank()) {
                    consecutiveTimeouts = 0
                    val updated = buffer.onFinalUtterance(spoken)
                    onTextStreamed(targetId, updated)
                    if (buffer.hasPendingWords()) {
                        launchWordRevealLoop(targetId, commitWhenDone = true)
                    } else {
                        onPhraseCommitted(targetId, updated)
                    }
                }
                if (isListeningRequested) {
                    scheduleNextCycle()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isListeningRequested) return
                val targetId = targetTextItemId ?: return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    consecutiveTimeouts = 0
                    val updated = buffer.onPartialUtterance(partial)
                    onTextStreamed(targetId, updated)
                    if (buffer.hasPendingWords()) {
                        launchWordRevealLoop(targetId, commitWhenDone = false)
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer = instance
    }

    private fun startListeningCycle() {
        if (!isListeningRequested) return
        val activeRecognizer = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
        }
        try {
            activeRecognizer.startListening(intent)
        } catch (_: SecurityException) {
            stopInternal(
                notifyStopped = true,
                errorMessage = "Activa el permiso de micrófono para dictar en vivo."
            )
        } catch (_: Exception) {
            stopInternal(
                notifyStopped = true,
                errorMessage = "No se pudo iniciar el dictado por voz."
            )
        }
    }

    private fun scheduleNextCycle(delayMs: Long = 120L) {
        restartJob?.cancel()
        restartJob = scope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            if (!isListeningRequested) return@launch
            // Ensure any pending final phrase is committed cleanly before the next phrase begins
            val targetId = targetTextItemId
            if (targetId != null && buffer.hasPendingWords()) {
                val flushed = buffer.flushPendingUtterance()
                onPhraseCommitted(targetId, flushed)
            }
            startListeningCycle()
        }
    }

    private fun launchWordRevealLoop(targetId: String, commitWhenDone: Boolean) {
        if (streamRevealJob?.isActive == true) return
        streamRevealJob = scope.launch(Dispatchers.Main.immediate) {
            while (isListeningRequested && buffer.hasPendingWords()) {
                delay(WORD_REVEAL_INTERVAL_MS)
                val nextText = buffer.stepRevealNextWord() ?: break
                onTextStreamed(targetId, nextText)
                if (!buffer.hasPendingWords() && commitWhenDone) {
                    onPhraseCommitted(targetId, nextText)
                }
            }
        }
    }

    private fun stopInternal(notifyStopped: Boolean, errorMessage: String? = null) {
        isListeningRequested = false
        restartJob?.cancel()
        restartJob = null
        streamRevealJob?.cancel()
        streamRevealJob = null

        val targetId = targetTextItemId
        if (targetId != null) {
            val finalText = buffer.flushPendingUtterance()
            if (finalText.isNotBlank()) {
                onPhraseCommitted(targetId, finalText)
            }
        }

        try {
            recognizer?.stopListening()
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        destroyRecognizer()
        onRmsChanged(0f)

        if (notifyStopped) {
            onDictationStopped(errorMessage)
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun mapErrorToSpanish(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Error al capturar audio del micrófono."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sin conexión para el reconocimiento de voz."
        SpeechRecognizer.ERROR_SERVER -> "El servicio de voz no respondió. Intenta de nuevo."
        SpeechRecognizer.ERROR_CLIENT -> "Dictado detenido."
        else -> "No se pudo continuar el dictado por voz."
    }

    companion object {
        private const val WORD_REVEAL_INTERVAL_MS = 52L
        private const val MAX_SILENT_TIMEOUTS = 3
    }
}
