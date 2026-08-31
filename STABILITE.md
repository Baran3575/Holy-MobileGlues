# Stabilite — ham performans potansiyelini açığa çıkarma

## Sorun

`BenchmarkGLRenderer` ilk halinde her kare `glGetUniformLocation` çağrısı, ısınma yok, ortalama (mean) ile puanlama yapıyordu. Bu üçü jitter üretir:

| Kaynak | Etki | Ölçü |
|---|---|---|
| `glGet*` her kare | driver sync, ~0.1 ms jitter | avg'yi şişirir, stability düşer |
| DVFS ramp (ilk 1-2 sn) | frekans tırmanırken kare süresi %30 uzun | min/avg oranı bozulur |
| GC (ArrayList büyütme) | tek kare 2-3× uzun | outlier p99'u çeker |

Sonuç: ham GPU gücü gizlenir, "yüksek avg ama düşük stability → düşük score".

## Düzeltmeler (kusursuz — PR her zaman açık, inceleme sürüyor)

- **S1 — Lokasyon cache** (`BenchmarkGLRenderer.kt:51`): `aPosLoc/uRotLoc/uOffLoc` `onSurfaceCreated`'te bir kez. Her kare 3 sync kayboldu.
- **S2 — Isınma** (`warmupMs=1500`, `warmupFrames=30`): ilk 1.5 sn + 30 kare ölçülmüyor. DVFS ve ilk GC atık veriden çıktı. `multidraw_bench.cpp: BENCH_WARMUP=8` ile aynı fikir.
- **S3 — Zaman bazlı rotasyon** (`elapsed/16*2°` deterministik): tek kare `dt` jitter'ı yok, FPS düşse yük sabit.
- **S4 — Median + MAD outlier** (`computeResultStable`): sorted → median → MAD×1.4826 → 3σ üstü atılır → temiz median. Puan `median × stab`, `stab = median/max`. Tekil hiccup ortalamayı çekemiyor. `multidraw_bench.cpp` `median, noise target 0.15` ile uyumlu.
- **S5/S6 — Kapasite ve Overlay**: `ArrayList(2048)` önceden, `ResultsPhase` kararsızsa `⚠️` + "kılıfı çıkar, 2 dk bekle" uyarısı, tabloda `median(avg)` birlikte.
- **S7 — Thread priority** `THREAD_PRIORITY_DISPLAY`: GL thread scheduler jitter'ı azaltıldı — ham potansiyel öne çıktı.
- **S8 — V-sync belirginleştirme**: `GLSurfaceView` vsync dahil ölçer (gerçek oyun FPS'ine yakın); CI `synth_bench` vsync'siz (ham driver). İkisi ayrı raporlanır.
- **S9 — Kararlılık kriteri**: `stability <70` → uyarı, `median(avg)` birlikte — kullanıcı ham median'a bakarak thermal/DVFS etkisini ayırabilir.
- **S10 — Çift ölçüm & kuadratik min cezası** (`BenchmarkGLRenderer.kt:computeResultStable`): skor `median×(stab/100)²` — önceki `median×stab/100` lineerdi, şimdi min düşükse kareyle cezalı (stab 80→0.64, 60→0.36, 50→0.25). `stab = min/median` olduğu için min/median oranı doğrudan skora gömülü; ayrıca `minMedianRatioPct`, `pacingScore` alanları eklendi. Jank %5 üstüyse ek `jankPenalty = 1 - jank/200` uygulanır. Minimum FPS artık ham potansiyel kadar skor belirleyici.
- **S11 — Thermal throttling simülasyonu**: ölçüm kronolojik ikiye bölünür, birinci/ikinci yarı median farkı `thermalDropPct` olarak raporlanır. >%12 ise overlay `🌡️ Thermal throttling şüphesi` uyarısı verir. Gerçek throttling yoksa 0-3% kalır; cihaz ısındıysa ikinci yarı belirgin yavaşlar.
- **S12 — V-sync vs raw ayrımı**: `isVsyncLimited()` — stab≥85, jank≤10 ve median 60/90/120/144/165'e ±2 yakınsa `vsyncLimited=true`. Overlay `🔒 V-sync tavanına takılı` notu düşer ve CI `synth_bench` (vsync'siz) ile yan yana yorumlanır. Ham GPU potansiyeli vsync tavanından yüksekse kullanıcı EGL `swapInterval 0` gerektiğini anlar.
- **S13 — Frame pacing**: her kare `median*1.5` üstüyse "jank" sayılır; `jankPct = jank/size*100`, `pacingScore = 100-jankPct`. Tabloda min hücresi renk kodlu (yeşil ≥85% / turuncu 60-74% / kırmızı <60% min/median), üstte min/med barı ve `jank %` rozeti var. Yüksek jank apertürü skora ek ceza olarak yansır.

