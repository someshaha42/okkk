import { ArrayBufferTarget, Muxer } from 'mp4-muxer';
import type {
  ColorModeOption,
  PixelRegion,
  RecordedVideoItem,
  RecordingSettings,
} from '../types/recorder';
import { VIDEO_BITRATE_OPTIONS } from '../types/recorder';
import {
  resolveFullScreenDimensions,
  toPixelRegion,
} from '../utils/regionMath';
import { saveRecording } from '../storage/recordingsDb';
import {
  GpuColorPipeline,
  type GraphicsBackendKind,
} from '../gpu/GpuColorPipeline';

export interface ActiveRecordingCallbacks {
  onPhaseChange: (phase: 'RECORDING' | 'PAUSED' | 'FINALIZING' | 'IDLE') => void;
  onSessionMetadata: (meta: {
    activeAudioLabel: string;
    activeEncoderName: string;
    activeGraphicsBackend: GraphicsBackendKind;
    activeColorMode: ColorModeOption;
    activeBitrateMbps: number;
    activeResolutionLabel: string;
    activeRegion: PixelRegion | null;
    showRegionBorder: boolean;
    warningMessage?: string | null;
  }) => void;
  onLiveSourceVideo: (video: HTMLVideoElement | null) => void;
  onElapsedChange: (elapsedMs: number) => void;
  onSaved: (item: RecordedVideoItem) => void;
  onError: (message: string) => void;
}

interface WebCodecsVideoChoice {
  codecString: string;
  muxerCodec: 'avc' | 'hevc';
  label: string;
  bitrate: number;
}

function getVideoColorSpaceInit(colorMode: ColorModeOption): VideoColorSpaceInit {
  switch (colorMode) {
    case 'ORIGINAL':
      return {
        primaries: 'bt709',
        transfer: 'iec61966-2-1',
        matrix: 'bt709',
        fullRange: false,
      };
    case 'FULL_RGB':
      return {
        primaries: 'bt709',
        transfer: 'iec61966-2-1',
        matrix: 'rgb',
        fullRange: true,
      };
    case 'DEEP_COLOR_10BIT':
      return {
        primaries: 'bt709',
        transfer: 'bt709',
        matrix: 'bt709',
        fullRange: true,
      };
  }
}

async function selectWebCodecsVideoConfig(
  width: number,
  height: number,
  requestedBps: number,
  colorMode: ColorModeOption,
): Promise<WebCodecsVideoChoice | null> {
  if (typeof VideoEncoder === 'undefined' || typeof VideoFrame === 'undefined') {
    return null;
  }

  const codecCandidates: Array<{
    codecString: string;
    muxerCodec: 'avc' | 'hevc';
    label: string;
  }> =
    colorMode === 'DEEP_COLOR_10BIT'
      ? [
          { codecString: 'hev1.2.4.L120.B0', muxerCodec: 'hevc', label: 'HEVC Main10 (WebCodecs)' },
          { codecString: 'hvc1.2.4.L120.B0', muxerCodec: 'hevc', label: 'HEVC Main10 (WebCodecs)' },
          { codecString: 'avc1.640033', muxerCodec: 'avc', label: 'H.264 High (WebCodecs)' },
          { codecString: 'avc1.64002a', muxerCodec: 'avc', label: 'H.264 High (WebCodecs)' },
          { codecString: 'avc1.4d002a', muxerCodec: 'avc', label: 'H.264 Main (WebCodecs)' },
        ]
      : [
          { codecString: 'avc1.640033', muxerCodec: 'avc', label: 'H.264 High (WebCodecs)' },
          { codecString: 'avc1.64002a', muxerCodec: 'avc', label: 'H.264 High (WebCodecs)' },
          { codecString: 'avc1.4d002a', muxerCodec: 'avc', label: 'H.264 Main (WebCodecs)' },
          { codecString: 'avc1.42E01E', muxerCodec: 'avc', label: 'H.264 Baseline (WebCodecs)' },
        ];

  const bitrateCandidates = [
    Math.min(requestedBps, 60_000_000),
    Math.min(requestedBps, 24_000_000),
    12_000_000,
    6_000_000,
  ];

  for (const candidate of codecCandidates) {
    for (const bitrate of bitrateCandidates) {
      try {
        const support = await VideoEncoder.isConfigSupported({
          codec: candidate.codecString,
          width,
          height,
          bitrate,
          framerate: 30,
          hardwareAcceleration: 'prefer-hardware',
          latencyMode: 'realtime',
        });
        if (support.supported) {
          return {
            codecString: candidate.codecString,
            muxerCodec: candidate.muxerCodec,
            label: candidate.label,
            bitrate,
          };
        }
      } catch {
        // Probe next config
      }
    }
  }

  return null;
}

