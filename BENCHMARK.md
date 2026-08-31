# Benchmark — GPU ham draw performansı

## Ne ölçülüyor?

| Case | Açıklama |
|---|---|
| `glDrawArrays_1x1024` | Tek büyük draw |
| `glDrawArrays_10x100` | Orta batch (Sodium chunk section benzeri) |
| `glDrawArrays_32x32` | Çok sayıda küçük draw (multidraw stresi) |
| `glClear_only` | Driver overhead tabanı |

Her case için `median µs/frame`, `draws/s`, `noise` (p95-median)/median. Raporlama `multidraw_bench.cpp` mantığı: warmup 8, target batch 24 ms, median (mean değil), 5–101 round.

## Nerede koşuyor?

- **CI (PR & main)**: `benchmark.yml` — `ubuntu-latest` + Mesa `lavapipe` (`LIBGL_ALWAYS_SOFTWARE=1`, `GALLIUM_DRIVER=llvmpipe`). Mutlak sayı anlamsız, PR vs main delta anlamlı. Artifact: `benchmark-<sha>/` + PR yorumu.
- **Cihaz (gerçek GPU)**: `MobileGlues` plugin app içindeki `mg_multidraw_bench_run()` → JSON, veya `MobileGL/tools/device_bench/bench.sh` (FCL fordebug, `eglSwapBuffers` sayımı, 180 s warmup, thermal gate). CI'da yapılamaz.

## Zalith ile ilişki

Zalith Launcher 2'de tek bench `DownloadEngineBench.kt` (download throughput, GPU değil). Bu repo Zalith'in *yapısını* (JUnit bench + Actions PR/main karşılaştırması + artifact/yorum) GPU'ya uyarladı. Zalith'in GPU bench'i yok — referans için `ZalithLauncher/src/test/java/.../DownloadEngineBench.kt` (48 MiB/16 conn, 2000 dosya).

## Yorumlama

- CI artifact'larını indir, `benchstat old.json new.json` ile karşılaştır.
- Noise > %15 ise sonuç kararsız — workflow retry önerir (multidraw_bench 4 pass'a kadar). CI'da tek pass; kararsızsa cihazda tekrar ölç.
- Gerçek cihaz + CI delta birlikte okunmalı: CI regresyonu yakalar, cihaz mutlak FPS'i verir.
