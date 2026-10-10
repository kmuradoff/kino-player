package com.xgimiru.player.dovi  // SPDX-License-Identifier: LGPL-2.1-or-later. Shader ported from libplacebo in 2026, modified; see NOTICE.

import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.DataSpace
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Рендер Dolby Vision профиля 5 силами видеочипа — как libplacebo в mpv:
 * аппаратный декодер HEVC отдаёт «сырые» кадры (IPT-PQ), шейдер применяет кривые и
 * матрицы из RPU этого кадра и выдаёт HDR10 (BT.2020 PQ) или SDR с тон-маппингом.
 */
@OptIn(UnstableApi::class)
class DoviRenderer(
    private val output: SurfaceView,
    private val store: DoviStore,
    private val hdrOut: Boolean,
    private val onInputSurface: (Surface) -> Unit,
) : VideoFrameMetadataListener, SurfaceHolder.Callback {

    private val thread = HandlerThread("dovi-gl").apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var surfaceW = 0
    private var surfaceH = 0
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var videoRatio = 1f
    @Volatile var scaleMode = 0
    private val texMatrix = FloatArray(16)
    private val releaseToPts = ConcurrentHashMap<Long, Long>()
    private var lastMeta: DoviFrame? = null
    private var framesDrawn = 0L
    private var framesWithMeta = 0L

    init {
        output.holder.setFormat(PixelFormat.RGBA_1010102)
        output.holder.addCallback(this)
    }

    // ── Жизненный цикл поверхности ───────────────────────────────────────────
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (hdrOut && Build.VERSION.SDK_INT >= 33) {
            // Помечаем слой как HDR10 (BT.2020 PQ): дальше проектор обрабатывает его как обычный HDR10.
            runCatching {
                SurfaceControl.Transaction().setDataSpace(output.surfaceControl, DataSpace.DATASPACE_BT2020_PQ).apply()
            }
        }
        gl.post { setup(holder.surface) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        gl.post { surfaceW = width; surfaceH = height }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        gl.post { destroyWindow() }
    }

    fun setVideoSize(w: Int, h: Int, pixelRatio: Float) {
        videoW = w; videoH = h; videoRatio = if (pixelRatio > 0f) pixelRatio else 1f
    }

    /** Media3 сообщает: кадр с таким-то временем будет показан в такой-то момент. */
    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {
        releaseToPts[releaseTimeNs] = presentationTimeUs
        if (releaseToPts.size > 64) releaseToPts.keys.sorted().take(32).forEach { releaseToPts.remove(it) }
    }

    fun stats() = "кадров $framesDrawn, с метаданными $framesWithMeta"

    fun release() {
        output.holder.removeCallback(this)
        gl.post {
            destroyWindow()
            surfaceTexture?.release(); surfaceTexture = null
            inputSurface?.release(); inputSurface = null
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            if (program != 0) GLES30.glDeleteProgram(program)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            thread.quitSafely()
        }
    }

    // ── EGL/GL ───────────────────────────────────────────────────────────────
    private var config: EGLConfig? = null

    private fun setup(surface: Surface) {
        if (display == EGL14.EGL_NO_DISPLAY) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
            config = chooseConfig(10) ?: chooseConfig(8)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        }
        window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, window, window, context)
        if (program == 0) {
            program = buildProgram()
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); texture = t[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(texture)
            st.setOnFrameAvailableListener({ drawFrame() }, gl)
            surfaceTexture = st
            val s = Surface(st)
            inputSurface = s
            main.post { onInputSurface(s) }
        }
    }

    private fun chooseConfig(bits: Int): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits,
            EGL14.EGL_ALPHA_SIZE, if (bits == 10) 2 else 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(8); val n = IntArray(1)
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, configs.size, n, 0)
        return configs.take(n[0]).firstOrNull { c ->
            val r = IntArray(1); EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_RED_SIZE, r, 0); r[0] == bits
        }
    }

    private fun destroyWindow() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
    }

    // ── Кадр ─────────────────────────────────────────────────────────────────
    private fun drawFrame() {
        val st = surfaceTexture ?: return
        if (window == EGL14.EGL_NO_SURFACE) { runCatching { st.updateTexImage() }; return }
        EGL14.eglMakeCurrent(display, window, window, context)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        val ts = st.timestamp
        val pts = releaseToPts.remove(ts)
        val meta = (if (pts != null) store.get(pts) else null) ?: lastMeta
        if (meta != null && meta !== lastMeta) { upload(meta); lastMeta = meta }
        framesDrawn++
        if (meta != null) framesWithMeta++

        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        setViewport()
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES30.glUniform1i(loc("uTex"), 0)
        GLES30.glUniformMatrix4fv(loc("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform1i(loc("uActive"), if (meta != null) 1 else 0)
        GLES30.glUniform1i(loc("uOutMode"), if (hdrOut) 0 else 1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, ts)
        EGL14.eglSwapBuffers(display, window)
    }

    /** «Вписать / заполнить / растянуть» — как в остальном плеере. */
    private fun setViewport() {
        if (videoW <= 0 || videoH <= 0 || surfaceW <= 0 || surfaceH <= 0) return
        val va = videoW * videoRatio / videoH
        val sa = surfaceW.toFloat() / surfaceH
        var w = surfaceW; var h = surfaceH
        when (scaleMode) {
            2 -> Unit
            1 -> if (va > sa) w = (surfaceH * va).toInt() else h = (surfaceW / va).toInt()
            else -> if (va > sa) h = (surfaceW / va).toInt() else w = (surfaceH * va).toInt()
        }
        GLES30.glViewport((surfaceW - w) / 2, (surfaceH - h) / 2, w, h)
    }

    // ── Метаданные → uniform-ы ───────────────────────────────────────────────
    private fun upload(m: DoviFrame) {
        GLES30.glUseProgram(program)
        val piv = FloatArray(21) { 1e9f }
        val co = FloatArray(24 * 4)
        val mmr = FloatArray(144 * 4)
        val loHi = FloatArray(6)
        val has = IntArray(3)
        for (c in 0 until 3) {
            val n = m.numPivots[c]
            if (n < 2) continue
            has[c] = 1
            loHi[c * 2] = m.pivots[c][0]; loHi[c * 2 + 1] = m.pivots[c][n - 1]
            for (i in 0 until n - 2) piv[c * 7 + i] = m.pivots[c][i + 1]
            var mmrIdx = 0
            for (i in 0 until n - 1) {
                val base = (c * 8 + i) * 4
                if (m.method[c][i] == 0) {
                    co[base] = m.poly[c][i][0]; co[base + 1] = m.poly[c][i][1]; co[base + 2] = m.poly[c][i][2]; co[base + 3] = 0f
                } else {
                    val order = m.mmrOrder[c][i]
                    co[base] = m.mmrConst[c][i]; co[base + 1] = mmrIdx.toFloat(); co[base + 2] = 0f; co[base + 3] = order.toFloat()
                    for (j in 0 until order) {
                        val k = m.mmr[c][i][j]
                        val v = (c * 48 + mmrIdx) * 4
                        if (mmrIdx + 1 >= 48) break
                        mmr[v] = k[0]; mmr[v + 1] = k[1]; mmr[v + 2] = k[2]; mmr[v + 3] = 0f
                        mmr[v + 4] = k[3]; mmr[v + 5] = k[4]; mmr[v + 6] = k[5]; mmr[v + 7] = k[6]
                        mmrIdx += 2
                    }
                }
            }
        }
        GLES30.glUniform1fv(loc("uPiv"), 21, piv, 0)
        GLES30.glUniform4fv(loc("uCo"), 24, co, 0)
        GLES30.glUniform4fv(loc("uMmr"), 144, mmr, 0)
        GLES30.glUniform2fv(loc("uLoHi"), 3, loHi, 0)
        GLES30.glUniform1iv(loc("uHas"), 3, has, 0)
        GLES30.glUniform3fv(loc("uOff"), 1, m.nonlinearOffset, 0)
        GLES30.glUniformMatrix3fv(loc("uNonlin"), 1, true, m.nonlinear, 0)
        GLES30.glUniformMatrix3fv(loc("uLms2Rgb"), 1, true, mul(LMS2RGB, m.linear), 0)
        val peakPq = if (m.l1MaxPq > 0) m.l1MaxPq else m.sourceMaxPq
        GLES30.glUniform1f(loc("uSrcPeak"), pqToNits(peakPq / 4095f).coerceIn(100f, 10000f))
    }

    private fun pqToNits(e: Float): Float {
        val p = e.toDouble().pow(1 / 78.84375)
        return (10000 * ((p - 0.8359375).coerceAtLeast(0.0) / (18.8515625 - 18.6875 * p)).pow(1 / 0.1593017578125)).toFloat()
    }

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private val locs = HashMap<String, Int>()
    private fun loc(name: String) = locs.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, VERTEX))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES30.glGetProgramInfoLog(p))
        val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        quadRef = quad
        return p
    }
    private var quadRef: FloatBuffer? = null

    companion object {
        /** HPE LMS (BT.2020) → RGB BT.2020, как в libplacebo. По строкам. */
        private val LMS2RGB = floatArrayOf(
            3.06441879f, -2.16597676f, 0.10155818f,
            -0.65612108f, 1.78554118f, -0.12943749f,
            0.01736321f, -0.04725154f, 1.03004253f,
        )

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    vec2 uv = aPos * 0.5 + 0.5;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val FRAGMENT = """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTex;
in vec2 vUv;
layout(location = 0) out vec4 outColor;

uniform int uActive;
uniform int uOutMode;          // 0 — HDR10 (PQ BT.2020), 1 — SDR
uniform float uPiv[21];        // 3 × 7 внутренних опорных точек
uniform vec4 uCo[24];          // 3 × 8 участков: полином (c0,c1,c2,0) / MMR (конст, индекс, 0, порядок)
uniform vec4 uMmr[144];        // 3 × 48
uniform vec2 uLoHi[3];
uniform int uHas[3];
uniform vec3 uOff;
uniform mat3 uNonlin;
uniform mat3 uLms2Rgb;
uniform float uSrcPeak;

const float M1 = 0.1593017578125, M2 = 78.84375, C1 = 0.8359375, C2 = 18.8515625, C3 = 18.6875;
vec3 pqEotf(vec3 e) { vec3 p = pow(max(e, 0.0), vec3(1.0 / M2)); return pow(max(p - C1, 0.0) / (C2 - C3 * p), vec3(1.0 / M1)); }
vec3 pqOetf(vec3 l) { vec3 p = pow(max(l, 0.0), vec3(M1)); return pow((C1 + C2 * p) / (1.0 + C3 * p), vec3(M2)); }

float reshape(int c, vec3 sig) {
    float s = sig[c];
    int piece = 0;
    for (int i = 0; i < 7; i++) if (s >= uPiv[c * 7 + i]) piece = i + 1;
    vec4 co = uCo[c * 8 + piece];
    if (co.w == 0.0) {
        s = (co.z * s + co.y) * s + co.x;
    } else {
        int idx = c * 48 + int(co.y);
        int order = int(co.w);
        vec4 sigX;
        sigX.xyz = sig.xxy * sig.yzz;
        sigX.w = sigX.x * sig.z;
        s = co.x + dot(uMmr[idx].xyz, sig) + dot(uMmr[idx + 1], sigX);
        if (order >= 2) {
            vec3 sig2 = sig * sig;
            vec4 sigX2 = sigX * sigX;
            s += dot(uMmr[idx + 2].xyz, sig2) + dot(uMmr[idx + 3], sigX2);
            if (order >= 3) s += dot(uMmr[idx + 4].xyz, sig2 * sig) + dot(uMmr[idx + 5], sigX2 * sigX);
        }
    }
    return clamp(s, uLoHi[c].x, uLoHi[c].y);
}

void main() {
    vec3 sig = clamp(texture(uTex, vUv).xyz, 0.0, 1.0);
    if (uActive == 0) { outColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec3 col = sig;
    for (int c = 0; c < 3; c++) if (uHas[c] == 1) col[c] = reshape(c, sig);
    vec3 lms = pqEotf(uNonlin * (col - uOff));      // линейный LMS, 1.0 = 10000 нит
    vec3 rgb = max(uLms2Rgb * lms, 0.0);             // линейный RGB BT.2020
    if (uOutMode == 0) {
        outColor = vec4(pqOetf(rgb), 1.0);
    } else {
        // SDR: BT.2020 → BT.709, тон-маппинг по яркости сцены, гамма.
        vec3 c709 = mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187) * rgb * 10000.0;
        float white = 203.0;
        float m = max(max(c709.r, c709.g), c709.b) / white;
        float pk = max(uSrcPeak / white, 1.0);
        float mt = m * (1.0 + m / (pk * pk)) / (1.0 + m);
        vec3 sdr = m > 0.0 ? c709 / white * (mt / m) : vec3(0.0);
        outColor = vec4(pow(clamp(sdr, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
    }
}
"""
    }
}
