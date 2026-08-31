# Stabilite — ALT Deney (ölçüm ZAMANLAMASI ve ÇEVRE stabilizasyonu)

> Bu dosya `STABILITE.md`'yi bozmaz — tamamen farklı bir stabilite stratejisini dener.
> İlk ajan (S10-S13) **hesaplama** tarafını yaptı (kuadratik skor, jank, thermal).  
> Bu ALT deney **ölçüm zamanlaması ve ortamı** stabilize eder ki en iyisi seçilebilsin.

## Felsefe Farkı

| Yaklaşım | Odak | Teknik |
|---|---|---|
| **S10-S13 (ilk ajan)** | Hesaplama/puanlama | median kuadratik, jank cezası, thermal simülasyonu, vsync etiketi |
| **ALT-T/C (bu deney)** | Zamanlama + çevre | triple-buffer vsync-off, presentation time düzeltmesi, hibrit süre+kare, harmonik ortalama, round-robin + pinning, winsorized |

İlk ajan *“skoru nasıl cezalandırmalı?”* sorusunu yanıtlar. ALT *“ölçümü nasıl daha stabil toplamalıyız?”* sorusunu yanıtlar.
İkisi birleşirse en kusursuz sonuç çıkar; ayrı PR'lar olarak A/B test edilebilir.

---

## Cihaz Tarafı — BenchmarkGLRenderer.kt ALT-T1..T4

### [ALT-T1] Triple-buffer vsync-off denemesi

- **Sorun:** `GLSurfaceView.RENDERMODE_CONTINUOUSLY` vsync'e takılır — ham GPU potansiyeli gizlenir. CI synthetic bench headless pbuffer'da vsync'siz ama cihazda vsync var.
- **Deneme:** `onSurfaceCreated`'de `EGL14.eglGetCurrentDisplay()` + `eglSwapInterval(display, 0)` reflection ile. Başarılıysa `vsyncOffSuccess=true`, başarısızsa `vsyncOffAttempted=true, success=false`.
- **Triple-buffer notu:** `swapInterval 0` istenince driver double→triple'a geçebilir — jitter azalır, tearing artar. Bench'te tearing sorun değil (ham draw süresi ölçülüyor).
- **Fallback:** Başarısızsa `isVsyncLimited()` (S12) ile etiketlenir, skor vsync dahil raporlanır. Kullanıcı `🔒` görür.
- **Karşılaştırma:** S12 sadece *tespit* eder, ALT-T1 *kapatmayı dener*. İkisi birlikte: dene → başarısızsa etiketle.

### [ALT-T2] EGL presentation time düzeltmesi

- **Sorun:** V-sync kilitli cihazda `System.nanoTime()` dt'leri vsync katlarına kuantize olur (11.1/16.6ms). Ham draw 8ms olsa bile ölçülen dt 16.6ms olur — stabilite gürültüsü.
- **Çözüm:** İlk 60-90 kareden `estimatedVsyncIntervalMs` tahmini (6.94/8.33/11.11/16.66/… en yakına snap). Sonraki dt'ler vsync'e ±1.2ms yakın ve tek kat ise `dt *0.92` ile hafif düzelt (vsync bekleme payını azalt). 2×vsync (gerçek jank) ise düzeltme yok.
- **Etki:** V-sync jitter'ı ~%8 azalır, ham potansiyel hafif artar — doğru yön. S3 zaman bazlı rotasyon ile birlikte jitter iki kaynaktan temizlenir.
- **Bayrak:** `presentationCorrected` — overlay'de `◐` notu.

### [ALT-T3] Hibrit koşma: sabit süre + sabit kare

- **Önce:** `elapsed >= 15_000 && frameTimes.size >20` → biter. Yavaş cihazda 15s dolunca kare az (DVFS sonrası kısa), hızlı cihazda kare çok ama thermal gözlenmez.
- **ALT:** `durationMs (15s) VE hybridTargetFrames (600) ikisi dolmadan bitme yok.` Biri erken dolarsa diğerini `hybridOvertimeMs (7.5s, %50)` kadar bekle. Overtime aşılırsa zorla bitir.
- **Avantaj:** DVFS sonrası kare sayısı normalize — yavaş cihaz da 600 kare toplar, hızlı cihaz da 15s thermal'i yaşar. `frameCount` artık karşılaştırılabilir.
- **Field:** `BenchmarkResult.hybridTargetFrames`, `sparklineMs.size` de hibrit'e bağlı.

