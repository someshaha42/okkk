import type { ColorModeOption, PixelRegion } from '../types/recorder';
import {
  GLSL3_FRAGMENT_SHADER,
  GLSL3_VERTEX_SHADER,
  WGSL_BLIT_SHADER,
  WGSL_COMPUTE_COLOR_SHADER,
} from './colorPipelineShaders';
import type {
  GPUCanvasContextLike,
  GPUDeviceLike,
  GPUTextureLike,
  NavigatorGPULike,
} from './webgpuTypes';

export type GraphicsBackendKind = 'WebGPU Compute' | 'WebGL2 Shader';

export interface RenderFrameParams {
  source: HTMLVideoElement | HTMLCanvasElement | ImageBitmap;
  sourceWidth: number;
  sourceHeight: number;
  cropRegion: PixelRegion | null;
  colorMode: ColorModeOption;
}

function colorModeToInt(mode: ColorModeOption): number {
  switch (mode) {
    case 'ORIGINAL':
      return 0;
    case 'FULL_RGB':
      return 1;
    case 'DEEP_COLOR_10BIT':
      return 2;
  }
}

function computeCropAndScale(
  srcW: number,
  srcH: number,
  dstW: number,
  dstH: number,
  cropRegion: PixelRegion | null,
): {
  sLeft: number;
  sTop: number;
  sWidth: number;
  sHeight: number;
  xScale: number;
  yScale: number;
  is1To1: boolean;
} {
  let xScale = 1;
  let yScale = 1;
  let sLeft = 0;
  let sTop = 0;
  let sWidth = 1;
  let sHeight = 1;
  let is1To1 = false;

  if (!cropRegion) {
    const srcAspect = srcW / Math.max(1, srcH);
    const dstAspect = dstW / Math.max(1, dstH);
    if (Math.abs(srcAspect - dstAspect) > 0.01) {
      if (srcAspect > dstAspect) {
        yScale = dstAspect / srcAspect;
      } else {
        xScale = srcAspect / dstAspect;
      }
    }
    is1To1 = srcW === dstW && srcH === dstH && Math.abs(xScale - 1) < 0.001 && Math.abs(yScale - 1) < 0.001;
  } else {
    sLeft = cropRegion.left / Math.max(1, cropRegion.screenWidth);
    sTop = cropRegion.top / Math.max(1, cropRegion.screenHeight);
    sWidth = Math.max(0.05, cropRegion.width / Math.max(1, cropRegion.screenWidth));
    sHeight = Math.max(0.05, cropRegion.height / Math.max(1, cropRegion.screenHeight));

    const croppedW = sWidth * srcW;
    const croppedH = sHeight * srcH;
    const cropAspect = croppedW / Math.max(1, croppedH);
    const dstAspect = dstW / Math.max(1, dstH);
    if (Math.abs(cropAspect - dstAspect) > 0.01) {
      if (cropAspect > dstAspect) {
        yScale = dstAspect / cropAspect;
      } else {
        xScale = cropAspect / dstAspect;
      }
    }
    is1To1 =
      Math.round(croppedW) === dstW &&
      Math.round(croppedH) === dstH &&
      Math.abs(xScale - 1) < 0.001 &&
      Math.abs(yScale - 1) < 0.001;
  }

  return { sLeft, sTop, sWidth, sHeight, xScale, yScale, is1To1 };
}

/**
 * Generates a compact GPU calibration test card for the idle preview so users
 * can inspect Original Color, Full RGB, and Deep Color (10-bit) processing in real time.
 */
