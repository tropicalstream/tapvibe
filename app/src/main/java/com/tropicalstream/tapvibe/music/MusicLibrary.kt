package com.tropicalstream.tapvibe.music

import android.content.Context
import java.io.File

/**
 * Uploaded-track storage. Files live in the app's external files dir and are copied
 * in as raw bytes with their ORIGINAL extension preserved — this is the fix for the
 * TapInsight encapsulation bug: never re-encode or touch the bytes as text, so the
 * container (mp3/m4a/flac/ogg/wav) and codec stay intact for the decoder.
 */
class MusicLibrary(context: Context) {

    val dir: File = File(context.getExternalFilesDir(null), "Music").apply { mkdirs() }

    private val audioExts = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "mp4", "3gp")

    fun tracks(): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in audioExts }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Copy [source] (a temp upload file) into the library, keeping its extension. */
    fun save(source: File, originalName: String): File {
        val dest = uniqueDest(sanitize(originalName))
        source.copyTo(dest, overwrite = true)   // binary stream copy — bytes verbatim
        return dest
    }

    fun delete(name: String): Boolean {
        val f = File(dir, sanitize(name))
        return f.exists() && f.delete()
    }

    fun displayName(f: File): String = f.nameWithoutExtension

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base.replace(Regex("[^A-Za-z0-9._ ()\\-]"), "_").take(120).ifBlank { "track" }
    }

    private fun uniqueDest(name: String): File {
        var dest = File(dir, name)
        if (!dest.exists()) return dest
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (dest.exists()) {
            dest = File(dir, "$stem ($i)" + if (ext.isNotEmpty()) ".$ext" else "")
            i++
        }
        return dest
    }
}