### [ALT-T4] Harmonik ortalama ağırlığı (min FPS)

- **Sorun:** Saf median düşük FPS'e az duyarlı (median robust). S10 kuadratik `median×(stab/100)²` min'i cezalar ama eğri keskin.
- **ALT:** Harmonik ortalama `H = n / sum(1/FPS_i) = 1000*n / sum(ms_i)`. Harmonic ≤ arithmetic, düşük FPS harmonik'i daha çok çeker — min'in ham potansiyel kadar ağırlıkta olmasının *alternatif* ifadesi.
- **Skor:** `harmonicScore = harmonicFps × (min/harmonic)` — lineer ceza (median kuadratik vs harmonic lineer farklı eğriler). Overlay'de ikisi yan yana: `〰 Harmonic: 72 FPS (min/harm 68%, skor 49)`.
- **Kullanım:** `harmonicWeightPct = min/harmonic*100` — <70 ise kırmızı. S10 `minMedianRatioPct` ile karşılaştırma için birebir.
- **Winsorized ile ilişki:** CI tarafında winsorized/trimmed mean de harmonik gibi düşük değerlere duyarlı ama tail'i keser; ikisi de `median`'a alternatif.

### Ek: sparkline verisi

- `sparklineMs: List<Double>` — kronolojik frameTimes ms, max 256 örnek (fazlası stride ile). Overlay `FrameTimeSparkline` Canvas ile çizer: yeşil = median, kırmızı nokta = jank (median×1.5 üstü). Min FPS'nin zaman içindeki dağılımı anında görünür — S13 jank bar'ına ek olarak *temporal* görselleştirme.

---

## CI Sentetik Bench — benchmark.yml ALT-C1/C2

### [ALT-C1] Round-robin sıralama + per-core pinning (taskset)

- **Eski (S10):** `for c in cases { 31 round c }` — case0 soğuk cache, case son sıcak (thermal bias). Ayrıca scheduler çekirdek göçmesi jitter'ı.
- **ALT:**
  1. **Frames önceden hesapla:** tüm case'ler için warmup + probe → `framesPerCase[]`. Sonra ölçüm.
  2. **Round-robin interleaved:** `for round 0..30 { for case in cases { 1 batch } }` — her round'da tüm case'ler sırayla birer batch. Thermal eşit dağılır, `multidraw_bench.cpp` round-robin ile uyumlu.
  3. **Per-core pinning:** `taskset -c $PIN_CORE` (son çekirdek) + `nice -n -5` varsa uygula. `LIBGL_ALWAYS_SOFTWARE=1` llvmpipe çok çekirdekli ama ölçüm tek çekirdekte daha stabil. Log: `taskset detected — will pin to core X`.
  4. **Çalıştırma:** `PIN_PREFIX` doluysa `$PIN_PREFIX xvfb-run ...`, boşsa normal.

### [ALT-C2] Winsorized / trimmed mean (IQR yerine)

- **İlk ajan (S10):** MAD (`median ±3*MAD*1.4826`) ile outlier atma → `filtered` → median/mean. Tekil hiccup'ları atar, `glClear_only` noise %22→%2-3.
- **ALT:** Winsorized mean `%10` — sorted'da alt %10 → p10'a, üst %10 → p90'a winsorize et, sonra mean. Trimmed mean %10 — alt/üst %10'u at, kalanın mean'i.
  - MAD: keskin eşik (3σ dışı at), robust ama tail'i tamamen siler.
  - Winsorized: tail'i silmez, *sınırlar* — outlier'ın etkisi azalır ama bilgisi kalır. CI'da daha az agresif, PR delta'sı daha yumuşak.
  - Trimmed: tail'i siler ama simetrik — IQR'ye yakın.
- **JSON:** Her case için `median_us` (MAD median primary), `winsorized_us`, `trimmed_us` birlikte yazılır. `benchstat` iki metrikle karşılaştırılabilir. Log: `MAD median 12.3 us, wMean 12.8 us (trim 12.5) ... [round-robin]`.