export function paintCalibrationSource(
  canvas: HTMLCanvasElement,
  width: number,
  height: number,
): void {
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  // Deep studio slate gradient background
  const bg = ctx.createLinearGradient(0, 0, width, height);
  bg.addColorStop(0, '#0B0E14');
  bg.addColorStop(0.5, '#151B26');
  bg.addColorStop(1, '#1E2534');
  ctx.fillStyle = bg;
  ctx.fillRect(0, 0, width, height);

  // Subtle 10-bit tonal ramp bar across top-middle (exposes 8-bit vs 10-bit smoothness)
  const rampX = Math.round(width * 0.08);
  const rampY = Math.round(height * 0.14);
  const rampW = Math.round(width * 0.84);
  const rampH = Math.max(18, Math.round(height * 0.16));
  const rampGrad = ctx.createLinearGradient(rampX, 0, rampX + rampW, 0);
  rampGrad.addColorStop(0, '#040507');
  rampGrad.addColorStop(0.35, '#1F2937');
  rampGrad.addColorStop(0.7, '#64748B');
  rampGrad.addColorStop(1, '#F8FAFC');
  ctx.fillStyle = rampGrad;
  ctx.fillRect(rampX, rampY, rampW, rampH);

  // Studio color gamut swatches (Crimson, Emerald, Steel Blue, Amber, Saturated P3-edge Cyan & Magenta)
  const swatches = ['#DC2626', '#10B981', '#2563EB', '#D97706', '#06B6D4', '#E11D48'];
  const swatchY = Math.round(height * 0.40);
  const swatchH = Math.max(24, Math.round(height * 0.24));
  const gap = Math.max(4, Math.round(width * 0.015));
  const totalGap = gap * (swatches.length - 1);
  const swatchW = Math.floor((rampW - totalGap) / swatches.length);

  swatches.forEach((hex, idx) => {
    const sx = rampX + idx * (swatchW + gap);
    const g = ctx.createLinearGradient(sx, swatchY, sx, swatchY + swatchH);
    g.addColorStop(0, hex);
    g.addColorStop(1, '#111827');
    ctx.fillStyle = g;
    ctx.fillRect(sx, swatchY, swatchW, swatchH);
  });

  // Shadow-to-highlight spectrum strip at bottom
  const specY = Math.round(height * 0.72);
  const specH = Math.max(14, Math.round(height * 0.14));
  const specGrad = ctx.createLinearGradient(rampX, 0, rampX + rampW, 0);
  specGrad.addColorStop(0, '#EF4444');
  specGrad.addColorStop(0.25, '#F59E0B');
  specGrad.addColorStop(0.5, '#10B981');
  specGrad.addColorStop(0.75, '#3B82F6');
  specGrad.addColorStop(1, '#A855F7');
  ctx.fillStyle = specGrad;
  ctx.fillRect(rampX, specY, rampW, specH);
}

/**
 * Unified GPU Color Processing Pipeline.
 * Prefers WebGPU Compute + Render when available and supported, with seamless WebGL2
 * shader fallback so Preview and Recorded Video always execute identical color math.
 */
export class GpuColorPipeline {
  readonly canvas: HTMLCanvasElement;
  readonly backend: GraphicsBackendKind;

  // WebGPU state
  private gpuDevice: GPUDeviceLike | null = null;
  private gpuContext: GPUCanvasContextLike | null = null;
  private gpuFormat = 'bgra8unorm';
  private gpuComputePipeline: { getBindGroupLayout(index: number): unknown } | null = null;
  private gpuBlitPipeline: { getBindGroupLayout(index: number): unknown } | null = null;
  private gpuUniformBuffer: unknown = null;
  private gpuLinearSampler: unknown = null;
  private gpuNearestSampler: unknown = null;
  private gpuSrcTexture: GPUTextureLike | null = null;
  private gpuDstTexture: GPUTextureLike | null = null;
  private gpuSrcW = 0;
  private gpuSrcH = 0;
  private gpuDstW = 0;
  private gpuDstH = 0;

  // WebGL2 state
  private gl: WebGL2RenderingContext | null = null;
  private glProgram: WebGLProgram | null = null;
  private glVao: WebGLVertexArrayObject | null = null;
  private glPosBuf: WebGLBuffer | null = null;
  private glTexBuf: WebGLBuffer | null = null;
  private glTexture: WebGLTexture | null = null;
  private glColorModeLoc: WebGLUniformLocation | null = null;
  private glTexLoc: WebGLUniformLocation | null = null;
  private glCurrentFilter: number | null = null;

  private disposed = false;

  private constructor(canvas: HTMLCanvasElement, backend: GraphicsBackendKind) {
    this.canvas = canvas;
    this.backend = backend;
  }

  static async create(
    canvas: HTMLCanvasElement,
    preferWebGpu = true,
  ): Promise<GpuColorPipeline> {
    if (preferWebGpu) {
      const webGpuInstance = await GpuColorPipeline.tryInitWebGpu(canvas);
      if (webGpuInstance) {
        return webGpuInstance;
      }
    }
    return GpuColorPipeline.initWebGl2(canvas);
  }

