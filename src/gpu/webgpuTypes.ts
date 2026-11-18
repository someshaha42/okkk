export interface GPUAdapterLike {
  requestDevice(): Promise<GPUDeviceLike>;
  info?: { architecture?: string; description?: string; vendor?: string };
}

export interface GPUQueueLike {
  submit(commandBuffers: unknown[]): void;
  writeBuffer(buffer: unknown, bufferOffset: number, data: ArrayBufferView): void;
  copyExternalImageToTexture(
    source: { source: HTMLCanvasElement | HTMLVideoElement | ImageBitmap },
    destination: { texture: GPUTextureLike },
    copySize: [number, number] | { width: number; height: number },
  ): void;
}

export interface GPUTextureLike {
  createView(): unknown;
  destroy(): void;
  width: number;
  height: number;
}

export interface GPUDeviceLike {
  queue: GPUQueueLike;
  createShaderModule(descriptor: { code: string }): unknown;
  createTexture(descriptor: {
    size: [number, number] | { width: number; height: number };
    format: string;
    usage: number;
  }): GPUTextureLike;
  createBuffer(descriptor: { size: number; usage: number }): unknown;
  createSampler(descriptor: {
    magFilter: 'nearest' | 'linear';
    minFilter: 'nearest' | 'linear';
  }): unknown;
  createComputePipeline(descriptor: {
    layout: 'auto';
    compute: { module: unknown; entryPoint: string };
  }): { getBindGroupLayout(index: number): unknown };
  createRenderPipeline(descriptor: {
    layout: 'auto';
    vertex: { module: unknown; entryPoint: string };
    fragment: {
      module: unknown;
      entryPoint: string;
      targets: Array<{ format: string }>;
    };
    primitive?: { topology: string };
  }): { getBindGroupLayout(index: number): unknown };
  createBindGroup(descriptor: {
    layout: unknown;
    entries: Array<{ binding: number; resource: unknown }>;
  }): unknown;
  createCommandEncoder(): {
    beginComputePass(): {
      setPipeline(pipeline: unknown): void;
      setBindGroup(index: number, bindGroup: unknown): void;
      dispatchWorkgroups(x: number, y?: number, z?: number): void;
      end(): void;
    };
    beginRenderPass(descriptor: {
      colorAttachments: Array<{
        view: unknown;
        clearValue?: { r: number; g: number; b: number; a: number };
        loadOp: 'clear' | 'load';
        storeOp: 'store' | 'discard';
      }>;
    }): {
      setPipeline(pipeline: unknown): void;
      setBindGroup(index: number, bindGroup: unknown): void;
      draw(vertexCount: number): void;
      end(): void;
    };
    finish(): unknown;
  };
  destroy(): void;
}

export interface GPUCanvasContextLike {
  configure(config: {
    device: GPUDeviceLike;
    format: string;
    alphaMode?: 'opaque' | 'premultiplied';
  }): void;
  getCurrentTexture(): GPUTextureLike;
  unconfigure?(): void;
}

export interface NavigatorGPULike {
  requestAdapter(options?: { powerPreference?: 'high-performance' | 'low-power' }): Promise<GPUAdapterLike | null>;
  getPreferredCanvasFormat(): string;
}