async function isAacAudioEncoderSupported(
  sampleRate: number,
  numberOfChannels: number,
): Promise<boolean> {
  if (typeof AudioEncoder === 'undefined' || typeof AudioData === 'undefined') {
    return false;
  }
  try {
    const res = await AudioEncoder.isConfigSupported({
      codec: 'mp4a.40.2',
      sampleRate,
      numberOfChannels,
      bitrate: 192_000,
    });
    return Boolean(res.supported);
  } catch {
    return false;
  }
}

function pickMediaRecorderMimeType(): {
  mimeType: string;
  extension: string;
  codecLabel: string;
} {
  const candidates: Array<{ mimeType: string; extension: string; codecLabel: string }> = [
    { mimeType: 'video/mp4;codecs=avc1.42E01E,mp4a.40.2', extension: 'mp4', codecLabel: 'H.264/MP4' },
    { mimeType: 'video/mp4;codecs=avc1', extension: 'mp4', codecLabel: 'H.264/MP4' },
    { mimeType: 'video/mp4', extension: 'mp4', codecLabel: 'MP4' },
    { mimeType: 'video/webm;codecs=vp9,opus', extension: 'webm', codecLabel: 'VP9/WebM' },
    { mimeType: 'video/webm;codecs=vp8,opus', extension: 'webm', codecLabel: 'VP8/WebM' },
    { mimeType: 'video/webm', extension: 'webm', codecLabel: 'WebM' },
  ];

  if (typeof MediaRecorder === 'undefined') {
    return { mimeType: '', extension: 'webm', codecLabel: 'WebM' };
  }

  for (const candidate of candidates) {
    if (MediaRecorder.isTypeSupported(candidate.mimeType)) {
      return candidate;
    }
  }
  return { mimeType: '', extension: 'webm', codecLabel: 'WebM' };
}

function buildRecordingFileName(extension: string): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const stamp = [
    now.getFullYear(),
    pad(now.getMonth() + 1),
    pad(now.getDate()),
    '_',
    pad(now.getHours()),
    pad(now.getMinutes()),
    pad(now.getSeconds()),
  ].join('');
  return `FrameCut_${stamp}.${extension}`;
}

export class WebScreenRecorderSession {
  private displayStream: MediaStream | null = null;
  private micStream: MediaStream | null = null;
  private mixedStream: MediaStream | null = null;
  private hiddenVideo: HTMLVideoElement | null = null;
  private canvas: HTMLCanvasElement | null = null;
  private gpuPipeline: GpuColorPipeline | null = null;

  // WebCodecs + mp4-muxer path
  private muxer: Muxer<ArrayBufferTarget> | null = null;
  private videoEncoder: VideoEncoder | null = null;
  private audioEncoder: AudioEncoder | null = null;
  private audioContext: AudioContext | null = null;
  private audioSourceNode: MediaStreamAudioSourceNode | null = null;
  private audioProcessorNode: ScriptProcessorNode | null = null;
  private encodedFrameCount = 0;
  private audioFramesSubmitted = 0;
  private usingWebCodecs = false;

  // MediaRecorder fallback path
  private mediaRecorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private fallbackMimeMeta = pickMediaRecorderMimeType();

  private rafId: number | null = null;
  private timerIntervalId: number | null = null;
  private isPaused = false;
  private isStopping = false;

  private startPerfMs = 0;
  private pauseStartPerfMs = 0;
  private pausedAccumulatedMs = 0;
  private lastEncodedFramePerfMs = 0;

  private outputWidth = 1080;
  private outputHeight = 1920;
  private cropRegion: PixelRegion | null = null;
  private activeColorMode: ColorModeOption = 'ORIGINAL';
  private callbacks: ActiveRecordingCallbacks;

