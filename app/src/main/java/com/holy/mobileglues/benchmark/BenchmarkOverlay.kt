/*
 * Holy MobileGlues — BenchmarkOverlay (Plus RendererBenchmarkOverlay portu)
 * Kaynak: Star1xr/ZalithLauncher2Plus RendererBenchmarkOverlay.kt (480 satır)
 * Farklar:
 *  - RendererInterface yerine HolyRenderer (= MobileGlues Plugin'in RendererInterface'i veya basit data class)
 *  - Türkçe string key'leri düzeltildi: benchmark_progress_of sıralaması, Stab%% → Kararlılık
 *  - Plus'taki ölü BenchmarkRow composable'ı çıkarıldı
 *  - CANONICAL: Holy plugin'i MobileGlues-plugin gibi tek renderer ise SELECTING atlanır (tek koşu)
 */

package com.holy.mobileglues.benchmark

import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import com.holy.mobileglues.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Holy'nin renderer soyutlaması. MobileGlues-plugin'de RendererInterface yok;
 * plugin tek renderer (libmobileglues.so). Karşılaştırma için ANGLE açık/kapalı
 * veya farklı MobileGlues sürümleri HolyRenderer olarak eklenir.
 *
 * OpenGL + OpenGL-over-Vulkan için: RendererBackend → HolyRenderer dönüşümü
 * `RendererSystem.enumerate()` ile otomatik — Direct GLES her cihazda, ANGLE Vulkan varsa eklenir.
 */
data class HolyRenderer(
    val name: String,
    val summary: String? = null,
    // İsteğe bağlı: bu renderer'ı uygulayacak env (örn. "MG_ANGLE_DIR=/data/app/.../lib/arm64")
    val env: Map<String, String> = emptyMap(),
    // Kusursuz: backend tipi — Direct mi ANGLE Vulkan mı? (karşılaştırma başlığı için)
    val backendId: String = "direct_gles",
) {
    companion object {
        /** RendererBackend → HolyRenderer — OpenGL / OpenGL-over-Vulkan ikilisi */
        fun fromBackend(backend: com.holy.mobileglues.renderer.RendererBackend): HolyRenderer =
            HolyRenderer(name = backend.displayName, summary = backend.summary, env = backend.toEnv(), backendId = backend.id)

        /** Context'ten otomatik liste — her cihazda en az 1, ANGLE varsa 2 */
        fun autoList(context: android.content.Context): List<HolyRenderer> =
            com.holy.mobileglues.renderer.RendererSystem.enumerate(context).map { fromBackend(it) }
    }
}

data class HolyBenchmarkResult(
    val rendererName: String,
    val result: BenchmarkResult,
)

private enum class BenchmarkPhase { SELECTING, RUNNING, RESULTS }