### Synthetic bench — `glClear_only` noise düzeltmesi (`benchmark.yml` `/tmp/synth_bench.cpp`)

`glClear_only` çok hızlı (~5-15 µs) olduğu için timer quantization noise %22 idi, diğer case'ler %1-2. Düzeltme: `TARGET_BATCH_US 24ms→50ms`, `WARMUP 8→16` (clear için 32), `frames/batch` tavanı 64→2048 (clear için min 256), `MAX_ROUNDS 21→31`, MAD outlier filtresi (3×MAD×1.4826) ve mutlak floor 0.5 µs. Batch 50 ms olunca quantization payı ~1/2000'e düşer, MAD tekil hiccup'ları atar → noise %22→%2-3 hedefi.

### Overlay — minimum FPS vurgusu (`BenchmarkOverlay.kt:ResultsPhase`)

Tabloda min hücresi renk kodlu, üstte `Min/Med` barı ve `jank %`. Uyarı hiyerarşisi: `thermal > severe min (<60%) > instability (<70) > jank (>10%) > vsync`. Severe min kırmızı ve `⛔ Minimum FPS çok düşük — kuadratik cezalı` mesajı, moderate turuncu. Skor rengi de aynı koda tabi — düşük min skoru doğrudan kızartır.

## Beklenen sonuç

- Aynı cihazda **median FPS artışı** (ham potansiyel) ve **stability artışı** (jitter azalışı) — score kuadratik olduğu için `median×stab²` ile ikisi de yükselir; düşük min tek başına skoru çeker.
- **Noise düşüşü**: `glClear_only` noise %22→%2-3, diğer case'ler %1-2 korunur — PR delta güvenilir.
- **Min FPS vurgusu**: tablo ve bar ile min/median anında görünür; düşük min kırmızı, skorla birlikte cezalı.
- PR'da `benchmark.yml` (lavapipe `synth_bench.json`) delta'yı gösterir; cihazda overlay doğrudan karşılaştırır. Stabilite düşükse score yerine median'a bakılması önerilir; min düşükse score zaten düşük — overlay açıkça söyler.

## Doğrulama

- CI: `benchmark` job'u her PR'da `bench-results/synth_bench.json` + `report.md` artifact'ı üretir, PR yorumu ekler. `glClear_only` noise'u log'da `%2-3` olmalı; yüksekse batch/round artırımı doğrulanır.
- Cihaz: `BenchmarkOverlay` → "Tekrar test et" ile iki koşu ardışık; ısınmış ikinci koşuda stability >80, `jankPct <5`, `thermalDropPct <8` beklenir. V-sync cihazda 60/90/120'ye kilitliyse `🔒` görünmeli, CI vsync'siz sayı daha yüksek olmalı.
- Kod kanıtı: `BenchmarkGLRenderer.kt:computeResultStable` kuadratik formül + `isVsyncLimited` + `thermalDrop` + `jankPct`; `benchmark.yml` MAD filtresi + `frames 256-2048` clear için.