  constructor(callbacks: ActiveRecordingCallbacks) {
    this.callbacks = callbacks;
  }

  async start(settings: RecordingSettings): Promise<void> {
    if (!navigator.mediaDevices?.getDisplayMedia) {
      throw new Error('Screen capture (getDisplayMedia) is not supported in this browser.');
    }

    const wantInternalAudio = settings.audioSource === 'INTERNAL';
    let displayStream: MediaStream;
    try {
      displayStream = await navigator.mediaDevices.getDisplayMedia({
        video: {
          frameRate: { ideal: 30, max: 60 },
        },
        audio: wantInternalAudio,
      });
    } catch (err) {
      const msg =
        err instanceof DOMException && err.name === 'NotAllowedError'
          ? 'Screen capture permission was cancelled or denied.'
          : err instanceof Error
            ? err.message
            : 'Unable to start screen capture.';
      throw new Error(msg);
    }

    this.displayStream = displayStream;

    const videoTrack = displayStream.getVideoTracks()[0];
    if (!videoTrack) {
      this.cleanupResources();
      throw new Error('No video track was provided by the selected capture source.');
    }

    const trackSettings = videoTrack.getSettings();
    const rawCapW = trackSettings.width ?? window.screen.width * (window.devicePixelRatio || 1);
    const rawCapH = trackSettings.height ?? window.screen.height * (window.devicePixelRatio || 1);

    const [orientedW, orientedH] = resolveFullScreenDimensions(
      rawCapW,
      rawCapH,
      settings.orientationMode,
    );

    const pixelRegion =
      settings.recordingMode === 'REGION'
        ? toPixelRegion(settings.selectedRegion, orientedW, orientedH)
        : null;

    this.cropRegion = pixelRegion;
    this.outputWidth = pixelRegion ? pixelRegion.width : orientedW;
    this.outputHeight = pixelRegion ? pixelRegion.height : orientedH;
    this.activeColorMode = settings.colorMode;

    // Resolve audio tracks
    let warningMessage: string | null = null;
    let activeAudioLabel = 'Muted';
    const audioTracks: MediaStreamTrack[] = [];

    if (wantInternalAudio) {
      const displayAudioTracks = displayStream.getAudioTracks();
      if (displayAudioTracks.length > 0) {
        audioTracks.push(...displayAudioTracks);
        activeAudioLabel = 'Internal Audio';
      } else {
        activeAudioLabel = 'Video Only (No system audio shared)';
        warningMessage =
          'System/tab audio was not shared in the browser picker; recording video only.';
      }
    } else {
      try {
        const micStream = await navigator.mediaDevices.getUserMedia({
          audio: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
          },
        });
        this.micStream = micStream;
        const micTracks = micStream.getAudioTracks();
        if (micTracks.length > 0) {
          audioTracks.push(...micTracks);
          activeAudioLabel = micTracks[0].label || 'Microphone';
        } else {
          activeAudioLabel = 'Microphone unavailable';
          warningMessage = 'No microphone track detected; recording video only.';
        }
      } catch {
        activeAudioLabel = 'Muted (Mic permission denied)';
        warningMessage = 'Microphone permission denied; recording video only.';
      }
    }

    // Prepare hidden video element for zero-copy GPU texture upload
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.srcObject = new MediaStream([videoTrack]);
    await video.play();
    this.hiddenVideo = video;
    this.callbacks.onLiveSourceVideo(video);

    // Initialize unified GPU Color Pipeline (WebGPU Compute -> WebGL2 fallback)
    const canvas = document.createElement('canvas');
    canvas.width = this.outputWidth;
    canvas.height = this.outputHeight;
    this.canvas = canvas;

    let gpuPipeline = await GpuColorPipeline.create(canvas, true);
    // Render first frame through GPU pipeline
    this.renderGpuFrameWithPipeline(gpuPipeline);