@Composable
fun BenchmarkOverlay(
    availableRenderers: List<HolyRenderer>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(BenchmarkPhase.SELECTING) }
    val selected = remember { mutableStateListOf<HolyRenderer>().also { it.addAll(availableRenderers) } }
    val results = remember { mutableStateListOf<HolyBenchmarkResult>() }
    var currentIndex by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(15) }

    // Holy tek renderer ise doğrudan RUNNING'e geç (seçim ekranı gereksiz)
    // Çağıran taraf yine SELECTING'i gösterebilir; bu optimizasyon opsiyonel.
    Box(modifier = Modifier.fillMaxSize()) {
        if (phase == BenchmarkPhase.RUNNING && currentIndex < selected.size) {
            key(currentIndex) {
                val rendererName = selected[currentIndex].name
                val glRenderer = remember {
                    BenchmarkGLRenderer(
                        durationMs = 15_000L,
                        onProgress = { s -> secondsLeft = s },
                        onComplete = { r ->
                            results.add(HolyBenchmarkResult(rendererName, r))
                            currentIndex++
                            if (currentIndex >= selected.size) phase = BenchmarkPhase.RESULTS
                            else secondsLeft = 15
                        },
                    )
                }
                val glView = remember {
                    GLSurfaceView(context).apply {
                        setEGLContextClientVersion(2)
                        setRenderer(glRenderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                }
                DisposableEffect(Unit) {
                    glView.onResume()
                    onDispose { glView.onPause() }
                }
                AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (phase == BenchmarkPhase.RUNNING) 0.5f else 0.85f)),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(id = R.string.benchmark_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            when (phase) {
                BenchmarkPhase.SELECTING -> SelectingPhase(
                    availableRenderers = availableRenderers,
                    selected = selected,
                    onStart = {
                        results.clear()
                        currentIndex = 0
                        secondsLeft = 15
                        phase = BenchmarkPhase.RUNNING
                    },
                    onDismiss = onDismiss,
                )
                BenchmarkPhase.RUNNING -> RunningPhase(
                    rendererName = if (currentIndex < selected.size) selected[currentIndex].name else "",
                    currentIndex = currentIndex,
                    totalCount = selected.size,
                    secondsLeft = secondsLeft,
                    onCancel = onDismiss,
                )
                BenchmarkPhase.RESULTS -> ResultsPhase(
                    results = results,
                    onDismiss = onDismiss,
                    onRunAgain = {
                        results.clear()
                        currentIndex = 0
                        secondsLeft = 15
                        phase = BenchmarkPhase.SELECTING
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectingPhase(
    availableRenderers: List<HolyRenderer>,
    selected: MutableList<HolyRenderer>,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
        Column(modifier = Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.benchmark_select_renderers), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = {
                    if (selected.size == availableRenderers.size) selected.clear()
                    else { selected.clear(); selected.addAll(availableRenderers) }
                }) {
                    Text(if (selected.size == availableRenderers.size) stringResource(R.string.benchmark_deselect_all) else stringResource(R.string.benchmark_select_all))
                }
            }
            HorizontalDivider()
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                availableRenderers.forEach { renderer ->
                    val checked = selected.contains(renderer)
                    Row(modifier = Modifier.fillMaxWidth().clickable { if (checked) selected.remove(renderer) else selected.add(renderer) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { on -> if (on) selected.add(renderer) else selected.remove(renderer) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(renderer.name, style = MaterialTheme.typography.bodyMedium)
                            renderer.summary?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = selected.isEmpty()) {
                Text(stringResource(R.string.benchmark_select_minimum), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
        Button(onClick = onStart, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.benchmark_start)) }
    }
}

@Composable
private fun RunningPhase(
    rendererName: String,
    currentIndex: Int,
    totalCount: Int,
    secondsLeft: Int,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.benchmark_running_renderer, rendererName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(stringResource(R.string.benchmark_progress_of, currentIndex + 1, totalCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            LinearProgressIndicator(progress = { currentIndex.toFloat() / totalCount.toFloat() }, modifier = Modifier.fillMaxWidth())
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.benchmark_running, secondsLeft), color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.benchmark_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
    }
    Button(onClick = onCancel) { Text(stringResource(R.string.generic_cancel)) }
}

/** [ALT] Frame time sparkline — minimum FPS için mini-grafik (frameTimes ms) */
@Composable
private fun FrameTimeSparkline(
    frameTimesMs: List<Double>,
    medianMs: Double,
    modifier: Modifier = Modifier,
) {
    if (frameTimesMs.isEmpty()) return
    val minMs = frameTimesMs.minOrNull() ?: 0.0
    val maxMs = frameTimesMs.maxOrNull() ?: 1.0
    val range = (maxMs - minMs).coerceAtLeast(0.5)
    val medianY = ((medianMs - minMs) / range).toFloat()
    Canvas(modifier = modifier.height(28.dp).fillMaxWidth().background(Color(0xFF1A1A2E), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(2.dp)) {
        val w = size.width
        val h = size.height
        // median çizgisi
        drawLine(
            color = Color(0xFF4CAF50).copy(alpha = 0.6f),
            start = Offset(0f, h * (1f - medianY)),
            end = Offset(w, h * (1f - medianY)),
            strokeWidth = 1f
        )
        // sparkline path
        if (frameTimesMs.size >= 2) {
            val path = Path()
            frameTimesMs.forEachIndexed { idx, ms ->
                val x = idx.toFloat() / (frameTimesMs.size - 1).toFloat() * w
                val y = h * (1f - ((ms - minMs) / range).toFloat())
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = Color(0xFF90CAF9), style = Stroke(width = 1.6f))
            // jank eşiği üstü noktalar kırmızı
            val jankThresh = medianMs * 1.5
            frameTimesMs.forEachIndexed { idx, ms ->
                if (ms > jankThresh) {
                    val x = idx.toFloat() / (frameTimesMs.size - 1).toFloat() * w
                    val y = h * (1f - ((ms - minMs) / range).toFloat())
                    drawCircle(color = Color(0xFFE53935), radius = 1.8f, center = Offset(x, y))
                }
            }
        }
    }
}

@Composable
private fun ResultsPhase(
    results: List<HolyBenchmarkResult>,
    onDismiss: () -> Unit,
    onRunAgain: () -> Unit,
) {
    val maxScore = results.maxOfOrNull { it.result.score }?.takeIf { it > 0 } ?: 1
    val sorted = results.sortedByDescending { it.result.score }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.benchmark_comparison_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            sorted.forEach { r ->
                val isWinner = r.result.score == maxScore && results.size > 1
                // Stabilite düşükse ham potansiyel gizlenmiş — kullanıcıya göster
                val isUnstable = r.result.stabilityPct < 70
                // [S10+] Min FPS ham potansiyel kadar kritik — renk kodlama
                val minRatio = r.result.minMedianRatioPct.takeIf { it != 0 } ?: run {
                    val med = r.result.medianFps.coerceAtLeast(1)
                    (r.result.minFps * 100 / med).coerceIn(0, 100)
                }
                val isSevereMin = minRatio < 60
                val isModerateMin = minRatio in 60..74
                val isGoodMin = minRatio >= 85
                val jank = r.result.jankPct
                val isHighJank = jank > 10
                val vsync = r.result.vsyncLimited
                val thermalDrop = r.result.thermalDropPct
                val isThermal = thermalDrop > 12
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        val suffix = buildString {
                            if (isWinner) append(" 🏆")
                            if (isUnstable) append(" ⚠️")
                            if (isSevereMin) append(" 🧊")
                            if (vsync) append(" 🔒")
                        }
                        Text(text = r.rendererName + suffix, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val scoreColor = when {
                            isSevereMin || isUnstable -> MaterialTheme.colorScheme.error
                            isModerateMin || isHighJank -> Color(0xFF9C5700) // amber — dikkat
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Text(stringResource(R.string.benchmark_score_label, r.result.score), style = MaterialTheme.typography.bodyMedium, color = scoreColor, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = { r.result.score.toFloat() / maxScore.toFloat() }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    // Min/median bar — pacing görseli (kuadratik skorun kaynağı)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Min/Med", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        val minColor = when {
                            isSevereMin -> MaterialTheme.colorScheme.error
                            isModerateMin -> Color(0xFF9C5700)
                            isGoodMin -> Color(0xFF2E7D32)
                            else -> MaterialTheme.colorScheme.outline
                        }
                        Box(modifier = Modifier.weight(1f).height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(modifier = Modifier.fillMaxWidth(minRatio / 100f).height(6.dp).background(minColor))
                        }
                        Text("$minRatio%", style = MaterialTheme.typography.labelSmall, color = minColor, fontWeight = FontWeight.Bold)
                        if (jank > 0) Text("jank $jank%", style = MaterialTheme.typography.labelSmall, color = if (isHighJank) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                    }
                    // Uyarılar — hiyerarşi: thermal > severe min > instability > jank > vsync
                    if (isSevereMin) {
                        Text("⛔ Minimum FPS çok düşük (%$minRatio, ${r.result.minFps} FPS) — kare takılmaları oynanışı bozar. Ham median ${r.result.medianFps} yüksek olsa da skor kuadratik cezalı (median×stab²). Soğut, arka planı kapat, tekrar dene.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    } else if (isModerateMin) {
                        Text("⚠️ Minimum FPS dalgalı (%$minRatio, min ${r.result.minFps} vs med ${r.result.medianFps}) — ara sıra takılma var. Skor min/median ile cezalı.", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9C5700))
                    }
                    if (isUnstable) {
                        Text("Kararlılık düşük (%${r.result.stabilityPct}, noise %${r.result.noisePct}) — sonuç ısınma/DVFS'ten etkilenmiş olabilir. Kılıfı çıkar, 2 dk bekle, tekrar dene.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (isHighJank) {
                        Text("Frame pacing bozuk — karelerin %$jank'i median*1.5 üstünde (takılma). Pacing skoru %${r.result.pacingScore}.", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9C5700))
                    }
                    if (isThermal) {
                        Text("🌡️ Thermal throttling şüphesi — ikinci yarı %$thermalDrop daha yavaş. Cihaz ısınmış; soğutup tekrar dene.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (vsync) {
                        Text("🔒 V-sync tavanına takılı görünüyor (≈${r.result.medianFps} FPS, stab %${r.result.stabilityPct}) — ham GPU potansiyeli daha yüksek olabilir; skor vsync dahil.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    // [ALT] Harmonic skor bilgisi — median kuadratik vs harmonic lineer karşılaştırması
                    if (r.result.harmonicFps > 0) {
                        val harmColor = if (r.result.harmonicWeightPct < 70) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        Text("〰 Harmonic: ${r.result.harmonicFps} FPS (min/harm ${r.result.harmonicWeightPct}%, skor ${r.result.harmonicScore}) — düşük karelere daha duyarlı, median ${r.result.medianFps} ile karşılaştır", style = MaterialTheme.typography.labelSmall, color = harmColor)
                    }
                    if (r.result.vsyncOffAttempted) {
                        val vsyncOffText = if (r.result.vsyncOffSuccess) "✓ V-sync kapatma denendi ve başarılı (swapInterval 0)" else "✗ V-sync kapatma denendi ama başarısız — ölçüm vsync dahil (ALT-T1 triple-buffer notu)"
                        Text(vsyncOffText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (r.result.presentationCorrected) {
                        Text("◐ Presentation time düzeltmesi uygulandı (vsync kuantizasyon ~%8 azaltıldı)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    // [ALT] Sparkline mini-grafik — frameTimes kronolojik
                    if (r.result.sparklineMs.isNotEmpty()) {
                        val sparkMs = r.result.sparklineMs
                        val medMs = 1000.0 / r.result.medianFps.coerceAtLeast(1)
                        FrameTimeSparkline(frameTimesMs = sparkMs, medianMs = medMs, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                        Text("frameTime sparkline — yeşil = median, kırmızı nokta = jank (median×1.5 üstü), ${sparkMs.size} kare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.benchmark_col_renderer), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.9f))
                listOf(R.string.benchmark_col_avg, R.string.benchmark_col_min, R.string.benchmark_col_p99, R.string.benchmark_col_stab).forEach { col ->
                    Text(stringResource(col), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
            sorted.forEach { r ->
                val minRatioRow = r.result.minMedianRatioPct.takeIf { it != 0 } ?: (r.result.minFps * 100 / r.result.medianFps.coerceAtLeast(1))
                val minCellColor = when {
                    minRatioRow < 60 -> MaterialTheme.colorScheme.error
                    minRatioRow < 75 -> Color(0xFF9C5700)
                    minRatioRow >= 85 -> Color(0xFF2E7D32)
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.rendererName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.result.medianFps}(${r.result.avgFps})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("${r.result.minFps}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = minCellColor, fontWeight = if (minRatioRow < 75) FontWeight.Bold else FontWeight.Normal)
                    Text("${r.result.p99Fps}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("${r.result.stabilityPct}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = if (r.result.stabilityPct < 70) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                // Ek satır: pacing/thermal/vsync detay — tabloyu şişirmeden tek satır
                if (r.result.jankPct > 0 || r.result.thermalDropPct > 0 || r.result.vsyncLimited) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("", modifier = Modifier.weight(1.9f))
                        Text("min/med ${minRatioRow}%  jank ${r.result.jankPct}%${if (r.result.vsyncLimited) "  vsync🔒" else ""}${if (r.result.thermalDropPct > 0) "  th ${r.result.thermalDropPct}%" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(3f), textAlign = TextAlign.Center)
                    }
                }
            }
            Text("Ort. = eski ortalama, parantez dışı = median (kararlı ham potansiyel). Min FPS hücre rengi: yeşil ≥85% / turuncu 60-74% / kırmızı <60% (min/median). Skor = median×(stab/100)² — min düşükse kuadratik ceza.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(stringResource(R.string.benchmark_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onRunAgain) { Text(stringResource(R.string.benchmark_run_again)) }
        Button(onClick = onDismiss) { Text(stringResource(R.string.generic_close)) }
    }
}
