package com.example.radialkeyboard.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

class AudioManager(context: Context) {

    private val appContext  = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // Primary player — letter sounds, TTS cache playback
    private var player: MediaPlayer? = null

    // Separate player for short UI click sounds — never stops letter sounds
    private var clickPlayer: MediaPlayer? = null

    private var tts: TextToSpeech? = null
    private var ttsReady      = false
    private var isShuttingDown = false

    // True while a direct tts.speak() call is active (Play button readback)
    private var isDirectSpeaking = false

    // LRU cache: text-hash key → synthesised WAV file.
    private val cacheDir = File(appContext.cacheDir, "tts_cache").also { it.mkdirs() }
    private val cache = object : LinkedHashMap<String, File>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, File>): Boolean {
            if (size > MAX_CACHE) { try { eldest.value.delete() } catch (_: Exception) {}; return true }
            return false
        }
    }

    // Per-utterance completion callbacks
    private val completionCallbacks = mutableMapOf<String, () -> Unit>()

    // Per-utterance word-cursor callbacks (only populated by speakForPlayback)
    private val cursorCallbacks = mutableMapOf<String, (Int) -> Unit>()

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}

        // Word boundary — fires during tts.speak() utterances (not synthesizeToFile)
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val key = utteranceId ?: return
            mainHandler.post { cursorCallbacks[key]?.invoke(start) }
        }

        override fun onDone(id: String?) {
            val key = id ?: return
            mainHandler.post {
                if (key.startsWith("pb_")) {
                    isDirectSpeaking = false
                    cursorCallbacks.remove(key)
                } else {
                    val file = File(cacheDir, "$key.wav")
                    if (file.exists()) { cache[key] = file; playFile(file) }
                }
                completionCallbacks.remove(key)?.invoke()
            }
        }

        override fun onError(id: String?) {
            val key = id ?: return
            mainHandler.post {
                if (key.startsWith("pb_")) isDirectSpeaking = false
                completionCallbacks.remove(key)?.invoke()
                cursorCallbacks.remove(key)
            }
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            if (isShuttingDown) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ml", "IN")
                tts?.setSpeechRate(0.9f)
                tts?.setOnUtteranceProgressListener(utteranceListener)
                ttsReady = true
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun playInstructions() {
        stopCurrent()
        try {
            val afd = appContext.assets.openFd("letter_sounds/instructions.mp3")
            playFd(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
        } catch (_: Exception) {}
    }

    fun playLetterSound(label: String) {
        stopCurrent()
        val path = "letter_sounds/${charToFilename(label)}"
        try {
            val afd = appContext.assets.openFd(path)
            playFd(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
        } catch (_: Exception) {}
    }

    /** Short activation click when the wheel appears — separate track from letter sounds. */
    fun playActivationSound() = playClickAsset("button_sounds/activate.wav")

    /** Short UI hover click — inner and outer rings have distinct sounds. */
    fun playClickSound(isInner: Boolean) =
        playClickAsset(if (isInner) "button_sounds/click_inner.wav" else "button_sounds/click_outer.wav")

    /** Speak text via TTS cache (post-commit readout, suggestion navigation). */
    fun speakText(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) { onDone(); return }
        stopCurrent()
        val key = text.hashCode().toString()
        cache[key]?.takeIf { it.exists() }?.let { playFile(it, onDone); return }
        if (!ttsReady) { onDone(); return }
        completionCallbacks[key] = onDone
        tts?.synthesizeToFile(text, null, File(cacheDir, "$key.wav"), key)
    }

    /**
     * Speak text directly via tts.speak() for the Play button readback.
     * onCursor fires at each word boundary with the char index so the UI
     * can advance a cursor. onDone fires when playback finishes or errors.
     */
    fun speakForPlayback(text: String, onCursor: (Int) -> Unit = {}, onDone: () -> Unit = {}) {
        stop()
        if (text.isBlank()) { onDone(); return }
        if (!ttsReady) { onDone(); return }
        val key = "pb_${System.currentTimeMillis()}"
        completionCallbacks[key] = onDone
        cursorCallbacks[key] = onCursor
        isDirectSpeaking = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, key)
    }

    fun stop() {
        completionCallbacks.clear()
        cursorCallbacks.clear()
        if (isDirectSpeaking) {
            isDirectSpeaking = false
            try { tts?.stop() } catch (_: Exception) {}
        }
        stopCurrent()
    }

    fun shutdown() {
        isShuttingDown = true
        ttsReady = false
        isDirectSpeaking = false
        completionCallbacks.clear()
        cursorCallbacks.clear()
        try { tts?.stop()        } catch (_: Exception) {}
        stopCurrent()
        try { clickPlayer?.release() } catch (_: Exception) {}
        clickPlayer = null
        try { tts?.shutdown()    } catch (_: Exception) {}
        tts = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun playFile(file: File, onDone: () -> Unit = {}) {
        if (!file.exists()) { onDone(); return }
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { it.release(); mainHandler.post(onDone) }
            stopCurrent()
            player = mp
            mp.start()
        } catch (_: Exception) { onDone() }
    }

    private fun playFd(fd: java.io.FileDescriptor, start: Long, length: Long) {
        try {
            val mp = MediaPlayer()
            mp.setDataSource(fd, start, length)
            mp.prepare()
            mp.setOnCompletionListener { it.release() }
            stopCurrent()
            player = mp
            mp.start()
        } catch (_: Exception) {}
    }

    private fun playClickAsset(path: String) {
        try {
            val afd = appContext.assets.openFd(path)
            val mp  = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.prepare()
            mp.setOnCompletionListener { it.release(); if (clickPlayer == mp) clickPlayer = null }
            try { clickPlayer?.stop()    } catch (_: Exception) {}
            try { clickPlayer?.release() } catch (_: Exception) {}
            clickPlayer = mp
            mp.start()
        } catch (_: Exception) {}
    }

    private fun stopCurrent() {
        try { player?.stop()    } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun charToFilename(label: String): String =
        label.codePoints().toArray().joinToString("_") { "%04X".format(it) } + ".mp3"

    companion object {
        private const val MAX_CACHE = 60
    }
}