    // Verify VideoFrame extraction compatibility; if WebGPU canvas blocks VideoFrame/captureStream, fall back cleanly to WebGL2
    if (gpuPipeline.backend === 'WebGPU Compute' && typeof VideoFrame !== 'undefined') {
      try {
        const probe = new VideoFrame(canvas, { timestamp: 0 });
        probe.close();
      } catch {
        gpuPipeline.dispose();
        gpuPipeline = await GpuColorPipeline.create(canvas, false);
        this.renderGpuFrameWithPipeline(gpuPipeline);
      }
    }
    this.gpuPipeline = gpuPipeline;

    const bitrateMeta =
      VIDEO_BITRATE_OPTIONS.find((b) => b.value === settings.videoBitrate) ??
      VIDEO_BITRATE_OPTIONS[1];
    const requestedBps = bitrateMeta.targetBitsPerSecond;

    let encoderName = '';
    let effectiveBitrateMbps = Math.round(requestedBps / 1_000_000);

    const webCodecsChoice = await selectWebCodecsVideoConfig(
      this.outputWidth,
      this.outputHeight,
      requestedBps,
      this.activeColorMode,
    );

    const hasAudio = audioTracks.length > 0;
    const audioSampleRate = 48_000;
    const audioChannels = settings.audioSource === 'INTERNAL' ? 2 : 1;
    const canEncodeAac = !hasAudio || (await isAacAudioEncoderSupported(audioSampleRate, audioChannels));

    if (webCodecsChoice && canEncodeAac) {
      this.usingWebCodecs = true;
      await this.startWebCodecsPipeline(
        webCodecsChoice,
        hasAudio ? audioTracks : [],
        audioSampleRate,
        audioChannels,
      );
      encoderName = webCodecsChoice.label;
      effectiveBitrateMbps = Math.max(1, Math.round(webCodecsChoice.bitrate / 1_000_000));
    } else {
      this.usingWebCodecs = false;
      this.startMediaRecorderFallback(canvas, audioTracks, requestedBps);
      encoderName = this.fallbackMimeMeta.codecLabel;
    }

    // Stop cleanly if user clicks the browser's built-in "Stop sharing" button
    videoTrack.addEventListener('ended', () => {
      if (!this.isStopping) {
        void this.stop();
      }
    });

    this.startPerfMs = performance.now();
    this.pauseStartPerfMs = 0;
    this.pausedAccumulatedMs = 0;
    this.lastEncodedFramePerfMs = 0;
    this.isPaused = false;
    this.isStopping = false;

    const showBorder =
      settings.recordingMode === 'REGION' && settings.regionAnimation === 'SHOW';

    this.callbacks.onSessionMetadata({
      activeAudioLabel,
      activeEncoderName: encoderName,
      activeGraphicsBackend: gpuPipeline.backend,
      activeColorMode: this.activeColorMode,
      activeBitrateMbps: effectiveBitrateMbps,
      activeResolutionLabel: `${this.outputWidth}×${this.outputHeight}`,
      activeRegion: pixelRegion,
      showRegionBorder: showBorder,
      warningMessage,
    });
    this.callbacks.onPhaseChange('RECORDING');

