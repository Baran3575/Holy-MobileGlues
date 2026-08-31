# Holy MobileGlues — app/ benchmark entegrasyonu

Bu klasör `Star1xr/ZalithLauncher2Plus` `BenchmarkGLRenderer.kt` tabanlı Holy benchmark'ını barındırır.

## Dosyalar

- `src/main/java/com/holy/mobileglues/benchmark/BenchmarkGLRenderer.kt` — 500 dörtgen × 15 sn, avg/min/p99/stability/score
- `src/main/java/com/holy/mobileglues/benchmark/BenchmarkOverlay.kt` — SELECTING / RUNNING / RESULTS (Plus portu, `HolyRenderer` ile)
- `src/main/res/values/strings_benchmark.xml` — TR varsayılan (Plus EN hatası düzeltildi: `benchmark_title` artık Türkçe)
- `src/main/res/values-tr/strings_benchmark.xml` — TR, Plus'taki `%2$d üzerinden %1$d` tersliği `%1$d / %2$d` yapıldı

## Entegrasyon (MobileGlues-plugin veya Holy app'e)

`RendererSettingsScreen.kt` / ayar ekranında:

```kotlin
var showBenchmark by remember { mutableStateOf(false) }
if (showBenchmark) {
    Dialog(usePlatformDefaultWidth = false) {
        BenchmarkOverlay(
            availableRenderers = listOf(
                HolyRenderer("Holy (varsayılan)", "MobileGlues + ANGLE kapalı"),
                HolyRenderer("Holy + ANGLE", "Borrow ANGLE", env = mapOf("MG_ANGLE_DIR" to angleDir)),
            ),
            onDismiss = { showBenchmark = false }
        )
    }
}
Button(onClick = { showBenchmark = true }) { Text(stringResource(R.string.benchmark_run)) }
```

Tek renderer (MobileGlues-plugin gibi) ise liste tek elemanlı — overlay yine çalışır, seçim ekranı tek satır gösterir.

## CI

- `benchmark.yml` `benchmark` job'u: lavapipe synthetic bench her zaman (PR & main), `has_holy_bench=true` ise strings/kt lint
- `benchmark-android` job'u: `has_holy_bench` ise `reactivecircus/android-emulator-runner` (API 30, swiftshader) ile `connectedAndroidTest`

Build local yok — tüm test Actions'ta.
