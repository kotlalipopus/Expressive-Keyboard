package com.expressive.keyboard

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipFile

internal object PluginArchive {
    const val MAX_ZIP = 256L * 1024 * 1024
    const val MAX_INLINE = 8L * 1024 * 1024
    const val PREFIX = "@archive:"
    private val marker = Regex("^@archive:([0-9a-f-]{36})/(\\d{1,4})$")

    private fun root(context: Context, id: String) = File(context.noBackupFilesDir, "plugin_archives/p_$id")

    fun file(context: Context, id: String, value: String): File? {
        val match = marker.matchEntire(value) ?: return null
        return File(root(context, id), "${match.groupValues[1]}/${match.groupValues[2]}")
            .takeIf(File::isFile)
    }

    fun bytes(context: Context, id: String, value: String, limit: Long): ByteArray? {
        if (value.startsWith(PREFIX)) {
            val resource = file(context, id, value) ?: return null
            if (resource.length() > limit) return null
            return resource.inputStream().use { input ->
                java.io.ByteArrayOutputStream().use { out -> copy(input, out, limit); out.toByteArray() }
            }
        }
        if (value.length.toLong() * 3 / 4 > limit + 2) return null
        return Base64.decode(value, Base64.DEFAULT).takeIf { it.size <= limit }
    }

    fun copy(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(32768)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= limit) { "Plugin file exceeds the size limit" }
            output.write(buffer, 0, count)
        }
    }

    fun prepare(context: Context, zip: File, install: (String) -> KeyboardPlugin): KeyboardPlugin {
        return ZipFile(zip).use { archive ->
            val entries = LinkedHashMap<String, java.util.zip.ZipEntry>()
            var size = 0L
            val incoming = archive.entries()
            while (incoming.hasMoreElements()) {
                val entry = incoming.nextElement()
                if (entry.isDirectory) continue
                val path = entry.name.replace('\\', '/').trimStart('/')
                require(path.isNotBlank() && path.length <= 250 && path.split('/').none { it == ".." || it == "." })
                require(entries.size < 2048 && entries.put(path, entry) == null)
                require(entry.size in 0..MAX_ZIP && size <= MAX_ZIP - entry.size) { "ZIP exceeds 256 MB unpacked" }
                size += entry.size
            }
            val prefix = entries.keys.map { it.substringBefore('/', "") }.distinct().singleOrNull()
                ?.takeIf { it.isNotEmpty() }?.plus('/').orEmpty()
            val files = entries.mapKeys { it.key.removePrefix(prefix) }
            fun read(entry: java.util.zip.ZipEntry, limit: Long): ByteArray = archive.getInputStream(entry).use { input ->
                java.io.ByteArrayOutputStream().use { out -> copy(input, out, limit); out.toByteArray() }
            }
            val manifest = files["plugin.json"] ?: files["manifest.json"] ?: error("ZIP has no plugin.json")
            val json = JSONObject(read(manifest, MAX_INLINE).toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            val main = json.optString("main", "main.js")
            if (!json.has("script")) files[main]?.let { json.put("script", read(it, MAX_INLINE).toString(Charsets.UTF_8)) }
            val id = PluginParser.parse(json.toString(), false).id
            val generation = UUID.randomUUID().toString()
            val folder = File(root(context, id), generation)
            require(folder.mkdirs())
            try {
                val packaged = JSONObject()
                var inline = 0L
                var unpacked = 0L
                for ((name, entry) in files) {
                    if (entry === manifest || name == "plugin.json" || name == "manifest.json" || name == main) continue
                    val audio = name.substringAfterLast('.').lowercase() in setOf("mp3", "wav", "ogg", "opus", "aac", "m4a", "flac")
                    if (!audio && entry.size <= 512 * 1024 && inline + entry.size <= MAX_INLINE / 2) {
                        val content = read(entry, 512 * 1024)
                        inline += content.size
                        unpacked += content.size
                        packaged.put(name, Base64.encodeToString(content, Base64.NO_WRAP))
                    } else {
                        val part = packaged.length().toString()
                        val destination = File(folder, part)
                        unpacked += archive.getInputStream(entry).use { input ->
                            destination.outputStream().use { output -> copy(input, output, MAX_ZIP - unpacked) }
                        }
                        packaged.put(name, "$PREFIX$generation/$part")
                    }
                }
                json.put("files", packaged)
                install(json.toString()).also {
                    root(context, id).listFiles()?.filter { entry -> entry != folder }?.forEach(File::deleteRecursively)
                    if (folder.list().isNullOrEmpty()) folder.delete()
                }
            } catch (failure: Exception) {
                folder.deleteRecursively()
                throw failure
            }
        }
    }

    fun clear(context: Context, id: String) {
        root(context, id).deleteRecursively()
    }
}
