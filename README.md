# FrameCut

FrameCut is a browser-based screen and region recorder rewritten in React, TypeScript, Vite, and Tailwind CSS from the original Android Jetpack Compose application.

## Features

- **Full Screen & Region Capture**: Record an entire browser tab, window, or display via `getDisplayMedia`, or crop to a custom pixel-aligned rectangle using the offscreen HTML5 Canvas pipeline.
- **Interactive Region Selector**: Drag to reposition, resize via 8 corner/edge handles, and snap to `Free`, `16:9`, `9:16`, `4:3`, or `1:1` aspect presets.
- **Orientation & Bitrate Configuration**: Switch between Portrait and Landscape canvas output and configure target encoding bitrates (`100 Mbps` – `350 Mbps`).
- **Audio Capture**: Record Internal Audio (system/tab audio shared in the display picker) or Microphone input.
- **Animated Region Frame**: Toggle the live crimson dashed region border preview (`Show` / `Hide`).
- **Recorded Videos Library**: Persist captured videos locally in IndexedDB with built-in playback, download/share, and deletion confirmation.

## Development

```bash
npm install
npm run dev
```

Build for production:

```bash
npm run build
```
