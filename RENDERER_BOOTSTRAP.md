# Holy-MobileGlues — Zalith Launcher 2 Renderer Fork Bootstrap

> **Durum:** Bu repo `MobileGlues-release` (docs-only) fork'u. APK build için native renderer eklenmesi gerekiyor.

## Hedef
Zalith Launcher 2 için MobileGlues'tan çatallanmış, daha iyi renderer (Holy). Üç olası mimari düşünüldü — hiçbiri varsayılmadı:

| Yöntem | Açıklama | Ne zaman |
|---|---|---|
| **A) Plugin fork (önerilen)** | `MobileGlues-plugin` + `MobileGlues` core'u submodule | Tam APK (libmobileglues.so + Compose ayar UI) isteniyorsa |
| **B) Core-only** | Sadece `MobileGlues-cpp` (CMake) | Sadece native lib, launcher kendi plugin'ini yapacaksa |
| **C) Prebuilt AAR** | Release APK'dan AAR çıkarıp patch | Hızlı deneme, C++ derlemeden |

## Önerilen: Yöntem A — tek komutla kur

```bash
# Holy-MobileGlues kökünde:
git submodule add https://github.com/MobileGL-Dev/MobileGlues MobileGlues
git submodule add https://github.com/MobileGL-Dev/MobileGlues-plugin plugin-src
# Veya sadece core:
# git submodule add -b plugin https://github.com/MobileGL-Dev/MobileGlues MobileGlues

# Gradle wrapper'ı plugin'den al (Holy docs-only olduğu için yok):
cp plugin-src/gradlew ./ && cp -r plugin-src/gradle ./ && cp plugin-src/settings.gradle.kts ./ 2>/dev/null || true
cp plugin-src/build.gradle.kts ./ 2>/dev/null || true
```

`settings.gradle.kts` için:
```kotlin
include(":app")
include(":MobileGlues")
project(":MobileGlues").projectDir = file("MobileGlues")
```

Sonra push — `.github/workflows/build.yml` ve `apk.yml` otomatik APK üretir (NDK 27.3.13750724, JDK 17).

## Yöntem B — core-only (launcher entegrasyonu)

```bash
git submodule add https://github.com/MobileGL-Dev/MobileGlues MobileGlues
mkdir -p MobileGlues-cpp  # zaten submodule içinde
# CMake direkt:
cmake -S MobileGlues/MobileGlues-cpp -B build -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build --target mobileglues
```

## Yöntem C — prebuilt

Releases'ten `MobileGlues-plugin.apk` indir, `lib/arm64-v8a/libmobileglues.so`'yu yama kaynağı yap. CI'da build yok.

## Benchmark — hangi sayılar güvenilir?

- **Zalith Launcher 2'deki "benchmark"** gerçekte `DownloadEngineBench.kt` (MockWebServer, 48 MiB segmented ~29.8 MiB/s, 2000 dosya ~567-719 files/s, %20 500 retry). GPU ham draw ölçmez. Bu repo GPU için `bench/multidraw_bench.cpp` (median, round-robin, 8 s budget, noise 0.15) + `MG_Benchmark` (Google Benchmark) + `tools/device_bench` (FCL fordebug, 180 s warmup, thermal gate) kullanır — Actions'te lavapipe (software) ile göreceli delta, gerçek cihazda `mg_multidraw_bench_run()` ile mutlak.
- Actions `benchmark.yml` her üç ihtimali de dener; hangisi varsa o koşar, yoksa synthetic EGL microbench (1280x720 pbuffer, Sodium benzeri VBO) her zaman rapor üretir.

## Local build yok

Tüm build'ler Actions'te. Secrets: `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` (opsiyonel, yoksa unsigned).

## Sonraki adım — daha iyi renderer

1. `MobileGlues-cpp/gl/` altında `multidraw.cpp`, `program.cpp`, `texture.cpp`'yi fork'la.
2. Stall noktaları: `glGetError` sync, shader cache miss, FBO resolve. `mg_multidraw_bench_run()` ile ölç, `benchmark.yml` PR yorumunda delta gör.
3. Zalith tarafında `RendererPluginManager.kt:144` (`com.fcl.plugin.mobileglues`) zaten tanıyor — `app_name`, `renderer`, `boatEnv`/`pojavEnv` manifestPlaceholders'ı koru.
