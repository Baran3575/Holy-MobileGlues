package com.holy.mobileglues

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.holy.mobileglues.benchmark.BenchmarkOverlay
import com.holy.mobileglues.benchmark.HolyRenderer
import com.holy.mobileglues.renderer.RendererSystem

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var showBenchmark by remember { mutableStateOf(false) }
                // OpenGL vs OpenGL-over-Vulkan — RendererSystem.enumerate ile otomatik
                val renderers = remember { HolyRenderer.autoList(this) }

                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Holy MobileGlues", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "arm64-v8a • OpenGL / OpenGL-over-Vulkan\n${renderers.joinToString { it.name }}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { showBenchmark = true }) {
                            Text("Benchmark Çalıştır")
                        }
                        Text(
                            "Stabilite: kuadratik skor, thermal/vsync/jank — STABILITE.md",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (showBenchmark) {
                        BenchmarkOverlay(
                            availableRenderers = renderers,
                            onDismiss = { showBenchmark = false }
                        )
                    }
                }
            }
        }
    }
}
