# Stabilite — ham performans potansiyelini açığa çıkarma

## Sorun

`BenchmarkGLRenderer` ilk halinde her kare `glGetUniformLocation` çağrısı, ısınma yok, ortalama (mean) ile puanlama yapıyordu. Bu üçü jitter üretir:

| Kaynak | Etki | Ölçü |
|---|---|---|
| `glGet*` her kare | driver sync, ~0.1 ms jitter | avg'yi şişirir, stability düşer |
| DVFS ramp (ilk 1-2 sn) | frekans tırmanırken kare süresi %30 uzun | min/avg oranı bozulur |
| GC (ArrayList büyütme) | tek kare 2-3× uzun | outlier p99'u çeker |

Sonuç: ham GPU gücü gizlenir, "yüksek avg ama düşük stability → düşük score".

## Düzeltmeler (bu PR)

- **S1 — Lokasyon cache** (`BenchmarkGLRenderer.kt:51-55`): `aPosLoc/uRotLoc/uOffLoc` `onSurfaceCreated`'te bir kez. Her kare 3 sync kayboldu.
- **S2 — Isınma** (`warmupMs=1500`, `warmupFrames=30`): ilk 1.5 sn + 30 kare ölçülmüyor. DVFS ve ilk GC atık veriden çıktı. `multidraw_bench.cpp: BENCH_WARMUP=8` ile aynı fikir.
- **S3 — Zaman bazlı rotasyon**: `rotation += dt/16*2°` — FPS düşse yük sabit, aksi halde düşük FPS'te az iş yapılmış gibi görünürdü.
- **S4 — Median + MAD outlier** (`computeResultStable`): sorted → median → MAD → 3×MAD üstü atılır → temiz median. Puan `median × stab`, `stab = median/max`. Tekil hiccup ortalamayı çekemiyor. `multidraw_bench.cpp`'deki `median, noise target 0.15` ile uyumlu.
- **S5/S6 — Kapasite ve Overlay**: `ArrayList(2048)` önceden, `ResultsPhase` kararsızsa `⚠️` + "kılıfı çıkar, 2 dk bekle" uyarısı, tabloda `median(avg)` birlikte gösterim.

## Beklenen sonuç

- Aynı cihazda **median FPS artışı** (ham potansiyel) ve **stability artışı** (jitter azalışı) — score ikisinin çarpımı olduğu için ikisi de yükselir.
- PR'da `benchmark.yml` (lavapipe `synth_bench.json`) delta'yı gösterir; cihazda overlay doğrudan karşılaştırır. Stabilite düşükse score yerine median'a bakılması önerilir.

## Doğrulama

- CI: `benchmark` job'u her PR'da `bench-results/synth_bench.json` + `report.md` artifact'ı üretir, PR yorumu ekler.
- Cihaz: `BenchmarkOverlay` → "Tekrar test et" ile iki koşu ardışık; ısınmış ikinci koşuda stability >80 beklenir, aksi halde thermal/DVFS notu.
