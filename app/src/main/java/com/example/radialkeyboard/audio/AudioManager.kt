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
    private var ttsReady = false

    // LRU cache: text hash → synthesised WAV file
    private val cacheDir = File(appContext.cacheDir, "tts_cache").also { it.mkdirs() }
    private val cache = object : LinkedHashMap<String, File>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, File>): Boolean {
            if (size > MAX_CACHE) { eldest.value.delete(); return true }
            return false
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language   = Locale("ml", "IN")
                tts?.setSpeechRate(0.9f)
                ttsReady = true
            }
        }
    }

    // Play pre-recorded Shobhana clip for a letter/number/symbol label.
    // Falls back silently if no asset exists for that label.
    fun playLetterSound(label: String) {
        val path = "letter_sounds/${charToFilename(label)}"
        try {
            val afd = appContext.assets.openFd(path)
            playFd(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
        } catch (_: Exception) { /* no clip for this label — silent */ }
    }

    // Speak arbitrary text, using the TTS cache to avoid re-synthesising the same string.
    fun speakText(text: String) {
        if (text.isBlank()) return

        val key = text.hashCode().toString()

        // Cache hit — play immediately
        cache[key]?.takeIf { it.exists() }?.let { playFile(it); return }

        // Cache miss — synthesise then play
        if (!ttsReady) return
        val outFile = File(cacheDir, "$key.wav")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                mainHandler.post {
                    cache[key] = outFile
                    playFile(outFile)
                }
            }
            override fun onError(id: String?) {}
        })
        tts?.synthesizeToFile(text, null, outFile, key)
    }

    fun stop() = stopCurrent()

    fun shutdown() {
        stopCurrent()
        tts?.shutdown()
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
        try { player?.let { if (it.isPlaying) it.stop(); it.release() } } catch (_: Exception) {}
        player = null
    }

    // Mirrors generate_letter_sounds.py char_to_filename()
    private fun charToFilename(label: String): String =
        label.codePoints().toArray().joinToString("_") { "%04X".format(it) } + ".mp3"

    companion object {
        private const val MAX_CACHE = 60
    }
}
