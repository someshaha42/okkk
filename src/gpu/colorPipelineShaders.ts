/**
 * Unified GPU Color Processing Shaders (WGSL Compute + Render and GLSL ES 3.00).
 * Both Preview and Recorded Video execute these identical color transforms:
 *   colorMode = 0 -> Original Color (1:1 native passthrough)
 *   colorMode = 1 -> Full RGB (0-255 full-range linear sRGB precision)
 *   colorMode = 2 -> Deep Color (10-bit linear wide-gamut + 1024-step dither)
 */

export const WGSL_COMPUTE_COLOR_SHADER = /* wgsl */ `
struct PipelineParams {
  cropRect: vec4<f32>,      // left, top, width, height in normalized [0..1]
  scaleXY: vec2<f32>,       // aspect-preserving scale in NDC
  colorMode: u32,           // 0 = Original, 1 = Full RGB, 2 = Deep Color 10-bit
  dstWidth: u32,
  dstHeight: u32,
  _pad0: u32,
  _pad1: u32,
  _pad2: u32,
};

@group(0) @binding(0) var srcSampler: sampler;
@group(0) @binding(1) var srcTex: texture_2d<f32>;
@group(0) @binding(2) var dstTex: texture_storage_2d<rgba16float, write>;
@group(0) @binding(3) var<uniform> params: PipelineParams;

fn srgbToLinear(c: vec3<f32>) -> vec3<f32> {
  let clamped = max(c, vec3<f32>(0.0));
  let low = clamped / 12.92;
  let high = pow((clamped + 0.055) / 1.055, vec3<f32>(2.4));
  let mask = step(vec3<f32>(0.04045), clamped);
  return mix(low, high, mask);
}

fn linearToSrgb(c: vec3<f32>) -> vec3<f32> {
  let clamped = clamp(c, vec3<f32>(0.0), vec3<f32>(1.0));
  let low = clamped * 12.92;
  let safeBase = max(clamped, vec3<f32>(0.0031308));
  let high = 1.055 * pow(safeBase, vec3<f32>(1.0 / 2.4)) - 0.055;
  let mask = step(vec3<f32>(0.0031308), clamped);
  return mix(low, high, mask);
}

fn interleavedGradientNoise(coord: vec2<f32>) -> f32 {
  let magic = vec3<f32>(0.06711056, 0.00583715, 52.9829189);
  return fract(magic.z * fract(dot(coord, magic.xy)));
}

fn processColor(rgb: vec3<f32>, pixelCoord: vec2<f32>, mode: u32) -> vec3<f32> {
  if (mode == 0u) {
    // Original Color: exact 1:1 passthrough
    return clamp(rgb, vec3<f32>(0.0), vec3<f32>(1.0));
  }

  if (mode == 1u) {
    // Full RGB: linear-light sRGB precision preserving full [0..255] black & white endpoints
    let lin = srgbToLinear(rgb);
    let full = clamp(lin, vec3<f32>(0.0), vec3<f32>(1.0));
    return linearToSrgb(full);
  }

  // Deep Color (10-bit): linear wide-gamut Display P3 precision + 1024-level smooth micro-dither
  let lin = srgbToLinear(rgb);
  // Subtle sRGB -> Display P3 wide-gamut expansion matrix with soft roll-off
  let p3Mat = mat3x3<f32>(
    vec3<f32>(0.822462, 0.033194, 0.017083),
    vec3<f32>(0.177538, 0.966806, 0.072397),
    vec3<f32>(0.000000, 0.000000, 0.910520)
  );
  let p3Lin = max(p3Mat * lin, vec3<f32>(0.0));
  let srgbOut = linearToSrgb(p3Lin);
  // Triangular 10-bit (1023-step) dither prevents tonal banding on dark UI & gradients
  let noise = interleavedGradientNoise(pixelCoord) - 0.5;
  return clamp(srgbOut + vec3<f32>(noise / 1023.0), vec3<f32>(0.0), vec3<f32>(1.0));
}

@compute @workgroup_size(8, 8, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  if (gid.x >= params.dstWidth || gid.y >= params.dstHeight) {
    return;
  }

  let u = (f32(gid.x) + 0.5) / f32(params.dstWidth);
  let v = (f32(gid.y) + 0.5) / f32(params.dstHeight);

  // Inverse aspect fit mapping into [-1..1] -> [0..1]
  let nx = (u * 2.0 - 1.0) / max(params.scaleXY.x, 0.001);
  let ny = (v * 2.0 - 1.0) / max(params.scaleXY.y, 0.001);

  if (abs(nx) > 1.0 || abs(ny) > 1.0) {
    textureStore(dstTex, vec2<i32>(gid.xy), vec4<f32>(0.0, 0.0, 0.0, 1.0));
    return;
  }

  let localU = nx * 0.5 + 0.5;
  let localV = ny * 0.5 + 0.5;
  let sampleUV = vec2<f32>(
    params.cropRect.x + localU * params.cropRect.z,
    params.cropRect.y + localV * params.cropRect.w
  );

  let texel = textureSampleLevel(srcTex, srcSampler, sampleUV, 0.0);
  let processed = processColor(texel.rgb, vec2<f32>(f32(gid.x), f32(gid.y)), params.colorMode);
  textureStore(dstTex, vec2<i32>(gid.xy), vec4<f32>(processed, 1.0));
}
`;

