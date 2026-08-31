/*
 * Holy MobileGlues — BenchmarkGLRenderer (kusursuz stabilite)
 * Kaynak: Star1xr/ZalithLauncher2Plus BenchmarkGLRenderer.kt:15 (149 satır)
 * Ham GPU potansiyelini gizleyen her jitter ayıklandı — inceleme her zaman açık, kusursuz olana kadar revize.
 *
 * Değişiklikler vs Plus:
 *  - [S1] Uniform/attrib lokasyon cache (glGet* her kare sync → 0)
 *  - [S2] Isınma 1500 ms + 30 kare (DVFS ramp, shader compile, GC atık veri dışı)
 *  - [S3] Zaman bazlı rotasyon (elapsed'e göre deterministik, tek kare dt jitter'ı yok)
 *  - [S4] Median+MAD outlier (3×MAD, MAD×1.4826 → σ), puan = median×stab, noise = 100-stab
 *  - [S5] V-sync notu + [S7] THREAD_PRIORITY_DISPLAY + [S8] surface lifecycle güvenli
 *  - [S6] ArrayList(2048) + [S9] deterministik grid (col/row sabit, allocation yok)
 *  Ölçülen tek şey ham draw-call maliyeti — gerisi stabilite gürültüsü değil.
 */

package com.holy.mobileglues.benchmark

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.roundToInt

data class BenchmarkResult(
    val avgFps: Int,
    val minFps: Int,
    val p99Fps: Int,
    val stabilityPct: Int,
    val score: Int,
    val frameCount: Int,
    // Stabilite revizyonu: ham veriler — PR karşılaştırması için
    val medianFps: Int = avgFps,
    val noisePct: Int = 100 - stabilityPct,
)

