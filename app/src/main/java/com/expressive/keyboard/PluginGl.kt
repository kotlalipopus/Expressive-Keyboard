package com.expressive.keyboard

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20 as GL
import android.opengl.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MAX_SIDE = 384
private const val MAX_SOURCE = 16_384
private const val QUAD_VERTEX = """
attribute vec3 aPosition;
void main() { gl_Position = vec4(aPosition, 1.0); }
"""
private const val MESH_VERTEX = """
attribute vec3 aPosition;
attribute vec4 aColor;
uniform mat4 iMvp;
varying mediump vec4 vColor;
void main() {
    gl_Position = iMvp * vec4(aPosition, 1.0);
    vColor = aColor;
}
"""
private const val MESH_FRAGMENT = """
precision mediump float;
varying mediump vec4 vColor;
void main() { gl_FragColor = vColor; }
"""

internal data class PluginMesh(
    val vertices: List<Float>,
    val indices: List<Int>,
    val colors: List<Int>,
    val rotateX: Float,
    val rotateY: Float,
    val rotateZ: Float,
    val scale: Float,
    val distance: Float
)

internal data class GlFrameRequest(val item: OverlayItem.Gl, val size: IntSize, val time: Float)

/** Один GL-поток для всех слоёв. Компиляция, отрисовка и чтение пикселей не задерживают ввод. */
internal object PluginGl {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "KeyboardPluginGL") }.asCoroutineDispatcher()
    private val renderer by lazy { runCatching { PluginGlRenderer() } }
    private val errors = mutableMapOf<String, String>()

    fun error(src: String): String? = synchronized(errors) { errors[src] }

    suspend fun render(requests: Map<Int, GlFrameRequest>): Map<Int, ImageBitmap> = withContext(worker) {
        buildMap {
            for ((index, request) in requests) {
                val src = request.item.source
                try {
                    put(index, renderer.getOrThrow().render(request))
                } catch (e: RuntimeException) {
                    synchronized(errors) {
                        if (errors.size >= 24) errors.remove(errors.keys.first())
                        errors[src] = e.message?.take(2048) ?: "OpenGL ES rendering failed"
                    }
                }
            }
        }
    }

    fun parse(
        o: Map<*, *>, x: Float, y: Float, w: Float, h: Float, alpha: Float, rotation: Float
    ): OverlayItem.Gl? {
        val isMesh = o["type"] == "mesh3d"
        val src = o["src"] as? String ?: if (isMesh) MESH_FRAGMENT else return null
        if (src.isBlank() || src.length > MAX_SOURCE) return null
        val mesh = if (isMesh) parseMesh(o) ?: return null else null
        val uniforms = (o["uniforms"] as? Map<*, *>)?.entries.orEmpty().take(32).mapNotNull { (name, value) ->
            val key = (name as? String)?.takeIf { it.length in 1..64 } ?: return@mapNotNull null
            val vector = when (value) {
                is Number -> listOf(value.toFloat())
                is Boolean -> listOf(if (value) 1f else 0f)
                is List<*> -> value.takeIf { it.size in 1..4 }?.map { (it as? Number)?.toFloat() ?: Float.NaN }
                else -> null
            } ?: return@mapNotNull null
            if (vector.all { it.isFinite() }) key to vector else null
        }.toMap()
        return OverlayItem.Gl(x.coerceIn(-4096f, 4096f), y.coerceIn(-4096f, 4096f),
            w.coerceIn(0f, 4096f), h.coerceIn(0f, 4096f), src, uniforms, mesh, alpha, rotation % 360f)
    }

    private fun parseMesh(o: Map<*, *>): PluginMesh? {
        val rawVertices = (o["vertices"] as? List<*>)?.takeIf { it.size in 9..4608 && it.size % 3 == 0 } ?: return null
        val vertices = rawVertices.map {
            (it as? Number)?.toFloat()?.takeIf { n -> n.isFinite() && n in -100f..100f } ?: return null
        }
        val count = vertices.size / 3
        val rawIndices = o["indices"] as? List<*>
        val indices = if (rawIndices != null) {
            if (rawIndices.size !in 3..4608 || rawIndices.size % 3 != 0) return null
            rawIndices.map {
                val index = (it as? Number)?.toDouble() ?: return null
                index.toInt().takeIf { i -> i in 0 until count && i.toDouble() == index } ?: return null
            }
        } else {
            if (count % 3 != 0) return null
            (0 until count).toList()
        }
        val tint = PluginParser.parseColor(o["color"] as? String) ?: -1
        val rawColors = o["colors"] as? List<*>
        val colors = List(count) { i -> PluginParser.parseColor(rawColors?.getOrNull(i) as? String) ?: tint }
        fun number(key: String, default: Float) = (o[key] as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: default
        return PluginMesh(vertices, indices, colors, number("rotateX", 0f) % 360f,
            number("rotateY", 0f) % 360f, number("rotateZ", 0f) % 360f,
            number("scale", 1f).coerceIn(0.01f, 10f), number("distance", 4f).coerceIn(0.25f, 100f))
    }
}

