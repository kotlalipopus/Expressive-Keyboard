package com.expressive.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

internal object UserPictures {
    private const val MAX_INPUT = 20 * 1024 * 1024
    private const val MAX_SIDE = 1024

    private fun dir(context: Context, plugin: String) = File(context.noBackupFilesDir, "plugin_images/p_$plugin")
    private fun file(context: Context, plugin: String, option: String) =
        AtomicFile(File(dir(context, plugin), "i_$option.png"))

    fun token(option: String, value: Any?): String? = (value as? String)?.takeIf {
        it.startsWith("user:$option:") && it.substringAfterLast(':').toLongOrNull()?.let { stamp -> stamp > 0 } == true
    }

    fun save(context: Context, plugin: String, option: String, uri: Uri): String {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_INPUT)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val bitmap = decode(bytes)
        try {
            val target = file(context, plugin, option)
            val stream = target.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                target.finishWrite(stream)
            } catch (failure: Exception) {
                target.failWrite(stream)
                throw failure
            }
        } finally {
            bitmap.recycle()
        }
        return "user:$option:${System.currentTimeMillis()}"
    }

    private fun decode(data: ByteArray): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                val factor = (MAX_SIDE.toDouble() / maxOf(info.size.width, info.size.height)).coerceAtMost(1.0)
                decoder.setTargetSize((info.size.width * factor).toInt().coerceAtLeast(1),
                    (info.size.height * factor).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val sample = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) - 1) / MAX_SIDE).coerceAtLeast(1)
        val original = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample }))
        val factor = MAX_SIDE.toDouble() / maxOf(original.width, original.height)
        if (factor >= 1) return original
        return Bitmap.createScaledBitmap(original, (original.width * factor).toInt().coerceAtLeast(1),
            (original.height * factor).toInt().coerceAtLeast(1), true).also {
            if (it !== original) original.recycle()
        }
    }

    fun thumbnail(context: Context, plugin: String, option: String): Bitmap? = runCatching {
        file(context, plugin, option).openRead().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }.getOrNull()

    fun readTexture(context: Context, plugin: KeyboardPlugin, value: String): ByteArray? {
        val option = plugin.config.firstOrNull { it.type == "image" && token(it.id, value) != null } ?: return null
        if (ModConfig.get(context, plugin, option) != value) return null
        return runCatching {
            file(context, plugin.id, option.id).readFully()
        }.getOrNull()
    }

    fun clear(context: Context, plugin: String, option: String) = file(context, plugin, option).delete()
    fun clearAll(context: Context, plugin: String) {
        val folder = dir(context, plugin)
        folder.listFiles()?.forEach { it.delete() }
        folder.delete()
    }
}