  private static async tryInitWebGpu(
    canvas: HTMLCanvasElement,
  ): Promise<GpuColorPipeline | null> {
    try {
      const navGpu = (navigator as unknown as { gpu?: NavigatorGPULike }).gpu;
      if (!navGpu) return null;

      const adapter = await navGpu.requestAdapter({ powerPreference: 'high-performance' });
      if (!adapter) return null;

      const device = await adapter.requestDevice();
      const ctx = canvas.getContext('webgpu') as unknown as GPUCanvasContextLike | null;
      if (!ctx) {
        device.destroy();
        return null;
      }

      const preferredFormat = navGpu.getPreferredCanvasFormat() || 'bgra8unorm';
      ctx.configure({
        device,
        format: preferredFormat,
        alphaMode: 'opaque',
      });

      // Verify GPUTextureUsage constants exist
      const GPU_TEXTURE_USAGE_TEXTURE_BINDING = 0x04;
      const GPU_TEXTURE_USAGE_STORAGE_BINDING = 0x08;
      const GPU_TEXTURE_USAGE_COPY_DST = 0x02;
      const GPU_TEXTURE_USAGE_RENDER_ATTACHMENT = 0x10;
      const GPU_BUFFER_USAGE_UNIFORM = 0x40;
      const GPU_BUFFER_USAGE_COPY_DST = 0x08;
      void GPU_TEXTURE_USAGE_TEXTURE_BINDING;
      void GPU_TEXTURE_USAGE_STORAGE_BINDING;
      void GPU_TEXTURE_USAGE_COPY_DST;
      void GPU_TEXTURE_USAGE_RENDER_ATTACHMENT;

      const computeModule = device.createShaderModule({ code: WGSL_COMPUTE_COLOR_SHADER });
      const blitModule = device.createShaderModule({ code: WGSL_BLIT_SHADER });

      const computePipeline = device.createComputePipeline({
        layout: 'auto',
        compute: { module: computeModule, entryPoint: 'main' },
      });

      const blitPipeline = device.createRenderPipeline({
        layout: 'auto',
        vertex: { module: blitModule, entryPoint: 'vsMain' },
        fragment: {
          module: blitModule,
          entryPoint: 'fsMain',
          targets: [{ format: preferredFormat }],
        },
        primitive: { topology: 'triangle-strip' },
      });

      const uniformBuffer = device.createBuffer({
        size: 48,
        usage: GPU_BUFFER_USAGE_UNIFORM | GPU_BUFFER_USAGE_COPY_DST,
      });

      const linearSampler = device.createSampler({
        magFilter: 'linear',
        minFilter: 'linear',
      });
      const nearestSampler = device.createSampler({
        magFilter: 'nearest',
        minFilter: 'nearest',
      });

      const instance = new GpuColorPipeline(canvas, 'WebGPU Compute');
      instance.gpuDevice = device;
      instance.gpuContext = ctx;
      instance.gpuFormat = preferredFormat;
      instance.gpuComputePipeline = computePipeline;
      instance.gpuBlitPipeline = blitPipeline;
      instance.gpuUniformBuffer = uniformBuffer;
      instance.gpuLinearSampler = linearSampler;
      instance.gpuNearestSampler = nearestSampler;
      return instance;
    } catch {
      return null;
    }
  }

  private static initWebGl2(canvas: HTMLCanvasElement): GpuColorPipeline {
    const gl = canvas.getContext('webgl2', {
      alpha: false,
      antialias: false,
      depth: false,
      stencil: false,
      desynchronized: true,
      powerPreference: 'high-performance',
      preserveDrawingBuffer: true,
    });
    if (!gl) {
      throw new Error('Neither WebGPU nor WebGL2 is available in this browser.');
    }

    const vs = GpuColorPipeline.compileGlShader(gl, gl.VERTEX_SHADER, GLSL3_VERTEX_SHADER);
    const fs = GpuColorPipeline.compileGlShader(gl, gl.FRAGMENT_SHADER, GLSL3_FRAGMENT_SHADER);
    const program = gl.createProgram();
    if (!program) {
      throw new Error('Failed to create WebGL2 shader program.');
    }
    gl.attachShader(program, vs);
    gl.attachShader(program, fs);
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      const log = gl.getProgramInfoLog(program) || 'Unknown link error';
      gl.deleteProgram(program);
      throw new Error(`WebGL2 program link error: ${log}`);
    }
    gl.deleteShader(vs);
    gl.deleteShader(fs);

