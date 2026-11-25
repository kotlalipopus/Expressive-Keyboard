package com.expressive.keyboard

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Heat of the phone (Android 10+). The GL layers lower their frame rate and resolution BEFORE the system throttles the CPU/GPU:
 * a throttled phone stutters much worse than one that dropped quality early.
 */
object GlThermal {
    @Volatile var status = 0
        private set
    private var lastPoll = 0L

    /** Cheap: asks the system at most once every 4 seconds. Called from the GL thread. */
    fun poll(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoll < 4000L) return
        lastPoll = now
        if (Build.VERSION.SDK_INT >= 29) {
            status = try {
                (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
            } catch (t: Throwable) {
                0
            }
        }
    }

    /** Frame-rate ceiling for the current heat level (THERMAL_STATUS_MODERATE = 2, SEVERE = 3, CRITICAL = 4 and up). */
    fun fpsCap(): Int = capFor(status)

    /** Resolution factor for the current heat level. */
    fun scaleFactor(): Float = factorFor(status)

    fun capFor(s: Int): Int = when {
        s >= 4 -> 12
        s >= 3 -> 20
        s >= 2 -> 30
        else -> 60
    }

    fun factorFor(s: Int): Float = when {
        s >= 4 -> 0.5f
        s >= 3 -> 0.7f
        s >= 2 -> 0.85f
        else -> 1f
    }
}

/**
 * Picks a resolution factor from how long frames really take. Pure logic (no Android classes), unit-tested.
 * Over 90% of the frame budget for ten frames in a row: step down by 15%. Under half the budget for 150 frames: step back up by 10%.
 */
class GlGovernor {
    var factor = 1f
        private set
    private var avg = 0.0
    private var slow = 0
    private var fast = 0

    fun frame(ms: Double, budgetMs: Double, minScale: Float) {
        if (factor < minScale) factor = minScale
        avg = if (avg == 0.0) ms else avg * 0.9 + ms * 0.1
        when {
            avg > budgetMs * 0.9 -> {
                slow++
                fast = 0
            }
            avg < budgetMs * 0.5 -> {
                fast++
                slow = 0
            }
            else -> {
                slow = 0
                fast = 0
            }
        }
        if (slow >= 10) {
            slow = 0
            if (factor > minScale) {
                factor = max(minScale, factor * 0.85f)
                avg = 0.0
            }
        } else if (fast >= 150 && factor < 1f) {
            fast = 0
            factor = min(1f, factor * 1.1f)
            avg = 0.0
        }
    }

    fun reset() {
        factor = 1f
        avg = 0.0
        slow = 0
        fast = 0
    }
}

/**
 * Compiled shader programs on disk (OpenGL ES 3.0 program binaries). A heavy shader then links in a few milliseconds on the next
 * launch instead of freezing the first frames. The key includes the driver, so an updated GPU driver never loads a stale binary.
 * File format: 4 bytes binaryFormat, then the binary.
 */
object GlProgramCache {
    private const val MAX_FILES = 48
    val hits = AtomicInteger()
    val stores = AtomicInteger()

    fun key(driver: String, vert: String, frag: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update(driver.toByteArray())
        md.update(0)
        md.update(vert.toByteArray())
        md.update(0)
        md.update(frag.toByteArray())
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun file(dir: File, key: String) = File(File(dir, "glbin"), "$key.bin")

    fun load(dir: File, key: String): Pair<Int, ByteArray>? = try {
        val f = file(dir, key)
        if (!f.isFile || f.length() < 8) {
            null
        } else {
            java.io.DataInputStream(f.inputStream().buffered()).use { s ->
                val fmt = s.readInt()
                val bytes = s.readBytes()
                f.setLastModified(System.currentTimeMillis())
                fmt to bytes
            }
        }
    } catch (e: Exception) {
        null
    }

    fun store(dir: File, key: String, format: Int, bytes: ByteArray) {
        try {
            val d = File(dir, "glbin")
            d.mkdirs()
            val tmp = File(d, "$key.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { s ->
                s.writeInt(format)
                s.write(bytes)
            }
            val dst = file(dir, key)
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                return
            }
            stores.incrementAndGet()
            val all = d.listFiles { x -> x.name.endsWith(".bin") }?.sortedBy { it.lastModified() } ?: return
            if (all.size > MAX_FILES) all.take(all.size - MAX_FILES).forEach { it.delete() }
        } catch (e: Exception) {
            // the cache is only an optimisation
        }
    }
}
