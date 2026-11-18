# FrameCut

FrameCut is a native Android screen and region recorder built with Jetpack Compose, a permanent **Vulkan 1.1 GPU compute & texture-sampling pipeline**, and hardware-accelerated **MediaCodec** video/audio encoding.

## Architecture & Features

- **Permanent Vulkan Graphics Pipeline**: A native C++20/JNI Vulkan compute shader (`color_process.comp`) performs hardware texture sampling, crop UV mapping, scaling, and color processing.
- **Unified Preview & Recorded Video Parity**: Both the live in-app preview `SurfaceView` and the `MediaCodec` input `Surface` are fed from the exact same processed Vulkan compute output image in a single submission.
- **Three Color Modes**:
  - **Original Color** *(default)*: 1:1 native color passthrough with BT.709 SDR parity.
  - **Full RGB**: Full `0–255` dynamic range (`COLOR_RANGE_FULL`) without `16–235` studio swing compression.
  - **Deep Color (10-bit)**: Wide-gamut linear-light processing with 10-bit (`1024`-step) Interleaved Gradient Noise micro-dither targeting `HEVCProfileMain10` with safe 8-bit hardware fallback.
- **MediaCodec + MediaMuxer Output**: Hardware HEVC / AVC video encoding + AAC-LC (`48 kHz`, `192 kbps`) audio (`Internal Audio` via `AudioPlaybackCapture` or `Microphone`), saved directly to `Movies/FrameCut`.
- **Full Screen & Custom Region Cropping**: Interactive 8-handle cropper with `Free`, `16:9`, `9:16`, `4:3`, and `1:1` presets plus an optional floating border overlay.
- **Automated GitHub Actions APK Builds**: `.github/workflows/build-apk.yml` automatically builds and uploads `FrameCut-debug-apk` via Gradle `9.3.1` and Java `17`.

## Local Build

```bash
gradle :app:assembleDebug --stacktrace
```