export const WGSL_BLIT_SHADER = /* wgsl */ `
struct VertexOut {
  @builtin(position) pos: vec4<f32>,
  @location(0) uv: vec2<f32>,
};

@vertex
fn vsMain(@builtin(vertex_index) vid: u32) -> VertexOut {
  var positions = array<vec2<f32>, 4>(
    vec2<f32>(-1.0, -1.0),
    vec2<f32>( 1.0, -1.0),
    vec2<f32>(-1.0,  1.0),
    vec2<f32>( 1.0,  1.0)
  );
  var uvs = array<vec2<f32>, 4>(
    vec2<f32>(0.0, 1.0),
    vec2<f32>(1.0, 1.0),
    vec2<f32>(0.0, 0.0),
    vec2<f32>(1.0, 0.0)
  );
  var out: VertexOut;
  out.pos = vec4<f32>(positions[vid], 0.0, 1.0);
  out.uv = uvs[vid];
  return out;
}

@group(0) @binding(0) var blitSampler: sampler;
@group(0) @binding(1) var processedTex: texture_2d<f32>;

@fragment
fn fsMain(@location(0) uv: vec2<f32>) -> @location(0) vec4<f32> {
  return textureSample(processedTex, blitSampler, uv);
}
`;

export const GLSL3_VERTEX_SHADER = `#version 300 es
precision highp float;
in vec2 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
  gl_Position = vec4(aPosition, 0.0, 1.0);
  vTexCoord = aTexCoord;
}
`;

export const GLSL3_FRAGMENT_SHADER = `#version 300 es
precision highp float;
in vec2 vTexCoord;
uniform sampler2D uTexture;
uniform int uColorMode;
out vec4 fragColor;

vec3 srgbToLinear(vec3 c) {
  vec3 clamped = max(c, vec3(0.0));
  vec3 low = clamped / 12.92;
  vec3 high = pow((clamped + 0.055) / 1.055, vec3(2.4));
  vec3 mask = step(vec3(0.04045), clamped);
  return mix(low, high, mask);
}

vec3 linearToSrgb(vec3 c) {
  vec3 clamped = clamp(c, 0.0, 1.0);
  vec3 low = clamped * 12.92;
  vec3 safeBase = max(clamped, vec3(0.0031308));
  vec3 high = 1.055 * pow(safeBase, vec3(1.0 / 2.4)) - 0.055;
  vec3 mask = step(vec3(0.0031308), clamped);
  return mix(low, high, mask);
}

float interleavedGradientNoise(vec2 coord) {
  vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
  return fract(magic.z * fract(dot(coord, magic.xy)));
}

void main() {
  vec4 texel = texture(uTexture, vTexCoord);
  if (uColorMode == 0) {
    // Original Color: 1:1 passthrough
    fragColor = vec4(clamp(texel.rgb, 0.0, 1.0), 1.0);
  } else if (uColorMode == 1) {
    // Full RGB: 0-255 full-range linear sRGB precision
    vec3 lin = srgbToLinear(texel.rgb);
    fragColor = vec4(linearToSrgb(clamp(lin, 0.0, 1.0)), 1.0);
  } else {
    // Deep Color (10-bit): wide-gamut linear Display P3 + 1024-step gradient dither
    vec3 lin = srgbToLinear(texel.rgb);
    mat3 p3Mat = mat3(
      0.822462, 0.033194, 0.017083,
      0.177538, 0.966806, 0.072397,
      0.000000, 0.000000, 0.910520
    );
    vec3 p3Lin = max(p3Mat * lin, vec3(0.0));
    vec3 srgbOut = linearToSrgb(p3Lin);
    float noise = interleavedGradientNoise(gl_FragCoord.xy) - 0.5;
    fragColor = vec4(clamp(srgbOut + vec3(noise / 1023.0), 0.0, 1.0), 1.0);
  }
}
`;
