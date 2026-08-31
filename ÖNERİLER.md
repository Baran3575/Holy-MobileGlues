# Öneriler — ne eklenebilir? (varsayım yok, mantıklı)

Bu liste sadece **mevcut kodla doğrulanabilir** şeylerden seçildi — her madde bir dosyaya / API'ye dayanıyor.

## 1) Hemen eklenebilir (risk yok)

| Öneri | Dayanak | Neden mantıklı |
|---|---|---|
| **Thermal/DVFS uyarısı** | `BenchmarkGLRenderer.kt` `stabilityPct = min/avg*100` zaten ölçülüyor; düşükse (<%70) overlay'de "cihaz ısındı, kılıfı çıkar, 2 dk bekle" banner | Plus'ta `stability` var ama uyarı yok; MobileGlues `multidraw_bench.cpp:15` noise>±15% ise retry yapıyor — aynı mantığın kullanıcıya gösterilmesi |
| **Tek-tık uygula** | `RendererSettingsScreen` zaten `RendererInterface` seçiyor; `RESULTS` fazında kazananın yanında "Uygula" butonu → `settings.putString("renderer", id)` | Plus'ta sonuç sadece gösteriliyor, uygulanmıyor — Holy'de kullanıcı kazananı elle aramamalı |
| **Sonuç paylaş / kaydet** | `BenchmarkResult` → `JSON` (`avg/min/p99/stab/score/frameCount` + `Build.MODEL` + `GLES version`) + `ACTION_SEND` | DeviceReports/ için veri toplamak release fork'un varoluş sebebi; paylaşılabilir JSON olmadan rapor toplanamaz |
| **Düşük cihaz modu** | `BenchmarkGLRenderer(durationMs=8000, repeat=250)` — 2 GB RAM / Mali-400'de 500×15sn ANR | Plus sabit 500×15sn; `bench/multidraw_bench.cpp` zaten `BENCH_MIN/MAX_FRAMES` ile ölçekliyor — aynı fikrin Android tarafı |
| **`benchmark_progress_of` düzeltmesi** | Plus TR `"%2$d üzerinden %1$d"` ters; Holy'de `"%1$d / %2$d"` yapıldı | Kullanıcı "1 / 5" yerine "5 üzerinden 1" görüyordu — TR hatası düzeltildi |

## 2) Bir sonraki sprint (kod var, bağlanması lazım)

| Öneri | Dayanak | Not |
|---|---|---|
| **ANGLE açık/kapalı karşılaştırması** | `MobileGlues-plugin/MGBench.kt:20` `angleDirectory: String?` + `MGInfoGetter.setenv("MG_ANGLE_DIR")`; benchmark overlay'e "ANGLE ile test et" checkbox | Holy zaten ANGLE opsiyonu sunuyor (`option_angle`); renderer'ı iki kez koşmak gerçek farkı gösterir |
| **Native multidraw bench'i Android overlay'e bağlama** | `MGBench.run(mgDirectory, angleDirectory)` → `mg_multidraw_bench_run()` JSON (8 sn, median, noise) | Plus'un GLSurfaceView bench'i draw-call'u ölçer; MobileGlues'un native bench'i **hangi `glMultiDraw*` stratejisinin** hızlı olduğunu ölçer — ikisi farklı, ikisi de lazım |
| **Geçmiş karşılaştırma** | `bench-results/synth_bench.json` CI'da, cihazda `SharedPreferences` JSONL | PR `benchmark.yml` zaten `benchstat` öneriyor; cihazda da "önceki koşu vs şimdi" gerekir |
| **Sürücü değişti uyarısı** | `MobileGlues-plugin/strings.xml: md_bench_outdated` zaten var | Holy'de `GLES version` değişince bench'i öner — Plus'ta yok |

## 3) Eklenmemeli (varsayım olur)

- **Vulkan bench:** Holy/MobileGlues GLES → Vulkan değil, `gpu_benchmark_vulkan` string'i Plus'ta ölü kod.
- **FPS'i doğrudan oyun FPS'i gibi göstermek:** Overlay "gerçek Minecraft FPS" değil, `500 dörtgen` sentetik — `benchmark_note` bunu zaten söylüyor, daha fazlası yanıltır.
- **CI'da gerçek GPU skoru:** Lavapipe mutlak değil, sadece PR delta — "cihazda 60 FPS" gibi sayı CI'dan gelmez.

## Uygulama sırası önerisi

1. Stabilite uyarısı + düşük cihaz modu (30 dk)
2. Tek-tık uygula + paylaş (1 saat)
3. ANGLE karşılaştırması + native bench hook (MGBench) (yarım gün)