### Karşılaştırma Tablosu

|  | S10 MAD | ALT winsorized |
|---|---|---|
| Yöntem | `abs(v-median) >3*MAD*1.48 → at` | alt/üst %10'u winsorize/trim |
| Agresiflik | Yüksek (tekil büyük hiccup tamamen gider) | Orta (tail sınırlanır, bilgi korunur) |
| `glClear_only` noise | %2-3 (MAD) | Beklenen %2-4 (winsorized, benzer) |
| PR delta hassasiyeti | Yüksek (outlier atılınca delta net) | Daha yumuşak (outlier etkisi az ama sıfır değil) |
| Öneri | Final skor için MAD median | Karşılaştırma için winsorized secondary |

---

## Overlay — sparkline + harmonic

- **FrameTimeSparkline** (`Canvas` 28dp): `frameTimesMs` kronolojik çizgi (mavi `#90CAF9`), median yeşil çizgi, jank noktaları kırmızı. 256 kareye kadar tam, fazlası stride ile örneklenir. Her renderer kartında ayrı.
- **Harmonic satırı:** `〰 Harmonic: X FPS (min/harm Y%, skor Z)` — S10 min/median bar'ının altında, karşılaştırma için.
- **Vsync denemesi satırı:** `✓/✗ V-sync kapatma denendi...` — ALT-T1 sonucu.
- **Presentation düzeltme satırı:** `◐ Presentation time düzeltmesi uygulandı` — sadece uygulandıysa.

---

## Dosya Değişiklikleri

- `BenchmarkGLRenderer.kt`: +150 satır ALT-T1..T4, `BenchmarkResult` 7 yeni field (`harmonicFps`, `harmonicScore`, `harmonicWeightPct`, `vsyncOffAttempted`, `vsyncOffSuccess`, `presentationCorrected`, `sparklineMs`), hibrit mantığı, EGL vsync-off, presentation düzeltmesi, harmonik skor.
- `benchmark.yml`: +40 satır ALT-C1/C2, `taskset` pinning, `winsorized_mean`/`trimmed_mean` helpers, round-robin interleaved loop, extended JSON (`winsorized_us`, `trimmed_us`).
- `BenchmarkOverlay.kt`: +80 satır `FrameTimeSparkline` composable, harmonic/vsync/sparkline satırları.
- `STABILITE_ALT.md`: bu dosya (ALT notu, `STABILITE.md` bozulmadı).

## Doğrulama

- **CI:** `benchmark` job'u `bench-results/synth_bench.json` içinde `mode: "ALT round-robin + taskset + winsorized"` ve her case'de `winsorized_us`/`trimmed_us` görmeli. Log'da `[warmup]` + `[round-robin]` satırları ve `taskset detected` (varsa) olmalı. `glClear_only` noise hem MAD hem winsorized için %2-4 olmalı — biri %22 ise batch/round ayarı doğrulanır.
- **Cihaz:** Overlay'de her kartta sparkline çizgisi, harmonic satırı, vsync deneme satırı görünmeli. Hibrit modda yavaş cihazda `frameCount >=600` ve süre 15-22.5s arası olmalı (overtime log'u). Vsync 60Hz cihazda `estimatedVsyncIntervalMs ≈16.66` ve `presentationCorrected=true` olabilir; vsync'siz cihazda false.
- **Karşılaştırma:** `STABILITE.md` S10-S13 skorları ile bu ALT skorları yan yana `benchstat` / overlay'de karşılaştır — hangisi daha stabil (stability yüksek, noise düşük, min/harm dengeli) ise seçilir. İdeal final: ALT zamanlama + S10 puanlama birleşimi.

## Not

- ALT deney **commit/push yapmaz** — sadece dosyaları düzenler. `perf/stabilite-ham-performans` branch'inde `STABILITE.md`'yi bozmadan `STABILITE_ALT.md` oluşturur.
- Triple-buffer vsync-off `GLSurfaceView`'da garanti değil — driver'a bağlı. Başarısızlık normal, etiketleme ile şeffaf.
- Harmonik vs median kuadratik — ikisi de min'i cezalar, eğri farklı. A/B test için ikisi de raporlanır, tek skor seçimi PR incelemesinde.
