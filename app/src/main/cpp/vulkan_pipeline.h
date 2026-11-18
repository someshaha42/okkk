#pragma once

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vulkan/vulkan.h>

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace framecut {

struct ColorPushConstants {
    float uvRect[4];     // u0, v0, uw, vh
    uint32_t dstSize[2]; // dstWidth, dstHeight
    uint32_t colorMode;  // 0 = Original, 1 = Full RGB, 2 = Deep Color 10-bit
    uint32_t frameIndex; // monotonic frame index
};

struct SwapchainTarget {
    ANativeWindow* window = nullptr;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent2D extent{0, 0};
    std::vector<VkImage> images;
    VkSemaphore imageAvailableSem = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSem = VK_NULL_HANDLE;
    bool prefer10Bit = false;
    bool needsRecreate = false;
};

class VulkanPipeline {
public:
    VulkanPipeline() = default;
    ~VulkanPipeline();

    VulkanPipeline(const VulkanPipeline&) = delete;
    VulkanPipeline& operator=(const VulkanPipeline&) = delete;

    bool initialize();
    void shutdown();

    void setPreviewWindow(ANativeWindow* window, uint32_t width, uint32_t height, bool prefer10Bit);
    void setEncoderWindow(ANativeWindow* window, uint32_t width, uint32_t height, bool prefer10Bit);

    bool processHardwareBuffer(
        AHardwareBuffer* buffer,
        float u0, float v0, float uw, float vh,
        uint32_t dstWidth, uint32_t dstHeight,
        uint32_t colorMode, uint32_t frameIndex
    );

    bool processRgbaBytes(
        const uint8_t* rgbaData,
        size_t dataSize,
        uint32_t srcWidth, uint32_t srcHeight,
        float u0, float v0, float uw, float vh,
        uint32_t dstWidth, uint32_t dstHeight,
        uint32_t colorMode, uint32_t frameIndex
    );

    std::string getDeviceInfoString() const;

private:
    bool loadVulkanFunctions();
    bool createInstance();
    bool selectPhysicalDevice();
    bool createLogicalDevice();
    bool createCommandResources();
    bool createComputePipeline();

    bool ensureSourceImage(uint32_t width, uint32_t height);
    bool ensureProcessedImage(uint32_t width, uint32_t height);
    bool ensureStagingBuffer(VkDeviceSize requiredBytes);
    bool ensureTargetSwapchain(SwapchainTarget& target);
    void destroyTargetSwapchain(SwapchainTarget& target, bool releaseWindow);

    bool dispatchAndPresent(
        VkImage sourceImage,
        VkImageView sourceView,
        VkImageLayout currentSourceLayout,
        const ColorPushConstants& pushConstants,
        bool isExternalAhbSource
    );

    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const;
    void recordImageBarrier(
        VkCommandBuffer cmd,
        VkImage image,
        VkImageLayout oldLayout,
        VkImageLayout newLayout,
        VkAccessFlags srcAccess,
        VkAccessFlags dstAccess,
        VkPipelineStageFlags srcStage,
        VkPipelineStageFlags dstStage
    );

    mutable std::mutex mMutex;
    bool mInitialized = false;
    void* mVulkanLib = nullptr;

    // Device properties / info
    std::string mGpuName = "UNAVAILABLE";
    std::string mApiVersion = "1.1";
    bool mSupportsAhbImport = false;
    bool mSupports10BitOutput = false;
    VkFormat mProcessedFormat = VK_FORMAT_R16G16B16A16_SFLOAT;

    // Core Vulkan objects
    VkInstance mInstance = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;
    uint32_t mQueueFamilyIndex = 0;
    VkQueue mQueue = VK_NULL_HANDLE;

    VkCommandPool mCommandPool = VK_NULL_HANDLE;
    VkCommandBuffer mCommandBuffer = VK_NULL_HANDLE;
    VkFence mSubmitFence = VK_NULL_HANDLE;

    // Compute shader & descriptors
    VkSampler mLinearSampler = VK_NULL_HANDLE;
    VkDescriptorSetLayout mDescriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool mDescriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet mDescriptorSet = VK_NULL_HANDLE;
    VkPipelineLayout mPipelineLayout = VK_NULL_HANDLE;
    VkShaderModule mShaderModule = VK_NULL_HANDLE;
    VkPipeline mComputePipeline = VK_NULL_HANDLE;

    // Staging upload source image (universal path)
    VkImage mSourceImage = VK_NULL_HANDLE;
    VkDeviceMemory mSourceMemory = VK_NULL_HANDLE;
    VkImageView mSourceView = VK_NULL_HANDLE;
    uint32_t mSourceWidth = 0;
    uint32_t mSourceHeight = 0;
    VkImageLayout mSourceLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    // Host staging buffer
    VkBuffer mStagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory mStagingMemory = VK_NULL_HANDLE;
    VkDeviceSize mStagingCapacity = 0;

    // Intermediate compute output image shared by Preview & MediaCodec
    VkImage mProcessedImage = VK_NULL_HANDLE;
    VkDeviceMemory mProcessedMemory = VK_NULL_HANDLE;
    VkImageView mProcessedView = VK_NULL_HANDLE;
    uint32_t mProcessedWidth = 0;
    uint32_t mProcessedHeight = 0;
    VkImageLayout mProcessedLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    // Dual output swapchains (Preview + MediaCodec Encoder)
    SwapchainTarget mPreviewTarget;
    SwapchainTarget mEncoderTarget;
};

} // namespace framecut