private class PluginGlRenderer {
    private data class ProgramKey(val mesh: Boolean, val source: String)
    private data class Uniform(val name: String, val location: Int, val type: Int)
    private data class Program(val id: Int, val position: Int, val color: Int, val uniforms: List<Uniform>)
    private val programs = object : LinkedHashMap<ProgramKey, Result<Program>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProgramKey, Result<Program>>): Boolean {
            if (size <= 16) return false
            eldest.value.getOrNull()?.let { GL.glDeleteProgram(it.id) }
            return true
        }
    }
    private val pixels = ByteBuffer.allocateDirect(MAX_SIDE * MAX_SIDE * 4)
    private val argb = IntArray(MAX_SIDE * MAX_SIDE)
    private val positions = ByteBuffer.allocateDirect(4608 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val colors = ByteBuffer.allocateDirect(1536 * 4).order(ByteOrder.nativeOrder())
    private val indices = ByteBuffer.allocateDirect(4608 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    private val quad = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
    private val model = FloatArray(16)
    private val projection = FloatArray(16)
    private val mvp = FloatArray(16)

    init {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, null, 0, null, 0)) {
            "OpenGL ES display unavailable"
        }
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val attrs = intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "OpenGL ES configuration unavailable"
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "Could not create OpenGL ES context" }
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, MAX_SIDE, EGL14.EGL_HEIGHT, MAX_SIDE, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "Could not create OpenGL ES drawing surface"
            }
        } catch (e: RuntimeException) {
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            throw e
        }
    }

    fun render(request: GlFrameRequest): ImageBitmap {
        val (item, size, time) = request
        val mesh = item.mesh
        val key = ProgramKey(mesh != null, item.source)
        val program = programs.getOrPut(key) { runCatching { link(key) } }.getOrThrow()
        val factor = (MAX_SIDE.toFloat() / maxOf(size.width, size.height)).coerceAtMost(1f)
        val width = (size.width * factor).roundToInt().coerceAtLeast(1)
        val height = (size.height * factor).roundToInt().coerceAtLeast(1)
        GL.glViewport(0, 0, width, height)
        GL.glClearColor(0f, 0f, 0f, 0f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        GL.glUseProgram(program.id)
        for (uniform in program.uniforms) {
            when (uniform.name) {
                "iTime" -> if (uniform.type == GL.GL_FLOAT) GL.glUniform1f(uniform.location, time)
                "iResolution" -> if (uniform.type == GL.GL_FLOAT_VEC2) GL.glUniform2f(uniform.location, width.toFloat(), height.toFloat())
                "iMvp" -> if (mesh != null && uniform.type == GL.GL_FLOAT_MAT4) {
                    GL.glUniformMatrix4fv(uniform.location, 1, false, transform(mesh, width.toFloat() / height), 0)
                }
                else -> bind(uniform, item.uniforms[uniform.name])
            }
        }
        positions.clear()
        if (mesh == null) positions.put(quad) else mesh.vertices.forEach { positions.put(it) }
        positions.flip()
        if (program.position >= 0) {
            GL.glEnableVertexAttribArray(program.position)
            GL.glVertexAttribPointer(program.position, 3, GL.GL_FLOAT, false, 0, positions)
        }
        if (mesh == null) {
            GL.glDisable(GL.GL_DEPTH_TEST)
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        } else {
            drawMesh(program, mesh)
        }
        if (program.position >= 0) GL.glDisableVertexAttribArray(program.position)
        return readBitmap(width, height)
    }

    private fun transform(mesh: PluginMesh, aspect: Float): FloatArray {
        Matrix.perspectiveM(projection, 0, 45f, aspect, 0.1f, 1200f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, -mesh.distance)
        Matrix.rotateM(model, 0, mesh.rotateZ, 0f, 0f, 1f)
        Matrix.rotateM(model, 0, mesh.rotateY, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, mesh.rotateX, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, mesh.scale, mesh.scale, mesh.scale)
        Matrix.multiplyMM(mvp, 0, projection, 0, model, 0)
        return mvp
    }

    private fun drawMesh(program: Program, mesh: PluginMesh) {
        GL.glEnable(GL.GL_DEPTH_TEST)
        if (program.color >= 0) {
            colors.clear()
            for (color in mesh.colors) {
                colors.put((color ushr 16).toByte()).put((color ushr 8).toByte())
                    .put(color.toByte()).put((color ushr 24).toByte())
            }
            colors.flip()
            GL.glEnableVertexAttribArray(program.color)
            GL.glVertexAttribPointer(program.color, 4, GL.GL_UNSIGNED_BYTE, true, 0, colors)
        }
        indices.clear()
        mesh.indices.forEach { indices.put(it.toShort()) }
        indices.flip()
        GL.glDrawElements(GL.GL_TRIANGLES, mesh.indices.size, GL.GL_UNSIGNED_SHORT, indices)
        if (program.color >= 0) GL.glDisableVertexAttribArray(program.color)
    }

    private fun readBitmap(width: Int, height: Int): ImageBitmap {
        pixels.clear()
        GL.glReadPixels(0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
        check(GL.glGetError() == GL.GL_NO_ERROR) { "OpenGL ES could not render the frame" }
        for (y in height - 1 downTo 0) {
            var i = y * width
            repeat(width) {
                val r = pixels.get().toInt() and 255
                val g = pixels.get().toInt() and 255
                val b = pixels.get().toInt() and 255
                val a = pixels.get().toInt() and 255
                argb[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private fun bind(u: Uniform, value: List<Float>?) {
        fun v(index: Int) = value?.getOrNull(index) ?: 0f
        when (u.type) {
            GL.GL_FLOAT -> GL.glUniform1f(u.location, v(0))
            GL.GL_FLOAT_VEC2 -> GL.glUniform2f(u.location, v(0), v(1))
            GL.GL_FLOAT_VEC3 -> GL.glUniform3f(u.location, v(0), v(1), v(2))
            GL.GL_FLOAT_VEC4 -> GL.glUniform4f(u.location, v(0), v(1), v(2), v(3))
            GL.GL_INT, GL.GL_BOOL -> GL.glUniform1i(u.location, v(0).toInt())
        }
    }

    private fun link(key: ProgramKey): Program {
        val vertex = compile(GL.GL_VERTEX_SHADER, if (key.mesh) MESH_VERTEX else QUAD_VERTEX)
        var fragment = 0
        var id = 0
        try {
            fragment = compile(GL.GL_FRAGMENT_SHADER, key.source)
            id = GL.glCreateProgram()
            check(id != 0) { "Could not allocate GLSL program" }
            GL.glAttachShader(id, vertex)
            GL.glAttachShader(id, fragment)
            GL.glLinkProgram(id)
            val status = IntArray(1)
            GL.glGetProgramiv(id, GL.GL_LINK_STATUS, status, 0)
            check(status[0] == GL.GL_TRUE) { GL.glGetProgramInfoLog(id) }
            GL.glGetProgramiv(id, GL.GL_ACTIVE_UNIFORMS, status, 0)
            val uniforms = buildList {
                val size = IntArray(1)
                val type = IntArray(1)
                repeat(status[0]) { index ->
                    val name = GL.glGetActiveUniform(id, index, size, 0, type, 0)
                    if (size[0] == 1) add(Uniform(name, GL.glGetUniformLocation(id, name), type[0]))
                }
            }
            return Program(id, GL.glGetAttribLocation(id, "aPosition"), GL.glGetAttribLocation(id, "aColor"), uniforms)
        } catch (e: RuntimeException) {
            if (id != 0) GL.glDeleteProgram(id)
            throw e
        } finally {
            GL.glDeleteShader(vertex)
            if (fragment != 0) GL.glDeleteShader(fragment)
        }
    }

    private fun compile(type: Int, src: String): Int {
        val id = GL.glCreateShader(type)
        check(id != 0) { "Could not allocate GLSL shader" }
        GL.glShaderSource(id, src)
        GL.glCompileShader(id)
        val status = IntArray(1)
        GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GL.GL_TRUE) return id
        val error = GL.glGetShaderInfoLog(id)
        GL.glDeleteShader(id)
        error(error.ifBlank { "GLSL compilation failed" })
    }
}