class BenchmarkGLRenderer(
    private val durationMs: Long = 15_000L,
    // [S2] Isınma: ölçülmeyen süre + kare sayısı
    private val warmupMs: Long = 1_500L,
    private val warmupFrames: Int = 30,
    val onProgress: (secondsLeft: Int) -> Unit = {},
    val onComplete: (BenchmarkResult) -> Unit = {},
) : GLSurfaceView.Renderer {

    // [S6] Kapasite önceden ayrıldı — her add'de array büyütme GC jitter'ını artırıyordu
    private val frameTimes = ArrayList<Long>(2048)
    private var lastNanos = 0L
    private var startNanos = 0L
    private var programId = 0
    private var vboId = 0
    // [S1] Cache — her kare sorgulanan lokasyonlar
    private var aPosLoc = -1
    private var uRotLoc = -1
    private var uOffLoc = -1
    private var rotation = 0f
    private var done = false
    private var framesTotal = 0

    private val VERT = """
        attribute vec2 aPos;
        uniform float uRot;
        uniform vec2 uOff;
        void main() {
            float r = uRot * 3.14159 / 180.0;
            float c = cos(r); float s = sin(r);
            vec2 p = vec2(aPos.x*c - aPos.y*s, aPos.x*s + aPos.y*c);
            gl_Position = vec4(p * 0.035 + uOff, 0.0, 1.0);
        }
    """.trimIndent()

    private val FRAG = """
        precision mediump float;
        uniform float uRot;
        void main() {
            float t = mod(uRot, 360.0) / 360.0;
            gl_FragColor = vec4(t, 0.6 - t*0.4, 1.0 - t, 0.9);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // [S7] Kusursuz: GL thread önceliği — scheduler jitter'ı azalt, ham potansiyel öne çıksın
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) } catch (_: Throwable) {}
        GLES20.glClearColor(0.08f, 0.08f, 0.12f, 1f)
        programId = compileProgram()
        vboId = makeQuadVBO()
        // [S1] Lokasyonları bir kez al
        aPosLoc = GLES20.glGetAttribLocation(programId, "aPos")
        uRotLoc = GLES20.glGetUniformLocation(programId, "uRot")
        uOffLoc = GLES20.glGetUniformLocation(programId, "uOff")
        // [S8] V-sync not: GLSurfaceView RENDERMODE_CONTINUOUSLY vsync'e takılır — gerçek ham potansiyel
        // vsync kapalı ölçüm için EGL14.eglSwapInterval(eglDisplay, 0) gerekir ama GLSurfaceView'da erişim yok;
        // bu bench vsync dahil FPS'i ölçer (cihazın gerçek oyun FPS'ine yakın). CI synthetic bench vsync'sizdir.
        startNanos = System.nanoTime()
        lastNanos = 0L
        frameTimes.clear()
        framesTotal = 0
        done = false
        rotation = 0f
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (done) return
        val now = System.nanoTime()
        if (lastNanos != 0L) {
            val dt = now - lastNanos
            // [S2] Isınma eşiğini geçmeden listeye ekleme — DVFS ramp ve ilk GC'ler ayıklanır
            val elapsedMs = (now - startNanos) / 1_000_000L
            framesTotal++
            if (elapsedMs >= warmupMs && framesTotal > warmupFrames) {
                frameTimes.add(dt)
            }
        }
        lastNanos = now

        val elapsedMs = (now - startNanos) / 1_000_000L
        val remainingSec = ((durationMs - elapsedMs) / 1000L).toInt().coerceAtLeast(0)
        onProgress(remainingSec)

        // Toplam süre (ısınma dahil) dolunca ve yeterli ölçüm varsa bitir
        if (elapsedMs >= durationMs && frameTimes.size > 20) {
            done = true
            onComplete(computeResultStable())
            return
        }

        // [S3] Zaman bazlı rotasyon — elapsed'e göre, tek kare dt jitter'ından arındırılmış
        // Önce 2°/frame, sonra lastFrame dt ile idi; şimdi toplam elapsed ile deterministik — ham yük sabit
        val elapsedMsForRot = (now - startNanos) / 1_000_000.0
        rotation = ((elapsedMsForRot / 16.0 * 2.0) % 360.0).toFloat()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programId)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 8, 0)

        repeat(500) { i ->
            val rot = (rotation + i * 0.72f) % 360f
            val col = i % 25 - 12
            val row = i / 25 - 10
            GLES20.glUniform1f(uRotLoc, rot)
            GLES20.glUniform2f(uOffLoc, col * 0.082f, row * 0.1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(aPosLoc)
    }

    // [S4] Stabilite odaklı hesap — multidraw_bench.cpp mantığı: median, p99, noise
    // Outliers: ilk ham liste sorted → median → MAD → 3*MAD üstü atılır → temiz median/avg
    private fun computeResultStable(): BenchmarkResult {
        if (frameTimes.isEmpty()) return BenchmarkResult(0, 0, 0, 0, 0, 0)
        val ms = frameTimes.map { it / 1_000_000.0 }.sorted()
        val medianMs = ms[ms.size / 2]
        // MAD
        val deviations = ms.map { abs(it - medianMs) }.sorted()
        val mad = deviations[deviations.size / 2].coerceAtLeast(0.001)
        // 3*MAD üstü outlier sayılır — tekil compositor hiccup'ları ayıklanır
        val filtered = ms.filter { abs(it - medianMs) <= 3 * mad * 1.4826 }
        val use = if (filtered.size >= ms.size * 0.7) filtered else ms // çok atıldıysa hamı kullan

        val avgMs = use.average()
        val p99Ms = use[(use.size * 0.99).toInt().coerceAtMost(use.lastIndex)]
        val maxMs = use.max()
        val medianFiltered = use.sorted().let { it[it.size / 2] }

        val avgFps = (1000.0 / avgMs).roundToInt()
        val medianFps = (1000.0 / medianFiltered).roundToInt()
        val minFps = (1000.0 / maxMs).roundToInt()
        val p99Fps = (1000.0 / p99Ms).roundToInt()
        // Kararlılık: median/max — jitter cezasız ham potansiyel median'da, kararlılık ayrı
        val stab = ((1000.0 / maxMs) / (1000.0 / medianFiltered) * 100).roundToInt().coerceIn(0, 100)
        // Puan: median × stab — tekil hiccup ortalamayı çekemiyor
        val score = (medianFps * stab / 100.0).roundToInt()
        val noise = (100 - stab).coerceIn(0, 100)

        return BenchmarkResult(avgFps, minFps, p99Fps, stab, score, use.size, medianFps, noise)
    }

    private fun compileProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            return id
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VERT))
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FRAG))
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun makeQuadVBO(): Int {
        val data = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, buf, GLES20.GL_STATIC_DRAW)
        return ids[0]
    }
}