    gl.disable(gl.DITHER);

    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);

    const posLoc = gl.getAttribLocation(program, 'aPosition');
    const texLoc = gl.getAttribLocation(program, 'aTexCoord');

    const posBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, posBuf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.DYNAMIC_DRAW);
    gl.enableVertexAttribArray(posLoc);
    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);

    const texBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, texBuf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 1, 1, 1, 0, 0, 1, 0]), gl.DYNAMIC_DRAW);
    gl.enableVertexAttribArray(texLoc);
    gl.vertexAttribPointer(texLoc, 2, gl.FLOAT, false, 0, 0);

    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);

    gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE);
    gl.bindVertexArray(null);

    const instance = new GpuColorPipeline(canvas, 'WebGL2 Shader');
    instance.gl = gl;
    instance.glProgram = program;
    instance.glVao = vao;
    instance.glPosBuf = posBuf;
    instance.glTexBuf = texBuf;
    instance.glTexture = texture;
    instance.glColorModeLoc = gl.getUniformLocation(program, 'uColorMode');
    instance.glTexLoc = gl.getUniformLocation(program, 'uTexture');
    instance.glCurrentFilter = gl.LINEAR;
    return instance;
  }

  private static compileGlShader(
    gl: WebGL2RenderingContext,
    type: number,
    source: string,
  ): WebGLShader {
    const shader = gl.createShader(type);
    if (!shader) {
      throw new Error('Failed to allocate WebGL2 shader.');
    }
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const info = gl.getShaderInfoLog(shader) || 'Unknown shader compile error';
      gl.deleteShader(shader);
      throw new Error(`WebGL2 shader compile error: ${info}`);
    }
    return shader;
  }

  renderFrame(params: RenderFrameParams): void {
    if (this.disposed) return;
    const dstW = Math.max(2, this.canvas.width);
    const dstH = Math.max(2, this.canvas.height);

    if (this.backend === 'WebGPU Compute') {
      this.renderWebGpu(params, dstW, dstH);
    } else {
      this.renderWebGl2(params, dstW, dstH);
    }
  }

  private renderWebGpu(params: RenderFrameParams, dstW: number, dstH: number): void {
    const device = this.gpuDevice;
    const ctx = this.gpuContext;
    const computePipeline = this.gpuComputePipeline;
    const blitPipeline = this.gpuBlitPipeline;
    if (!device || !ctx || !computePipeline || !blitPipeline) return;

    const srcW = Math.max(1, params.sourceWidth);
    const srcH = Math.max(1, params.sourceHeight);

    if (!this.gpuSrcTexture || this.gpuSrcW !== srcW || this.gpuSrcH !== srcH) {
      this.gpuSrcTexture?.destroy();
      this.gpuSrcTexture = device.createTexture({
        size: [srcW, srcH],
        format: 'rgba8unorm',
        // TEXTURE_BINDING (0x04) | COPY_DST (0x02) | RENDER_ATTACHMENT (0x10)
        usage: 0x04 | 0x02 | 0x10,
      });
      this.gpuSrcW = srcW;
      this.gpuSrcH = srcH;
    }

    if (!this.gpuDstTexture || this.gpuDstW !== dstW || this.gpuDstH !== dstH) {
      this.gpuDstTexture?.destroy();
      this.gpuDstTexture = device.createTexture({
        size: [dstW, dstH],
        format: 'rgba16float',
        // TEXTURE_BINDING (0x04) | STORAGE_BINDING (0x08)
        usage: 0x04 | 0x08,
      });
      this.gpuDstW = dstW;
      this.gpuDstH = dstH;
      ctx.configure({
        device,
        format: this.gpuFormat,
        alphaMode: 'opaque',
      });
    }

    device.queue.copyExternalImageToTexture(
      { source: params.source },
      { texture: this.gpuSrcTexture },
      [srcW, srcH],
    );

    const { sLeft, sTop, sWidth, sHeight, xScale, yScale, is1To1 } = computeCropAndScale(
      srcW,
      srcH,
      dstW,
      dstH,
      params.cropRegion,
    );

    const uniformBytes = new ArrayBuffer(48);
    const f32 = new Float32Array(uniformBytes);
    const u32 = new Uint32Array(uniformBytes);
    f32[0] = sLeft;
    f32[1] = sTop;
    f32[2] = sWidth;
    f32[3] = sHeight;
    f32[4] = xScale;
    f32[5] = yScale;
    u32[6] = colorModeToInt(params.colorMode);
    u32[7] = dstW;
    u32[8] = dstH;
    device.queue.writeBuffer(this.gpuUniformBuffer, 0, f32);

    const sampler = is1To1 ? this.gpuNearestSampler : this.gpuLinearSampler;

    const computeBindGroup = device.createBindGroup({
      layout: computePipeline.getBindGroupLayout(0),
      entries: [
        { binding: 0, resource: sampler },
        { binding: 1, resource: this.gpuSrcTexture.createView() },
        { binding: 2, resource: this.gpuDstTexture.createView() },
        { binding: 3, resource: { buffer: this.gpuUniformBuffer } },
      ],
    });

    const blitBindGroup = device.createBindGroup({
      layout: blitPipeline.getBindGroupLayout(0),
      entries: [
        { binding: 0, resource: this.gpuLinearSampler },
        { binding: 1, resource: this.gpuDstTexture.createView() },
      ],
    });

    const encoder = device.createCommandEncoder();

    const computePass = encoder.beginComputePass();
    computePass.setPipeline(computePipeline);
    computePass.setBindGroup(0, computeBindGroup);
    computePass.dispatchWorkgroups(Math.ceil(dstW / 8), Math.ceil(dstH / 8), 1);
    computePass.end();

    const renderPass = encoder.beginRenderPass({
      colorAttachments: [
        {
          view: ctx.getCurrentTexture().createView(),
          clearValue: { r: 0, g: 0, b: 0, a: 1 },
          loadOp: 'clear',
          storeOp: 'store',
        },
      ],
    });
    renderPass.setPipeline(blitPipeline);
    renderPass.setBindGroup(0, blitBindGroup);
    renderPass.draw(4);
    renderPass.end();

    device.queue.submit([encoder.finish()]);
  }

  private renderWebGl2(params: RenderFrameParams, dstW: number, dstH: number): void {
    const gl = this.gl;
    if (!gl || !this.glProgram || !this.glVao || !this.glTexture) return;

    const srcW = Math.max(1, params.sourceWidth);
    const srcH = Math.max(1, params.sourceHeight);

    const { sLeft, sTop, sWidth, sHeight, xScale, yScale, is1To1 } = computeCropAndScale(
      srcW,
      srcH,
      dstW,
      dstH,
      params.cropRegion,
    );

    const sRight = sLeft + sWidth;
    const sBottom = sTop + sHeight;

    const glAny = gl as WebGL2RenderingContext & { drawingBufferColorSpace?: string };
    if ('drawingBufferColorSpace' in glAny) {
      try {
        glAny.drawingBufferColorSpace =
          params.colorMode === 'DEEP_COLOR_10BIT' ? 'display-p3' : 'srgb';
      } catch {
        // Fallback to default sRGB drawing buffer
      }
    }

    gl.viewport(0, 0, dstW, dstH);
    gl.clearColor(0, 0, 0, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);

    gl.useProgram(this.glProgram);
    gl.bindVertexArray(this.glVao);

    gl.bindBuffer(gl.ARRAY_BUFFER, this.glPosBuf);
    gl.bufferSubData(
      gl.ARRAY_BUFFER,
      0,
      new Float32Array([-xScale, -yScale, xScale, -yScale, -xScale, yScale, xScale, yScale]),
    );

    gl.bindBuffer(gl.ARRAY_BUFFER, this.glTexBuf);
    gl.bufferSubData(
      gl.ARRAY_BUFFER,
      0,
      new Float32Array([sLeft, sBottom, sRight, sBottom, sLeft, sTop, sRight, sTop]),
    );

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this.glTexture);

    const desiredFilter = is1To1 ? gl.NEAREST : gl.LINEAR;
    if (desiredFilter !== this.glCurrentFilter) {
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, desiredFilter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, desiredFilter);
      this.glCurrentFilter = desiredFilter;
    }

    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, params.source);

    gl.uniform1i(this.glTexLoc, 0);
    gl.uniform1i(this.glColorModeLoc, colorModeToInt(params.colorMode));

    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.bindVertexArray(null);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;

    if (this.gpuDevice) {
      this.gpuSrcTexture?.destroy();
      this.gpuDstTexture?.destroy();
      this.gpuContext?.unconfigure?.();
      this.gpuDevice.destroy();
      this.gpuDevice = null;
      this.gpuContext = null;
    }

    if (this.gl) {
      if (this.glTexture) this.gl.deleteTexture(this.glTexture);
      if (this.glPosBuf) this.gl.deleteBuffer(this.glPosBuf);
      if (this.glTexBuf) this.gl.deleteBuffer(this.glTexBuf);
      if (this.glVao) this.gl.deleteVertexArray(this.glVao);
      if (this.glProgram) this.gl.deleteProgram(this.glProgram);
      this.gl = null;
    }
  }
}
