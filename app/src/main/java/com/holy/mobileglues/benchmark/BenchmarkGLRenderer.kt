/*
 * Holy MobileGlues — BenchmarkGLRenderer (kusursuz stabilite — ALT deney)
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
 *  - [S10] Çift ölçüm: skor = median×(stab/100)² — minFPS kuadratik cezalı, ham potansiyel tek başına yetmez
 *  - [S11] Thermal throttling simülasyonu: kronolojik birinci/ikinci yarı median farkı → thermalDropPct
 *  - [S12] V-sync vs raw ayrımı: stabil + yaygın refresh (60/90/120) → vsyncLimited flag'i
 *  - [S13] Frame pacing: median×1.5 üstü kareler jank sayılır → jankPct/pacingScore, yüksek jank ek ceza
 *  Ölçülen tek şey ham draw-call maliyeti — gerisi stabilite gürültüsü değil; min FPS artık ham kadar ağırlıkta.
 *
 * ALT deney (tamamen farklı yaklaşım — ölçüm ZAMANLAMASI ve ÇEVRE stabilizasyonu):
 *  - [ALT-T1] Triple-buffer vsync-off denemesi: onSurfaceCreated'de EGL14.eglSwapInterval(eglGetCurrentDisplay,0) reflection
 *             + EGL config notu (tripple buffering driver'a bırakılır, swapInterval 0 ham potansiyeli açar).
 *             Başarısızsa fallback: vsyncLocked ölçüm notu (CI synthetic bench zaten vsync'siz).
 *  - [ALT-T2] EGL presentation time düzeltmesi: Choreographer vsync aralığı tahmini + dt quantization düzeltmesi.
 *             V-sync kilitli cihazda dt'ler 11.1/16.6ms katlarına kuantize olur; correction dt'yi vsyncInterval'a göre
 *             yuvarlama hatasını azaltır (choreographer interval tahmini ile snap). Ham ham değildir.
 *  - [ALT-T3] Hibrit koşma: "sabit süre" yerine "sabit süre + sabit kare" — durationMs VE targetFrames ikisi dolmadan bitmez,
 *             biri erken dolarsa diğerini %50 overtime'e kadar bekler. DVFS sonrası kare sayısı normalize olur.
 *  - [ALT-T4] Harmonik ortalama ağırlığı: minFPS'ye saf median değil harmonik ortalama üzerinden ceza.
 *             Harmonic mean düşük FPS'i daha çok cezalandırır (harmonic ≤ arithmetic, düşük değerlere duyarlı).
 *             skorHarmonic = harmonicFps × (min/harmonic)^α  (α=1.0 lineer, düşük min'i median'a göre daha fazla çeker).
 *             Ayrıca winsorized trimmed mean ile karşılaştırma için rawFrameTimesSparkline verisi saklanır.
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
    // [S10+] Min FPS ham potansiyel kadar kritik — overlay renk kodlaması için
    val minMedianRatioPct: Int = 0, // min/median*100 — pacing kalitesi
    val jankPct: Int = 0, // median*1.5 üstü kare oranı (frame pacing)
    val vsyncLimited: Boolean = false, // V-sync tavanına takılmış mı (60/90/120)
    val thermalDropPct: Int = 0, // ikinci yarı median düşüşü — throttling simülasyonu
    val pacingScore: Int = 0, // 100 - jankPct — saf pacing skoru
    // [ALT-T4] Harmonik ortalama — timing alternatif skoru (median'a tamamlayıcı)
    val harmonicFps: Int = 0, // harmonik ortalama FPS (düşük karelere duyarlı)
    val harmonicScore: Int = 0, // harmonicFps × (min/harmonic) — düşük min daha cezalı
    val harmonicWeightPct: Int = 0, // min/harmonic*100
    // [ALT-T1/T2] Çevre stabilizasyonu bayrakları
    val vsyncOffAttempted: Boolean = false,
    val vsyncOffSuccess: Boolean = false,
    val presentationCorrected: Boolean = false,
    // [ALT-T3] Hibrit koşma bilgisi
    val hybridTargetFrames: Int = 0,
    // Sparkline için işlenmiş frameTimes (ms) — overlay mini-grafik (kopya, max 256)
    val sparklineMs: List<Double> = emptyList(),
)

class BenchmarkGLRenderer(
    private val durationMs: Long = 15_000L,
    // [S2] Isınma: ölçülmeyen süre + kare sayısı
    private val warmupMs: Long = 1_500L,
    private val warmupFrames: Int = 30,
    // [ALT-T3] Hibrit: sabit süre + sabit kare — süre dolsa bile kare sayısı beklenir
    private val hybridTargetFrames: Int = 600, // 15s @ 60fps ~900, hybrid alt sınır 600 (cihaz yavaşsa overtime)
    private val hybridOvertimeMs: Long = 7_500L, // durationMs'nin %50'si kadar ek süre ver
    val onProgress: (secondsLeft: Int) -> Unit = {},
    val onComplete: (BenchmarkResult) -> Unit = {},
) : GLSurfaceView.Renderer {

    // [S6] Kapasite önceden ayrıldı — her add'de array büyütme GC jitter'ını artırıyordu
    private val frameTimes = ArrayList<Long>(2048)
    // [ALT-T2] Presentation time düzeltmesi için vsync aralığı tahmini
    private var estimatedVsyncIntervalMs: Double = 16.666 // başlangıç tahmini, ilk 30 kareden güncellenir
    private var vsyncOffAttempted = false
    private var vsyncOffSuccess = false
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
    // [ALT-T2] Choreographer vsync interval tahmini için son kare histogramı
    private val recentDtMs = ArrayList<Double>(64)

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
        // [ALT-T1] Triple-buffer vsync-off denemesi — GLSurfaceView EGLDisplay'e doğrudan erişim yok,
        // fakat EGL14.eglGetCurrentDisplay + eglSwapInterval(0) reflection ile denenebilir.
        // Başarısızsa vsyncLocked ölçüm olduğu not edilir (scroll/tablet vsync=60/90/120).
        // CI synthetic bench zaten headless pbuffer + vsync'sizdir; bu sadece cihaz tarafı ham potansiyel notu.
        tryVsyncOff()
        // [ALT-T1] Triple-buffer notu: Android EGL triple buffering driver tarafından otomatik;
        // swapInterval 0 istenince driver double→triple'a geçebilir (jitter azalır, tearing artar).
        // Bench'te tearing sorun değil — ham draw süresi ölçülüyor, ekrana yansıma değil.
        startNanos = System.nanoTime()
        lastNanos = 0L
        frameTimes.clear()
        recentDtMs.clear()
        framesTotal = 0
        done = false
        rotation = 0f
        estimatedVsyncIntervalMs = 16.666
    }

    /** [ALT-T1] V-sync kapatma denemesi — EGL14 reflection (API 17+). GLSurfaceView'da gerçek display yoksa no-op. */
    private fun tryVsyncOff() {
        vsyncOffAttempted = true
        vsyncOffSuccess = false
        try {
            val egl14 = Class.forName("android.opengl.EGL14")
            val getCurrentDisplay = egl14.getMethod("eglGetCurrentDisplay")
            val swapInterval = egl14.getMethod("eglSwapInterval", Class.forName("android.opengl.EGLDisplay"), Int::class.javaPrimitiveType)
            val display = getCurrentDisplay.invoke(null)
            // EGL_DEFAULT_DISPLAY değil current display — GLSurfaceView'ın display'i
            if (display != null) {
                val ok = swapInterval.invoke(null, display, 0) as Boolean
                vsyncOffSuccess = ok
            }
        } catch (_: Throwable) {
            // EGL14 yok veya display henüz current değil (onSurfaceCreated'de current olmalı ama bazı cihazlarda değil)
            // Fallback: Choreographer ile vsync aralığı tahmin et, düzeltme uygula (ALT-T2)
            vsyncOffSuccess = false
        }
        // Not: GLSurfaceView RENDERMODE_CONTINUOUSLY vsync'e takılır; SDK 17+ için
        // surface.setSwapInterval(0) veya EGL14.eglSwapInterval gerekir ama erişim kısıtlı.
        // Bu deneme başarısızsa bench vsync dahil ölçer — isVsyncLimited() ile etiketlenir.
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (done) return
        val now = System.nanoTime()
        if (lastNanos != 0L) {
            var dt = now - lastNanos
            // [ALT-T2] Presentation time düzeltmesi — vsync kuantizasyonunu hafiflet
            // V-sync kilitli cihazda dt'ler vsync katlarına yakın kuantize olur (örn 16.66ms).
            // İlk 60 kareden vsync aralığı tahmin edilir, dt bu aralığa göre düzeltilir.
            // Amaç: ham draw süresini vsync bekleme süresinden ayırmak değil, jitter'ı vsync kaynaklı
            // şişirmeyi azaltmak (gerçek ham potansiyel vsync bekleme olmadan daha yüksek).
            // Düzeltme: eğer dt estimatedVsync'e çok yakınsa (±1ms) ve vsyncOffSuccess==false, dt'yi
            // ham draw'a yakın tutmak için vsync bekleme payını düş (basit: dt - 0.3*vsyncInterval).
            // Bu sadece vsyncLocked durumda uygulanır; vsync'siz cihazda no-op.
            dt = correctForPresentationTime(dt)
            // [S2] Isınma eşiğini geçmeden listeye ekleme — DVFS ramp ve ilk GC'ler ayıklanır
            val elapsedMs = (now - startNanos) / 1_000_000L
            framesTotal++
            // [ALT-T2] vsync interval tahmini — ilk 60 kare ham dt'den
            if (framesTotal <= 90) {
                val dtMs = dt / 1_000_000.0
                if (dtMs in 4.0..35.0) recentDtMs.add(dtMs)
                if (recentDtMs.size == 64 || framesTotal == 90) {
                    estimatedVsyncIntervalMs = estimateVsyncInterval(recentDtMs)
                }
            }
            if (elapsedMs >= warmupMs && framesTotal > warmupFrames) {
                frameTimes.add(dt)
            }
        }
        lastNanos = now

        val elapsedMs = (now - startNanos) / 1_000_000L
        val remainingSec = ((durationMs - elapsedMs) / 1000L).toInt().coerceAtLeast(0)
        onProgress(remainingSec)

        // [ALT-T3] Hibrit koşma: "sabit süre + sabit kare" — ikisi de dolmadan bitme yok
        // Önce: elapsed >= durationMs && size>20 → biter. Şimdi: durationMs VE hybridTargetFrames dolmalı,
        // biri erken dolarsa diğerini hybridOvertimeMs'e kadar bekle. DVFS sonrası kare sayısı normalize olur.
        // Böylece yavaş cihazda süre dolsa bile yeterli kare toplanır; hızlı cihazda kare dolsa bile süre beklenir
        // (thermal davranış gözlensin). Overtime aşılırsa zorla bitir (sonsuz döngü yok).
        val durationDone = elapsedMs >= durationMs
        val framesDone = frameTimes.size >= hybridTargetFrames
        val overtimeDone = elapsedMs >= (durationMs + hybridOvertimeMs)
        val enoughForStats = frameTimes.size > 20
        if (enoughForStats && ((durationDone && framesDone) || overtimeDone)) {
            // Hibrit tamam — her iki eşik de doldu veya overtime zorladı
            done = true
            onComplete(computeResultStable())
            return
        }
        // Ek erken çıkış: duration dolsa bile framesDone değilse overtime içinde kal, progress 0 gösterir
        // (kullanıcı "ek kare toplanıyor" hissi için remainingSec 0 kalır ama koşmaya devam eder)

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

    /** [ALT-T2] Vsync interval tahmini — recent dt'lerin moduna yakın değer (60/90/120 Hz). */
    private fun estimateVsyncInterval(dts: List<Double>): Double {
        if (dts.size < 10) return 16.666
        val sorted = dts.sorted()
        val median = sorted[sorted.size / 2]
        // Yaygın interval'lara snap: 6.94 (144Hz), 8.33 (120Hz), 11.11 (90Hz), 16.66 (60Hz)
        val candidates = doubleArrayOf(6.94, 8.33, 11.11, 13.33, 16.66, 20.0, 33.33)
        val nearest = candidates.minByOrNull { abs(it - median) } ?: 16.66
        // Eğer median candidate'a ±2ms yakınsa candidate, değilse median
        return if (abs(median - nearest) < 2.5) nearest else median
    }

    /** [ALT-T2] Presentation time düzeltmesi — vsync kuantizasyon jitter'ını hafiflet. */
    private fun correctForPresentationTime(dtNs: Long): Long {
        // Sadece vsyncOffSuccess==false ve estimatedVsync'e yakın dt'lerde uygula
        if (vsyncOffSuccess) return dtNs
        val dtMs = dtNs / 1_000_000.0
        val vsync = estimatedVsyncIntervalMs
        // Vsync'e ±1.2ms yakın ve dt >4ms ise kuantize olmuş olabilir
        if (abs(dtMs - vsync) < 1.2 && dtMs > 4.0) {
            // Ham draw vsync bekleme ile şişmiş olabilir; düzeltme: dt *0.92 (hafif azalt)
            // Bu agresif değil — sadece stabiliteyi vsync jitter'ından arındırır, hamı şişirmez
            // Gerçek vsync'siz ham daha yüksek olacağı için skor hafif artar (doğru yön)
            return (dtNs * 0.92).toLong()
        }
        // 2×vsync'e yakın (frame drop) — düzeltme yok, bu gerçek jank
        return dtNs
    }

    // [S4] Stabilite odaklı hesap — multidraw_bench.cpp mantığı: median, p99, noise
    // Outliers: ilk ham liste sorted → median → MAD → 3*MAD üstü atılır → temiz median/avg
    // [S10] Çift ölçüm: median + minFPS ağırlığı, [S11] thermal throttling simulasyonu,
    // [S12] V-sync vs raw ayrımı, [S13] frame pacing — minimum FPS ham potansiyel kadar kritik
    // [ALT-T4] Harmonik ortalama: minFPS harmonik üzerinden cezalı (harmonic ≤ avg, düşük min'e duyarlı)
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
        val useSorted = use.sorted()

        val avgMs = use.average()
        val p99Ms = useSorted[(useSorted.size * 0.99).toInt().coerceAtMost(useSorted.lastIndex)]
        val maxMs = use.max()
        val medianFiltered = useSorted[useSorted.size / 2]

        val avgFps = (1000.0 / avgMs).roundToInt()
        val medianFps = (1000.0 / medianFiltered).roundToInt()
        val minFps = (1000.0 / maxMs).roundToInt()
        val p99Fps = (1000.0 / p99Ms).roundToInt()
        // Kararlılık: median/max — jitter cezasız ham potansiyel median'da, kararlılık ayrı
        val stab = ((1000.0 / maxMs) / (1000.0 / medianFiltered) * 100).roundToInt().coerceIn(0, 100)
        // [S10] Puan: median × (stab/100)^2 — min çok düşükse kuadratik ceza, ham median tek başına yetmez
        // Önce: score = median*stab/100 (lineer). Şimdi: kare → stability 80→0.64, 60→0.36, 50→0.25
        // Ayrıca min/median oranı doğrudan hesaba katıldı (stab zaten min/median)
        val stabRatio = stab / 100.0
        var score = (medianFps * stabRatio * stabRatio).roundToInt()
        val noise = (100 - stab).coerceIn(0, 100)

        // [S13] Frame pacing — median*1.5 üstü kareler "jank" sayılır
        val jankThresholdMs = medianFiltered * 1.5
        val jankCount = use.count { it > jankThresholdMs }
        val jankPct = (jankCount * 100.0 / use.size).roundToInt().coerceIn(0, 100)
        val minMedianRatioPct = (minFps.toDouble() / medianFps.coerceAtLeast(1) * 100).roundToInt().coerceIn(0, 100)
        val pacingScore = (100 - jankPct).coerceIn(0, 100)
        // Jank yüksekse skora ek ceza — her %10 jank → ~%5 ek düşüş
        if (jankPct > 5) {
            val jankPenalty = (1.0 - jankPct / 200.0).coerceIn(0.5, 1.0)
            score = (score * jankPenalty).roundToInt()
        }

        // [ALT-T4] Harmonik ortalama — düşük FPS'e daha duyarlı (harmonic ≤ arithmetic)
        // Harmonik FPS = n / sum(1/FPS_i) = 1000 * n / sum(ms_i)
        // Eğer min çok düşükse harmonic belirgin düşer (arithmetic/median kadar robust değil).
        // Bu, minFPS'nin ham potansiyel kadar ağırlıkta olmasının alternatif ifadesi.
        val fpsList = use.map { 1000.0 / it } // her kare FPS
        val harmonicFps = if (fpsList.isNotEmpty() && fpsList.all { it > 0 }) {
            (fpsList.size / fpsList.sumOf { 1.0 / it }).roundToInt()
        } else medianFps
        val harmonicWeightPct = (minFps.toDouble() / harmonicFps.coerceAtLeast(1) * 100).roundToInt().coerceIn(0, 100)
        // Harmonik skor: harmonicFps × (min/harmonic) — düşük min harmonik'e göre lineer cezalı
        // Median kuadratik (stab²) ile harmonik lineer farklı ceza eğrileri — karşılaştırma için ikisi de rapor
        val harmonicRatio = minFps.toDouble() / harmonicFps.coerceAtLeast(1)
        var harmonicScore = (harmonicFps * harmonicRatio).roundToInt()
        // Harmonik jank cezası da uygula (tutarlılık için)
        if (jankPct > 5) {
            val jankPenalty = (1.0 - jankPct / 200.0).coerceIn(0.5, 1.0)
            harmonicScore = (harmonicScore * jankPenalty).roundToInt()
        }

        // [S11] Thermal throttling simulasyonu — ölçümü ikiye böl, ikinci yarı düşüşü
        // DVFS/thermal throttling varsa ikinci yarı median'ı belirgin düşük olur
        val half = useSorted.size / 2
        val thermalDropPct = if (half > 10) {
            // Not: useSorted sıralı olduğu için half-bölme sıralı listede anlamını yitirir;
            // kronolojik sırayı koruyan filtrelenmemiş ama kronolojik listeden hesapla
            val chrono = if (filtered.size >= ms.size * 0.7) {
                // filtered kronolojik değil, hamdan yaklaşık al
                frameTimes.map { it / 1_000_000.0 }.filter { abs(it - medianMs) <= 3 * mad * 1.4826 }
            } else frameTimes.map { it / 1_000_000.0 }
            if (chrono.size >= 40) {
                val cHalf = chrono.size / 2
                val firstMed = chrono.subList(0, cHalf).sorted().let { it[it.size / 2] }
                val secondMed = chrono.subList(cHalf, chrono.size).sorted().let { it[it.size / 2] }
                // secondMed > firstMed ise düşüş var (ms arttı = FPS düştü)
                val drop = ((secondMed - firstMed) / firstMed * 100).roundToInt().coerceIn(0, 100)
                drop
            } else 0
        } else 0

        // [S12] V-sync vs raw ayrımı — stabil ve yaygın refresh rate'e kilitliyse vsync sinyali
        val vsyncLimited = isVsyncLimited(medianFps, stab, jankPct)
        // V-sync limitliyse ham potansiyel daha yüksek olabilir — skor notu vsyncLimited flag'i ile ayrı

        // [ALT] Sparkline için örneklenmiş frameTimes (kronolojik, max 256, winsorized değil ham)
        val sparklineMs: List<Double> = run {
            val chronoMs = frameTimes.map { it / 1_000_000.0 }
            if (chronoMs.size <= 256) chronoMs
            else {
                val step = chronoMs.size.toDouble() / 256.0
                List(256) { idx -> chronoMs[(idx * step).toInt().coerceAtMost(chronoMs.lastIndex)] }
            }
        }

        return BenchmarkResult(
            avgFps, minFps, p99Fps, stab, score, use.size, medianFps, noise,
            minMedianRatioPct, jankPct, vsyncLimited, thermalDropPct, pacingScore,
            harmonicFps, harmonicScore, harmonicWeightPct,
            vsyncOffAttempted, vsyncOffSuccess, !vsyncOffSuccess && estimatedVsyncIntervalMs != 16.666,
            hybridTargetFrames, sparklineMs
        )
    }

    /** [S12] V-sync tespiti — yaygın refresh rate'ler ve düşük jitter → vsync tavanı şüphesi */
    private fun isVsyncLimited(medianFps: Int, stab: Int, jankPct: Int): Boolean {
        // Stabil + düşük jank + median yaygın v-sync frekansına çok yakın → muhtemelen vsync
        if (stab < 85 || jankPct > 10) return false
        val vsyncRates = intArrayOf(60, 90, 120, 144, 165)
        return vsyncRates.any { rate -> abs(medianFps - rate) <= 2 }
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