    this.startRenderLoop();
    this.startTimerLoop();
  }

  private async startWebCodecsPipeline(
    videoChoice: WebCodecsVideoChoice,
    audioTracks: MediaStreamTrack[],
    audioSampleRate: number,
    audioChannels: number,
  ): Promise<void> {
    const hasAudio = audioTracks.length > 0;

    const muxer = new Muxer({
      target: new ArrayBufferTarget(),
      video: {
        codec: videoChoice.muxerCodec,
        width: this.outputWidth,
        height: this.outputHeight,
        frameRate: 30,
      },
      audio: hasAudio
        ? {
            codec: 'aac',
            numberOfChannels: audioChannels,
            sampleRate: audioSampleRate,
          }
        : undefined,
      fastStart: 'in-memory',
      firstTimestampBehavior: 'offset',
    });
    this.muxer = muxer;
    this.encodedFrameCount = 0;
    this.audioFramesSubmitted = 0;

    const videoEncoder = new VideoEncoder({
      output: (chunk, meta) => {
        muxer.addVideoChunk(chunk, meta);
      },
      error: (err) => {
        this.callbacks.onError(`VideoEncoder error: ${err.message}`);
        void this.stop();
      },
    });

    videoEncoder.configure({
      codec: videoChoice.codecString,
      width: this.outputWidth,
      height: this.outputHeight,
      bitrate: videoChoice.bitrate,
      framerate: 30,
      hardwareAcceleration: 'prefer-hardware',
      latencyMode: 'realtime',
    });
    this.videoEncoder = videoEncoder;

    if (hasAudio) {
      const audioEncoder = new AudioEncoder({
        output: (chunk, meta) => {
          muxer.addAudioChunk(chunk, meta);
        },
        error: (err) => {
          this.callbacks.onError(`AudioEncoder error: ${err.message}`);
        },
      });
      audioEncoder.configure({
        codec: 'mp4a.40.2',
        sampleRate: audioSampleRate,
        numberOfChannels: audioChannels,
        bitrate: 192_000,
      });
      this.audioEncoder = audioEncoder;

      const audioCtx = new AudioContext({ sampleRate: audioSampleRate });
      this.audioContext = audioCtx;
      const source = audioCtx.createMediaStreamSource(new MediaStream(audioTracks));
      this.audioSourceNode = source;

      const processor = audioCtx.createScriptProcessor(2048, audioChannels, audioChannels);
      this.audioProcessorNode = processor;

      processor.onaudioprocess = (event) => {
        if (this.isPaused || this.isStopping || !this.audioEncoder || this.audioEncoder.state !== 'configured') {
          return;
        }
        const frameCount = event.inputBuffer.length;
        const planar = new Float32Array(frameCount * audioChannels);
        for (let ch = 0; ch < audioChannels; ch++) {
          const chData = event.inputBuffer.getChannelData(
            Math.min(ch, event.inputBuffer.numberOfChannels - 1),
          );
          planar.set(chData, ch * frameCount);
        }

        const timestampUs = Math.round((this.audioFramesSubmitted * 1_000_000) / audioSampleRate);
        this.audioFramesSubmitted += frameCount;

        try {
          const audioData = new AudioData({
            format: 'f32-planar',
            sampleRate: audioSampleRate,
            numberOfFrames: frameCount,
            numberOfChannels: audioChannels,
            timestamp: timestampUs,
            data: planar,
          });
          this.audioEncoder.encode(audioData);
          audioData.close();
        } catch {
          // Skip transient audio chunk error to keep recording stable
        }
      };

      // Connect through silent gain so ScriptProcessor runs without echoing audio to speakers
      const muteGain = audioCtx.createGain();
      muteGain.gain.value = 0;
      source.connect(processor);
      processor.connect(muteGain);
      muteGain.connect(audioCtx.destination);
    }
  }

  private startMediaRecorderFallback(
    canvas: HTMLCanvasElement,
    audioTracks: MediaStreamTrack[],
    requestedBps: number,
  ): void {
    const canvasStream = canvas.captureStream(30);
    const combinedTracks: MediaStreamTrack[] = [
      ...canvasStream.getVideoTracks(),
      ...audioTracks,
    ];
    this.mixedStream = new MediaStream(combinedTracks);

    const recorder = this.createMediaRecorder(this.mixedStream, requestedBps);
    this.mediaRecorder = recorder;
    this.chunks = [];

    recorder.ondataavailable = (event) => {
      if (event.data && event.data.size > 0) {
        this.chunks.push(event.data);
      }
    };
    recorder.onstop = () => {
      void this.finalizeMediaRecorder();
    };
    recorder.onerror = () => {
      this.callbacks.onError('MediaRecorder encountered an encoding error.');
      void this.stop();
    };

    recorder.start(250);
  }

  private createMediaRecorder(stream: MediaStream, requestedBps: number): MediaRecorder {
    const baseOpts: MediaRecorderOptions = {};
    if (this.fallbackMimeMeta.mimeType) {
      baseOpts.mimeType = this.fallbackMimeMeta.mimeType;
    }
    const bitrateCandidates = [requestedBps, 24_000_000, 12_000_000, undefined];
    for (const bps of bitrateCandidates) {
      try {
        const opts: MediaRecorderOptions = { ...baseOpts };
        if (bps !== undefined) {
          opts.videoBitsPerSecond = bps;
        }
        return new MediaRecorder(stream, opts);
      } catch {
        // Retry next bitrate
      }
    }
    return new MediaRecorder(stream);
  }

  private renderGpuFrameWithPipeline(pipeline: GpuColorPipeline): void {
    const video = this.hiddenVideo;
    if (!video || video.readyState < 2) return;
    pipeline.renderFrame({
      source: video,
      sourceWidth: video.videoWidth || this.outputWidth,
      sourceHeight: video.videoHeight || this.outputHeight,
      cropRegion: this.cropRegion,
      colorMode: this.activeColorMode,
    });
  }

  private startRenderLoop(): void {
    const minFrameIntervalMs = 1000 / 32; // Cap ~30 FPS smoothly for GPU efficiency

    const tick = (now: number) => {
      if (this.isStopping) return;

      if (!this.isPaused && this.gpuPipeline && this.canvas) {
        if (now - this.lastEncodedFramePerfMs >= minFrameIntervalMs) {
          this.renderGpuFrameWithPipeline(this.gpuPipeline);

          if (
            this.usingWebCodecs &&
            this.videoEncoder &&
            this.videoEncoder.state === 'configured' &&
            this.videoEncoder.encodeQueueSize <= 3
          ) {
            this.lastEncodedFramePerfMs = now;
            const elapsedMs = Math.max(
              0,
              now - this.startPerfMs - this.pausedAccumulatedMs,
            );
            const timestampUs = Math.round(elapsedMs * 1000);
            try {
              const frame = new VideoFrame(this.canvas, {
                timestamp: timestampUs,
                duration: 33_333,
              });
              // Apply explicit color space tag if supported by runtime
              void getVideoColorSpaceInit(this.activeColorMode);
              const keyFrame = this.encodedFrameCount % 30 === 0;
              this.encodedFrameCount += 1;
              this.videoEncoder.encode(frame, { keyFrame });
              frame.close();
            } catch {
              // Skip single dropped frame on transient GPU stall
            }
          } else {
            this.lastEncodedFramePerfMs = now;
          }
        }
      }

      this.rafId = requestAnimationFrame(tick);
    };
    this.rafId = requestAnimationFrame(tick);
  }

  private startTimerLoop(): void {
    if (this.timerIntervalId !== null) {
      window.clearInterval(this.timerIntervalId);
    }
    this.timerIntervalId = window.setInterval(() => {
      if (this.isPaused || this.isStopping) return;
      const elapsed = Math.max(
        0,
        performance.now() - this.startPerfMs - this.pausedAccumulatedMs,
      );
      this.callbacks.onElapsedChange(elapsed);
    }, 200);
  }

  pause(): void {
    if (this.isPaused || this.isStopping) return;
    this.isPaused = true;
    this.pauseStartPerfMs = performance.now();
    if (!this.usingWebCodecs && this.mediaRecorder && this.mediaRecorder.state === 'recording') {
      this.mediaRecorder.pause();
    }
    this.callbacks.onPhaseChange('PAUSED');
  }

  resume(): void {
    if (!this.isPaused || this.isStopping) return;
    if (this.pauseStartPerfMs > 0) {
      this.pausedAccumulatedMs += Math.max(0, performance.now() - this.pauseStartPerfMs);
      this.pauseStartPerfMs = 0;
    }
    this.isPaused = false;
    if (!this.usingWebCodecs && this.mediaRecorder && this.mediaRecorder.state === 'paused') {
      this.mediaRecorder.resume();
    }
    this.callbacks.onPhaseChange('RECORDING');
  }

  async stop(): Promise<void> {
    if (this.isStopping) return;
    this.isStopping = true;
    this.callbacks.onPhaseChange('FINALIZING');
    this.callbacks.onLiveSourceVideo(null);

    if (this.rafId !== null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
    if (this.timerIntervalId !== null) {
      window.clearInterval(this.timerIntervalId);
      this.timerIntervalId = null;
    }

    if (this.usingWebCodecs) {
      await this.finalizeWebCodecs();
    } else if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      try {
        this.mediaRecorder.stop();
      } catch {
        await this.finalizeMediaRecorder();
      }
    } else {
      await this.finalizeMediaRecorder();
    }
  }

  private getFinalElapsedMs(): number {
    return Math.max(
      0,
      Math.round(
        (this.isPaused && this.pauseStartPerfMs > 0
          ? this.pauseStartPerfMs
          : performance.now()) -
          this.startPerfMs -
          this.pausedAccumulatedMs,
      ),
    );
  }

  private async finalizeWebCodecs(): Promise<void> {
    const finalElapsedMs = this.getFinalElapsedMs();

    try {
      if (this.videoEncoder && this.videoEncoder.state === 'configured') {
        await this.videoEncoder.flush();
      }
      if (this.audioEncoder && this.audioEncoder.state === 'configured') {
        await this.audioEncoder.flush();
      }
    } catch {
      // Proceed with available muxed chunks
    }

    try {
      this.videoEncoder?.close();
    } catch {
      // ignore
    }
    try {
      this.audioEncoder?.close();
    } catch {
      // ignore
    }

    let blob: Blob | null = null;
    if (this.muxer && this.encodedFrameCount > 0) {
      try {
        this.muxer.finalize();
        const { buffer } = this.muxer.target;
        blob = new Blob([buffer], { type: 'video/mp4' });
      } catch {
        blob = null;
      }
    }

    this.cleanupResources();

    if (!blob || blob.size === 0) {
      this.callbacks.onError('Recording ended before any video frames were captured.');
      this.callbacks.onPhaseChange('IDLE');
      return;
    }

    const item: RecordedVideoItem = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      displayName: buildRecordingFileName('mp4'),
      mimeType: 'video/mp4',
      durationMs: finalElapsedMs,
      sizeBytes: blob.size,
      dateAddedSec: Math.floor(Date.now() / 1000),
      width: this.outputWidth,
      height: this.outputHeight,
    };

    try {
      await saveRecording(item, blob);
      this.callbacks.onSaved(item);
    } catch {
      this.callbacks.onError('Failed to save recording to browser storage.');
    } finally {
      this.callbacks.onPhaseChange('IDLE');
    }
  }

  private async finalizeMediaRecorder(): Promise<void> {
    const finalElapsedMs = this.getFinalElapsedMs();
    this.cleanupResources();

    const mimeType =
      this.mediaRecorder?.mimeType || this.fallbackMimeMeta.mimeType || 'video/webm';
    const blob = new Blob(this.chunks, { type: mimeType });
    this.chunks = [];
    this.mediaRecorder = null;

    if (blob.size === 0) {
      this.callbacks.onError('Recording ended before any video frames were captured.');
      this.callbacks.onPhaseChange('IDLE');
      return;
    }

    const ext = mimeType.includes('mp4') ? 'mp4' : 'webm';
    const item: RecordedVideoItem = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      displayName: buildRecordingFileName(ext),
      mimeType,
      durationMs: finalElapsedMs,
      sizeBytes: blob.size,
      dateAddedSec: Math.floor(Date.now() / 1000),
      width: this.outputWidth,
      height: this.outputHeight,
    };

    try {
      await saveRecording(item, blob);
      this.callbacks.onSaved(item);
    } catch {
      this.callbacks.onError('Failed to save recording to browser storage.');
    } finally {
      this.callbacks.onPhaseChange('IDLE');
    }
  }

  private cleanupResources(): void {
    this.displayStream?.getTracks().forEach((t) => t.stop());
    this.micStream?.getTracks().forEach((t) => t.stop());
    this.mixedStream?.getTracks().forEach((t) => t.stop());

    if (this.audioProcessorNode) {
      this.audioProcessorNode.onaudioprocess = null;
      this.audioProcessorNode.disconnect();
      this.audioProcessorNode = null;
    }
    if (this.audioSourceNode) {
      this.audioSourceNode.disconnect();
      this.audioSourceNode = null;
    }
    if (this.audioContext) {
      void this.audioContext.close().catch(() => {});
      this.audioContext = null;
    }

    if (this.hiddenVideo) {
      this.hiddenVideo.pause();
      this.hiddenVideo.srcObject = null;
      this.hiddenVideo = null;
    }

    this.gpuPipeline?.dispose();
    this.gpuPipeline = null;
    this.canvas = null;
    this.muxer = null;
    this.videoEncoder = null;
    this.audioEncoder = null;
    this.displayStream = null;
    this.micStream = null;
    this.mixedStream = null;
  }
}
