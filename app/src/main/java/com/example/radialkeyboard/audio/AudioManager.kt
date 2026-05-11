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

    private var player: MediaPlayer? = null

    private var tts: TextToSpeech? = null
    private var ttsReady      = false
    private var isShuttingDown = false

    // LRU cache: text-hash key → synthesised WAV file.
    // All reads/writes happen on the main thread (via mainHandler.post), so no
    // external synchronisation is needed.
    private val cacheDir = File(appContext.cacheDir, "tts_cache").also { it.mkdirs() }
    private val cache = object : LinkedHashMap<String, File>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, File>): Boolean {
            if (size > MAX_CACHE) { try { eldest.value.delete() } catch (_: Exception) {}; return true }
            return false
        }
    }

    // Single persistent listener — set once after TTS init, never replaced.
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) {
            val key = id ?: return
            mainHandler.post {
                val file = File(cacheDir, "$key.wav")
                if (file.exists()) {
                    cache[key] = file
                    playFile(file)
                }
            }
        }
        override fun onError(id: String?) {}
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

    // Play the app-open instruction clip (instructions.mp3).
    fun playInstructions() {
        stopCurrent()
        try {
            val afd = appContext.assets.openFd("letter_sounds/instructions.mp3")
            playFd(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
        } catch (_: Exception) {}
    }

    // Play the pre-recorded Shobhana clip for a letter/number/symbol label.
    // Stops any currently playing audio first. Falls back silently if no asset exists.
    fun playLetterSound(label: String) {
        stopCurrent()
        val path = "letter_sounds/${charToFilename(label)}"
        try {
            val afd = appContext.assets.openFd(path)
            playFd(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
        } catch (_: Exception) { /* no clip for this label */ }
    }

    // Speak arbitrary text using the TTS cache.
    // Cache hit → plays instantly. Cache miss → synthesises to file, caches, then plays.
    fun speakText(text: String) {
        if (text.isBlank()) return
        val key = text.hashCode().toString()

        // Cache hit — play immediately on main thread
        cache[key]?.takeIf { it.exists() }?.let { playFile(it); return }

        if (!ttsReady) return
        val outFile = File(cacheDir, "$key.wav")
        // utteranceListener handles playback once synthesis is done
        tts?.synthesizeToFile(text, null, outFile, key)
    }

    fun stop() = stopCurrent()

    fun shutdown() {
        isShuttingDown = true
        ttsReady = false
        stopCurrent()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun playFile(file: File) {
        if (!file.exists()) return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { it.release() }
            stopCurrent()
            player = mp
            mp.start()
        } catch (_: Exception) {}
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

    private fun stopCurrent() {
        try { player?.stop()    } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    // Mirrors generate_letter_sounds.py char_to_filename()
    private fun charToFilename(label: String): String =
        label.codePoints().toArray().joinToString("_") { "%04X".format(it) } + ".mp3"

    companion object {
        private const val MAX_CACHE = 60
    }
}
