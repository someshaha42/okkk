#include "vulkan_pipeline.h"

#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <jni.h>

using framecut::VulkanPipeline;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_vulkan_VulkanColorProcessor_nativeCreate(JNIEnv*, jclass) {
    auto* pipeline = new VulkanPipeline();
    if (!pipeline->initialize()) {
        delete pipeline;
        return 0L;
    }
    return reinterpret_cast<jlong>(pipeline);
}

JNIEXPORT jstring JNICALL
Java_com_example_vulkan_VulkanColorProcessor_nativeGetInfo(JNIEnv* env, jclass, jlong handle) {
    if (handle == 0L) {
        return env->NewStringUTF("UNAVAILABLE|0.0|0|0");
    }
    auto* pipeline = reinterpret_cast<VulkanPipeline*>(handle);
    return env->NewStringUTF(pipeline->getDeviceInfoString().c_str());
}

JNIEXPORT void JNICALL
Java_com_example_vulkan_VulkanColorProcessor_nativeSetPreviewSurface(
    JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height, jboolean prefer10Bit
) {
    if (handle == 0L) return;
    auto* pipeline = reinterpret_cast<VulkanPipeline*>(handle);
    ANativeWindow* window = surface != nullptr ? ANativeWindow_fromSurface(env, surface) : nullptr;
    pipeline->setPreviewWindow(
        window,
        static_cast<uint32_t>(width > 0 ? width : 2),
        static_cast<uint32_t>(height > 0 ? height : 2),
        prefer10Bit == JNI_TRUE
    );
}

JNIEXPORT void JNICALL
Java_com_example_vulkan_VulkanColorProcessor_nativeSetEncoderSurface(
    JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height, jboolean prefer10Bit
) {
    if (handle == 0L) return;
    auto* pipeline = reinterpret_cast<VulkanPipeline*>(handle);
    ANativeWindow* window = surface != nullptr ? ANativeWindow_fromSurface(env, surface) : nullptr;
    pipeline->setEncoderWindow(
        window,
        static_cast<uint32_t>(width > 0 ? width : 2),
        static_cast<uint32_t>(height > 0 ? height : 2),
        prefer10Bit == JNI_TRUE
    );
}

JNIEXPORT jboolean JNICALL
Java_com_example_vulkan_VulkanColorProcessor_nativeProcessHardwareBuffer(
    JNIEnv* env, jclass, jlong handle, jobject hwBufferObj,
    jfloat u0, jfloat v0, jfloat uw, jfloat vh,
    jint dstWidth, jint dstHeight, jint colorMode, jint frameIndex
) {
    if (handle == 0L || hwBufferObj == nullptr || dstWidth <= 0 || dstHeight <= 0) {
        return JNI_FALSE;
    }
    auto* pipeline = reinterpret_cast<VulkanPipeline*>(handle);
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, hwBufferObj);
    if (ahb == nullptr) return JNI_FALSE;

    const bool ok = pipeline->processHardwareBuffer(
        ahb, u0, v0, uw, vh,
        static_cast<uint32_t>(dstWidth),
        static_cast<uint32_t>(dstHeight),
        static_cast<uint32_t>(colorMode),
        static_cast<uint32_t>(frameIndex)
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_vulkan_VulkanColorProcessor_nativeProcessRgbaBuffer(
    JNIEnv* env, jclass, jlong handle, jobject byteBuffer,
    jint srcWidth, jint srcHeight,
    jfloat u0, jfloat v0, jfloat uw, jfloat vh,
    jint dstWidth, jint dstHeight, jint colorMode, jint frameIndex
) {
    if (handle == 0L || byteBuffer == nullptr || srcWidth <= 0 || srcHeight <= 0 ||
        dstWidth <= 0 || dstHeight <= 0) {
        return JNI_FALSE;
    }
    auto* pipeline = reinterpret_cast<VulkanPipeline*>(handle);
    auto* ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    const jlong cap = env->GetDirectBufferCapacity(byteBuffer);
    if (ptr == nullptr || cap <= 0) return JNI_FALSE;

    const bool ok = pipeline->processRgbaBytes(
        ptr, static_cast<size_t>(cap),
        static_cast<uint32_t>(srcWidth),
        static_cast<uint32_t>(srcHeight),
        u0, v0, uw, vh,
        static_cast<uint32_t>(dstWidth),
        static_cast<uint32_t>(dstHeight),
        static_cast<uint32_t>(colorMode),
        static_cast<uint32_t>(frameIndex)
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_vulkan_VulkanColorProcessor_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    if (handle == 0L) return;
    auto* pipeline = reinterpret_cast<VulkanPipeline*>(handle);
    delete pipeline;
}

} // extern "C"
