#include "vulkan_pipeline.h"
#include "vulkan_shaders_spv.h"

#include <android/log.h>
#include <dlfcn.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <sstream>

#define LOG_TAG "FrameCutVulkan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace framecut {

namespace {

// Loaded Vulkan function pointers (global + instance + device)
PFN_vkGetInstanceProcAddr pfnGetInstanceProcAddr = nullptr;
PFN_vkEnumerateInstanceExtensionProperties pfnEnumerateInstanceExtensionProperties = nullptr;
PFN_vkCreateInstance pfnCreateInstance = nullptr;
PFN_vkDestroyInstance pfnDestroyInstance = nullptr;
PFN_vkEnumeratePhysicalDevices pfnEnumeratePhysicalDevices = nullptr;
PFN_vkGetPhysicalDeviceProperties pfnGetPhysicalDeviceProperties = nullptr;
PFN_vkGetPhysicalDeviceFormatProperties pfnGetPhysicalDeviceFormatProperties = nullptr;
PFN_vkGetPhysicalDeviceMemoryProperties pfnGetPhysicalDeviceMemoryProperties = nullptr;
PFN_vkGetPhysicalDeviceQueueFamilyProperties pfnGetPhysicalDeviceQueueFamilyProperties = nullptr;
PFN_vkEnumerateDeviceExtensionProperties pfnEnumerateDeviceExtensionProperties = nullptr;
PFN_vkCreateDevice pfnCreateDevice = nullptr;
PFN_vkDestroyDevice pfnDestroyDevice = nullptr;
PFN_vkGetDeviceQueue pfnGetDeviceQueue = nullptr;
PFN_vkDeviceWaitIdle pfnDeviceWaitIdle = nullptr;

PFN_vkCreateAndroidSurfaceKHR pfnCreateAndroidSurfaceKHR = nullptr;
PFN_vkDestroySurfaceKHR pfnDestroySurfaceKHR = nullptr;
PFN_vkGetPhysicalDeviceSurfaceSupportKHR pfnGetPhysicalDeviceSurfaceSupportKHR = nullptr;
PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR pfnGetPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
PFN_vkGetPhysicalDeviceSurfaceFormatsKHR pfnGetPhysicalDeviceSurfaceFormatsKHR = nullptr;
PFN_vkGetPhysicalDeviceSurfacePresentModesKHR pfnGetPhysicalDeviceSurfacePresentModesKHR = nullptr;

PFN_vkCreateSwapchainKHR pfnCreateSwapchainKHR = nullptr;
PFN_vkDestroySwapchainKHR pfnDestroySwapchainKHR = nullptr;
PFN_vkGetSwapchainImagesKHR pfnGetSwapchainImagesKHR = nullptr;
PFN_vkAcquireNextImageKHR pfnAcquireNextImageKHR = nullptr;
PFN_vkQueuePresentKHR pfnQueuePresentKHR = nullptr;

PFN_vkCreateCommandPool pfnCreateCommandPool = nullptr;
PFN_vkDestroyCommandPool pfnDestroyCommandPool = nullptr;
PFN_vkAllocateCommandBuffers pfnAllocateCommandBuffers = nullptr;
PFN_vkResetCommandBuffer pfnResetCommandBuffer = nullptr;
PFN_vkBeginCommandBuffer pfnBeginCommandBuffer = nullptr;
PFN_vkEndCommandBuffer pfnEndCommandBuffer = nullptr;

PFN_vkCreateFence pfnCreateFence = nullptr;
PFN_vkDestroyFence pfnDestroyFence = nullptr;
PFN_vkWaitForFences pfnWaitForFences = nullptr;
PFN_vkResetFences pfnResetFences = nullptr;
PFN_vkCreateSemaphore pfnCreateSemaphore = nullptr;
PFN_vkDestroySemaphore pfnDestroySemaphore = nullptr;

PFN_vkCreateBuffer pfnCreateBuffer = nullptr;
PFN_vkDestroyBuffer pfnDestroyBuffer = nullptr;
PFN_vkGetBufferMemoryRequirements pfnGetBufferMemoryRequirements = nullptr;
PFN_vkBindBufferMemory pfnBindBufferMemory = nullptr;

PFN_vkCreateImage pfnCreateImage = nullptr;
PFN_vkDestroyImage pfnDestroyImage = nullptr;
PFN_vkGetImageMemoryRequirements pfnGetImageMemoryRequirements = nullptr;
PFN_vkBindImageMemory pfnBindImageMemory = nullptr;
PFN_vkCreateImageView pfnCreateImageView = nullptr;
PFN_vkDestroyImageView pfnDestroyImageView = nullptr;

PFN_vkAllocateMemory pfnAllocateMemory = nullptr;
PFN_vkFreeMemory pfnFreeMemory = nullptr;
PFN_vkMapMemory pfnMapMemory = nullptr;
PFN_vkUnmapMemory pfnUnmapMemory = nullptr;

PFN_vkCreateSampler pfnCreateSampler = nullptr;
PFN_vkDestroySampler pfnDestroySampler = nullptr;
PFN_vkCreateDescriptorSetLayout pfnCreateDescriptorSetLayout = nullptr;
PFN_vkDestroyDescriptorSetLayout pfnDestroyDescriptorSetLayout = nullptr;
PFN_vkCreateDescriptorPool pfnCreateDescriptorPool = nullptr;
PFN_vkDestroyDescriptorPool pfnDestroyDescriptorPool = nullptr;
PFN_vkAllocateDescriptorSets pfnAllocateDescriptorSets = nullptr;
PFN_vkUpdateDescriptorSets pfnUpdateDescriptorSets = nullptr;
PFN_vkCreatePipelineLayout pfnCreatePipelineLayout = nullptr;
PFN_vkDestroyPipelineLayout pfnDestroyPipelineLayout = nullptr;
PFN_vkCreateShaderModule pfnCreateShaderModule = nullptr;
PFN_vkDestroyShaderModule pfnDestroyShaderModule = nullptr;
PFN_vkCreateComputePipelines pfnCreateComputePipelines = nullptr;
PFN_vkDestroyPipeline pfnDestroyPipeline = nullptr;

PFN_vkCmdBindPipeline pfnCmdBindPipeline = nullptr;
PFN_vkCmdBindDescriptorSets pfnCmdBindDescriptorSets = nullptr;
PFN_vkCmdPushConstants pfnCmdPushConstants = nullptr;
PFN_vkCmdDispatch pfnCmdDispatch = nullptr;
PFN_vkCmdPipelineBarrier pfnCmdPipelineBarrier = nullptr;
PFN_vkCmdCopyBufferToImage pfnCmdCopyBufferToImage = nullptr;
PFN_vkCmdBlitImage pfnCmdBlitImage = nullptr;
PFN_vkQueueSubmit pfnQueueSubmit = nullptr;

// Optional zero-copy AHardwareBuffer extension functions
PFN_vkGetAndroidHardwareBufferPropertiesANDROID pfnGetAndroidHardwareBufferPropertiesANDROID = nullptr;

template <typename T>
bool loadInstanceProc(VkInstance instance, const char* name, T& outFn) {
    outFn = reinterpret_cast<T>(pfnGetInstanceProcAddr(instance, name));
    if (!outFn) {
        LOGW("Missing Vulkan symbol: %s", name);
        return false;
    }
    return true;
}

} // namespace

VulkanPipeline::~VulkanPipeline() {
    shutdown();
}

bool VulkanPipeline::loadVulkanFunctions() {
    mVulkanLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!mVulkanLib) {
        LOGE("dlopen(libvulkan.so) failed: %s", dlerror());
        return false;
    }

    pfnGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(mVulkanLib, "vkGetInstanceProcAddr")
    );
    if (!pfnGetInstanceProcAddr) {
        LOGE("vkGetInstanceProcAddr not found in libvulkan.so");
        return false;
    }

    pfnCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
        pfnGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance")
    );
    pfnEnumerateInstanceExtensionProperties =
        reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
            pfnGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties")
        );

    return pfnCreateInstance != nullptr && pfnEnumerateInstanceExtensionProperties != nullptr;
}

bool VulkanPipeline::initialize() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mInitialized) return true;

    if (!loadVulkanFunctions()) return false;
    if (!createInstance()) {
        shutdown();
        return false;
    }
    if (!selectPhysicalDevice()) {
        shutdown();
        return false;
    }
    if (!createLogicalDevice()) {
        shutdown();
        return false;
    }
    if (!createCommandResources()) {
        shutdown();
        return false;
    }
    if (!createComputePipeline()) {
        shutdown();
        return false;
    }

    mInitialized = true;
    LOGI("Vulkan initialized: %s (API %s, 10bit=%d, AHB=%d)",
         mGpuName.c_str(), mApiVersion.c_str(),
         mSupports10BitOutput ? 1 : 0, mSupportsAhbImport ? 1 : 0);
    return true;
}

bool VulkanPipeline::createInstance() {
    uint32_t extCount = 0;
    pfnEnumerateInstanceExtensionProperties(nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> available(extCount);
    pfnEnumerateInstanceExtensionProperties(nullptr, &extCount, available.data());

    auto hasExt = [&](const char* name) {
        return std::any_of(available.begin(), available.end(), [&](const VkExtensionProperties& p) {
            return std::strcmp(p.extensionName, name) == 0;
        });
    };

    std::vector<const char*> extensions;
    if (!hasExt(VK_KHR_SURFACE_EXTENSION_NAME) || !hasExt(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
        LOGE("Required Android Vulkan surface extensions missing");
        return false;
    }
    extensions.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
    extensions.push_back(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
    if (hasExt(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME)) {
        extensions.push_back(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
    }
    if (hasExt(VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME)) {
        extensions.push_back(VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME);
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "FrameCut";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "FrameCutVulkan";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();

    if (pfnCreateInstance(&createInfo, nullptr, &mInstance) != VK_SUCCESS) {
        LOGE("vkCreateInstance failed");
        return false;
    }

    bool ok = true;
    ok &= loadInstanceProc(mInstance, "vkDestroyInstance", pfnDestroyInstance);
    ok &= loadInstanceProc(mInstance, "vkEnumeratePhysicalDevices", pfnEnumeratePhysicalDevices);
    ok &= loadInstanceProc(mInstance, "vkGetPhysicalDeviceProperties", pfnGetPhysicalDeviceProperties);
    ok &= loadInstanceProc(mInstance, "vkGetPhysicalDeviceFormatProperties", pfnGetPhysicalDeviceFormatProperties);
    ok &= loadInstanceProc(mInstance, "vkGetPhysicalDeviceMemoryProperties", pfnGetPhysicalDeviceMemoryProperties);
    ok &= loadInstanceProc(mInstance, "vkGetPhysicalDeviceQueueFamilyProperties", pfnGetPhysicalDeviceQueueFamilyProperties);
    ok &= loadInstanceProc(mInstance, "vkEnumerateDeviceExtensionProperties", pfnEnumerateDeviceExtensionProperties);
    ok &= loadInstanceProc(mInstance, "vkCreateDevice", pfnCreateDevice);
    ok &= loadInstanceProc(mInstance, "vkDestroyDevice", pfnDestroyDevice);
    ok &= loadInstanceProc(mInstance, "vkGetDeviceQueue", pfnGetDeviceQueue);
    ok &= loadInstanceProc(mInstance, "vkDeviceWaitIdle", pfnDeviceWaitIdle);

    ok &= loadInstanceProc(mInstance, "vkCreateAndroidSurfaceKHR", pfnCreateAndroidSurfaceKHR);
    ok &= loadInstanceProc(mInstance, "vkDestroySurfaceKHR", pfnDestroySurfaceKHR);
    ok &= loadInstanceProc(mInstance, "vkGetPhysicalDeviceSurfaceSupportKHR", pfnGetPhysicalDeviceSurfaceSupportKHR);
    ok &= loadInstanceProc(mInstance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR", pfnGetPhysicalDeviceSurfaceCapabilitiesKHR);
    ok &= loadInstanceProc(mInstance, "vkGetPhysicalDeviceSurfaceFormatsKHR", pfnGetPhysicalDeviceSurfaceFormatsKHR);
    ok &= loadInstanceProc(mInstance, "vkGetPhysicalDeviceSurfacePresentModesKHR", pfnGetPhysicalDeviceSurfacePresentModesKHR);

    ok &= loadInstanceProc(mInstance, "vkCreateSwapchainKHR", pfnCreateSwapchainKHR);
    ok &= loadInstanceProc(mInstance, "vkDestroySwapchainKHR", pfnDestroySwapchainKHR);
    ok &= loadInstanceProc(mInstance, "vkGetSwapchainImagesKHR", pfnGetSwapchainImagesKHR);
    ok &= loadInstanceProc(mInstance, "vkAcquireNextImageKHR", pfnAcquireNextImageKHR);
    ok &= loadInstanceProc(mInstance, "vkQueuePresentKHR", pfnQueuePresentKHR);

    ok &= loadInstanceProc(mInstance, "vkCreateCommandPool", pfnCreateCommandPool);
    ok &= loadInstanceProc(mInstance, "vkDestroyCommandPool", pfnDestroyCommandPool);
    ok &= loadInstanceProc(mInstance, "vkAllocateCommandBuffers", pfnAllocateCommandBuffers);
    ok &= loadInstanceProc(mInstance, "vkResetCommandBuffer", pfnResetCommandBuffer);
    ok &= loadInstanceProc(mInstance, "vkBeginCommandBuffer", pfnBeginCommandBuffer);
    ok &= loadInstanceProc(mInstance, "vkEndCommandBuffer", pfnEndCommandBuffer);

    ok &= loadInstanceProc(mInstance, "vkCreateFence", pfnCreateFence);
    ok &= loadInstanceProc(mInstance, "vkDestroyFence", pfnDestroyFence);
    ok &= loadInstanceProc(mInstance, "vkWaitForFences", pfnWaitForFences);
    ok &= loadInstanceProc(mInstance, "vkResetFences", pfnResetFences);
    ok &= loadInstanceProc(mInstance, "vkCreateSemaphore", pfnCreateSemaphore);
    ok &= loadInstanceProc(mInstance, "vkDestroySemaphore", pfnDestroySemaphore);

    ok &= loadInstanceProc(mInstance, "vkCreateBuffer", pfnCreateBuffer);
    ok &= loadInstanceProc(mInstance, "vkDestroyBuffer", pfnDestroyBuffer);
    ok &= loadInstanceProc(mInstance, "vkGetBufferMemoryRequirements", pfnGetBufferMemoryRequirements);
    ok &= loadInstanceProc(mInstance, "vkBindBufferMemory", pfnBindBufferMemory);

    ok &= loadInstanceProc(mInstance, "vkCreateImage", pfnCreateImage);
    ok &= loadInstanceProc(mInstance, "vkDestroyImage", pfnDestroyImage);
    ok &= loadInstanceProc(mInstance, "vkGetImageMemoryRequirements", pfnGetImageMemoryRequirements);
    ok &= loadInstanceProc(mInstance, "vkBindImageMemory", pfnBindImageMemory);
    ok &= loadInstanceProc(mInstance, "vkCreateImageView", pfnCreateImageView);
    ok &= loadInstanceProc(mInstance, "vkDestroyImageView", pfnDestroyImageView);

    ok &= loadInstanceProc(mInstance, "vkAllocateMemory", pfnAllocateMemory);
    ok &= loadInstanceProc(mInstance, "vkFreeMemory", pfnFreeMemory);
    ok &= loadInstanceProc(mInstance, "vkMapMemory", pfnMapMemory);
    ok &= loadInstanceProc(mInstance, "vkUnmapMemory", pfnUnmapMemory);

    ok &= loadInstanceProc(mInstance, "vkCreateSampler", pfnCreateSampler);
    ok &= loadInstanceProc(mInstance, "vkDestroySampler", pfnDestroySampler);
    ok &= loadInstanceProc(mInstance, "vkCreateDescriptorSetLayout", pfnCreateDescriptorSetLayout);
    ok &= loadInstanceProc(mInstance, "vkDestroyDescriptorSetLayout", pfnDestroyDescriptorSetLayout);
    ok &= loadInstanceProc(mInstance, "vkCreateDescriptorPool", pfnCreateDescriptorPool);
    ok &= loadInstanceProc(mInstance, "vkDestroyDescriptorPool", pfnDestroyDescriptorPool);
    ok &= loadInstanceProc(mInstance, "vkAllocateDescriptorSets", pfnAllocateDescriptorSets);
    ok &= loadInstanceProc(mInstance, "vkUpdateDescriptorSets", pfnUpdateDescriptorSets);
    ok &= loadInstanceProc(mInstance, "vkCreatePipelineLayout", pfnCreatePipelineLayout);
    ok &= loadInstanceProc(mInstance, "vkDestroyPipelineLayout", pfnDestroyPipelineLayout);
    ok &= loadInstanceProc(mInstance, "vkCreateShaderModule", pfnCreateShaderModule);
    ok &= loadInstanceProc(mInstance, "vkDestroyShaderModule", pfnDestroyShaderModule);
    ok &= loadInstanceProc(mInstance, "vkCreateComputePipelines", pfnCreateComputePipelines);
    ok &= loadInstanceProc(mInstance, "vkDestroyPipeline", pfnDestroyPipeline);

    ok &= loadInstanceProc(mInstance, "vkCmdBindPipeline", pfnCmdBindPipeline);
    ok &= loadInstanceProc(mInstance, "vkCmdBindDescriptorSets", pfnCmdBindDescriptorSets);
    ok &= loadInstanceProc(mInstance, "vkCmdPushConstants", pfnCmdPushConstants);
    ok &= loadInstanceProc(mInstance, "vkCmdDispatch", pfnCmdDispatch);
    ok &= loadInstanceProc(mInstance, "vkCmdPipelineBarrier", pfnCmdPipelineBarrier);
    ok &= loadInstanceProc(mInstance, "vkCmdCopyBufferToImage", pfnCmdCopyBufferToImage);
    ok &= loadInstanceProc(mInstance, "vkCmdBlitImage", pfnCmdBlitImage);
    ok &= loadInstanceProc(mInstance, "vkQueueSubmit", pfnQueueSubmit);

    pfnGetAndroidHardwareBufferPropertiesANDROID =
        reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            pfnGetInstanceProcAddr(mInstance, "vkGetAndroidHardwareBufferPropertiesANDROID")
        );

    return ok;
}

bool VulkanPipeline::selectPhysicalDevice() {
    uint32_t devCount = 0;
    pfnEnumeratePhysicalDevices(mInstance, &devCount, nullptr);
    if (devCount == 0) {
        LOGE("No Vulkan physical devices found");
        return false;
    }

    std::vector<VkPhysicalDevice> devices(devCount);
    pfnEnumeratePhysicalDevices(mInstance, &devCount, devices.data());

    int bestScore = -1;
    VkPhysicalDevice bestDev = VK_NULL_HANDLE;
    uint32_t bestQueueFamily = 0;

    for (VkPhysicalDevice dev : devices) {
        uint32_t qCount = 0;
        pfnGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        pfnGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, qProps.data());

        int chosenQueue = -1;
        for (uint32_t i = 0; i < qCount; ++i) {
            if ((qProps[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
                (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
                chosenQueue = static_cast<int>(i);
                break;
            }
        }
        if (chosenQueue < 0) continue;

        VkPhysicalDeviceProperties props{};
        pfnGetPhysicalDeviceProperties(dev, &props);
        int score = 10;
        if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) score = 100;
        else if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) score = 80;
        else if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) score = 40;

        if (score > bestScore) {
            bestScore = score;
            bestDev = dev;
            bestQueueFamily = static_cast<uint32_t>(chosenQueue);
        }
    }

    if (bestDev == VK_NULL_HANDLE) {
        LOGE("No suitable graphics+compute Vulkan physical device found");
        return false;
    }

    mPhysicalDevice = bestDev;
    mQueueFamilyIndex = bestQueueFamily;

    VkPhysicalDeviceProperties props{};
    pfnGetPhysicalDeviceProperties(mPhysicalDevice, &props);
    mGpuName = props.deviceName;
    std::ostringstream ver;
    ver << VK_VERSION_MAJOR(props.apiVersion) << "." << VK_VERSION_MINOR(props.apiVersion);
    mApiVersion = ver.str();

    // Check R16G16B16A16_SFLOAT compute storage + blit support; fall back to R8G8B8A8_UNORM if missing
    VkFormatProperties fp16{};
    pfnGetPhysicalDeviceFormatProperties(mPhysicalDevice, VK_FORMAT_R16G16B16A16_SFLOAT, &fp16);
    const VkFormatFeatureFlags requiredFeatures =
        VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
    if ((fp16.optimalTilingFeatures & requiredFeatures) == requiredFeatures) {
        mProcessedFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
        mSupports10BitOutput = true;
    } else {
        mProcessedFormat = VK_FORMAT_R8G8B8A8_UNORM;
        mSupports10BitOutput = false;
    }

    return true;
}

bool VulkanPipeline::createLogicalDevice() {
    uint32_t extCount = 0;
    pfnEnumerateDeviceExtensionProperties(mPhysicalDevice, nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> available(extCount);
    pfnEnumerateDeviceExtensionProperties(mPhysicalDevice, nullptr, &extCount, available.data());

    auto hasDevExt = [&](const char* name) {
        return std::any_of(available.begin(), available.end(), [&](const VkExtensionProperties& p) {
            return std::strcmp(p.extensionName, name) == 0;
        });
    };

    std::vector<const char*> enabledExts;
    if (!hasDevExt(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
        LOGE("VK_KHR_swapchain not supported on device");
        return false;
    }
    enabledExts.push_back(VK_KHR_SWAPCHAIN_EXTENSION_NAME);

    if (hasDevExt(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) &&
        hasDevExt(VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME) &&
        pfnGetAndroidHardwareBufferPropertiesANDROID != nullptr) {
        enabledExts.push_back(VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
        enabledExts.push_back(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
        if (hasDevExt(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
            enabledExts.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
        }
        mSupportsAhbImport = true;
    }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = mQueueFamilyIndex;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo devInfo{};
    devInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &queueInfo;
    devInfo.enabledExtensionCount = static_cast<uint32_t>(enabledExts.size());
    devInfo.ppEnabledExtensionNames = enabledExts.data();

    if (pfnCreateDevice(mPhysicalDevice, &devInfo, nullptr, &mDevice) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed");
        return false;
    }

    pfnGetDeviceQueue(mDevice, mQueueFamilyIndex, 0, &mQueue);
    return true;
}

bool VulkanPipeline::createCommandResources() {
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = mQueueFamilyIndex;

    if (pfnCreateCommandPool(mDevice, &poolInfo, nullptr, &mCommandPool) != VK_SUCCESS) {
        return false;
    }

    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = mCommandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;

    if (pfnAllocateCommandBuffers(mDevice, &allocInfo, &mCommandBuffer) != VK_SUCCESS) {
        return false;
    }

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (pfnCreateFence(mDevice, &fenceInfo, nullptr, &mSubmitFence) != VK_SUCCESS) {
        return false;
    }

    return true;
}

bool VulkanPipeline::createComputePipeline() {
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (pfnCreateSampler(mDevice, &samplerInfo, nullptr, &mLinearSampler) != VK_SUCCESS) {
        return false;
    }

    std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo dslInfo{};
    dslInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    dslInfo.pBindings = bindings.data();
    if (pfnCreateDescriptorSetLayout(mDevice, &dslInfo, nullptr, &mDescriptorSetLayout) != VK_SUCCESS) {
        return false;
    }

    std::array<VkDescriptorPoolSize, 2> poolSizes{};
    poolSizes[0] = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2};
    poolSizes[1] = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2};

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 2;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    if (pfnCreateDescriptorPool(mDevice, &poolInfo, nullptr, &mDescriptorPool) != VK_SUCCESS) {
        return false;
    }

    VkDescriptorSetAllocateInfo dsAlloc{};
    dsAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAlloc.descriptorPool = mDescriptorPool;
    dsAlloc.descriptorSetCount = 1;
    dsAlloc.pSetLayouts = &mDescriptorSetLayout;
    if (pfnAllocateDescriptorSets(mDevice, &dsAlloc, &mDescriptorSet) != VK_SUCCESS) {
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(ColorPushConstants);

    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &mDescriptorSetLayout;
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (pfnCreatePipelineLayout(mDevice, &layoutInfo, nullptr, &mPipelineLayout) != VK_SUCCESS) {
        return false;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = sizeof(kColorProcessCompSpv);
    shaderInfo.pCode = kColorProcessCompSpv;
    if (pfnCreateShaderModule(mDevice, &shaderInfo, nullptr, &mShaderModule) != VK_SUCCESS) {
        return false;
    }

    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = mShaderModule;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = mPipelineLayout;

    if (pfnCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &mComputePipeline) != VK_SUCCESS) {
        return false;
    }

    return true;
}

uint32_t VulkanPipeline::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const {
    VkPhysicalDeviceMemoryProperties memProps{};
    pfnGetPhysicalDeviceMemoryProperties(mPhysicalDevice, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((typeFilter & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return UINT32_MAX;
}

bool VulkanPipeline::ensureSourceImage(uint32_t width, uint32_t height) {
    if (mSourceImage != VK_NULL_HANDLE && mSourceWidth == width && mSourceHeight == height) {
        return true;
    }

    if (mSourceView != VK_NULL_HANDLE) {
        pfnDestroyImageView(mDevice, mSourceView, nullptr);
        mSourceView = VK_NULL_HANDLE;
    }
    if (mSourceImage != VK_NULL_HANDLE) {
        pfnDestroyImage(mDevice, mSourceImage, nullptr);
        mSourceImage = VK_NULL_HANDLE;
    }
    if (mSourceMemory != VK_NULL_HANDLE) {
        pfnFreeMemory(mDevice, mSourceMemory, nullptr);
        mSourceMemory = VK_NULL_HANDLE;
    }

    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent = {width, height, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (pfnCreateImage(mDevice, &imgInfo, nullptr, &mSourceImage) != VK_SUCCESS) {
        return false;
    }

    VkMemoryRequirements req{};
    pfnGetImageMemoryRequirements(mDevice, mSourceImage, &req);
    uint32_t memType = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType == UINT32_MAX) return false;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = req.size;
    allocInfo.memoryTypeIndex = memType;
    if (pfnAllocateMemory(mDevice, &allocInfo, nullptr, &mSourceMemory) != VK_SUCCESS) {
        return false;
    }
    pfnBindImageMemory(mDevice, mSourceImage, mSourceMemory, 0);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = mSourceImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (pfnCreateImageView(mDevice, &viewInfo, nullptr, &mSourceView) != VK_SUCCESS) {
        return false;
    }

    mSourceWidth = width;
    mSourceHeight = height;
    mSourceLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    return true;
}

bool VulkanPipeline::ensureProcessedImage(uint32_t width, uint32_t height) {
    if (mProcessedImage != VK_NULL_HANDLE && mProcessedWidth == width && mProcessedHeight == height) {
        return true;
    }

    if (mProcessedView != VK_NULL_HANDLE) {
        pfnDestroyImageView(mDevice, mProcessedView, nullptr);
        mProcessedView = VK_NULL_HANDLE;
    }
    if (mProcessedImage != VK_NULL_HANDLE) {
        pfnDestroyImage(mDevice, mProcessedImage, nullptr);
        mProcessedImage = VK_NULL_HANDLE;
    }
    if (mProcessedMemory != VK_NULL_HANDLE) {
        pfnFreeMemory(mDevice, mProcessedMemory, nullptr);
        mProcessedMemory = VK_NULL_HANDLE;
    }

    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = mProcessedFormat;
    imgInfo.extent = {width, height, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (pfnCreateImage(mDevice, &imgInfo, nullptr, &mProcessedImage) != VK_SUCCESS) {
        return false;
    }

    VkMemoryRequirements req{};
    pfnGetImageMemoryRequirements(mDevice, mProcessedImage, &req);
    uint32_t memType = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType == UINT32_MAX) return false;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = req.size;
    allocInfo.memoryTypeIndex = memType;
    if (pfnAllocateMemory(mDevice, &allocInfo, nullptr, &mProcessedMemory) != VK_SUCCESS) {
        return false;
    }
    pfnBindImageMemory(mDevice, mProcessedImage, mProcessedMemory, 0);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = mProcessedImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = mProcessedFormat;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (pfnCreateImageView(mDevice, &viewInfo, nullptr, &mProcessedView) != VK_SUCCESS) {
        return false;
    }

    mProcessedWidth = width;
    mProcessedHeight = height;
    mProcessedLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    return true;
}

bool VulkanPipeline::ensureStagingBuffer(VkDeviceSize requiredBytes) {
    if (mStagingBuffer != VK_NULL_HANDLE && mStagingCapacity >= requiredBytes) {
        return true;
    }

    if (mStagingBuffer != VK_NULL_HANDLE) {
        pfnDestroyBuffer(mDevice, mStagingBuffer, nullptr);
        mStagingBuffer = VK_NULL_HANDLE;
    }
    if (mStagingMemory != VK_NULL_HANDLE) {
        pfnFreeMemory(mDevice, mStagingMemory, nullptr);
        mStagingMemory = VK_NULL_HANDLE;
    }

    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = requiredBytes;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (pfnCreateBuffer(mDevice, &bufInfo, nullptr, &mStagingBuffer) != VK_SUCCESS) {
        return false;
    }

    VkMemoryRequirements req{};
    pfnGetBufferMemoryRequirements(mDevice, mStagingBuffer, &req);
    uint32_t memType = findMemoryType(
        req.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );
    if (memType == UINT32_MAX) return false;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = req.size;
    allocInfo.memoryTypeIndex = memType;
    if (pfnAllocateMemory(mDevice, &allocInfo, nullptr, &mStagingMemory) != VK_SUCCESS) {
        return false;
    }
    pfnBindBufferMemory(mDevice, mStagingBuffer, mStagingMemory, 0);
    mStagingCapacity = requiredBytes;
    return true;
}

void VulkanPipeline::destroyTargetSwapchain(SwapchainTarget& target, bool releaseWindow) {
    if (mDevice != VK_NULL_HANDLE) {
        pfnDeviceWaitIdle(mDevice);
        if (target.swapchain != VK_NULL_HANDLE) {
            pfnDestroySwapchainKHR(mDevice, target.swapchain, nullptr);
            target.swapchain = VK_NULL_HANDLE;
        }
        if (target.imageAvailableSem != VK_NULL_HANDLE) {
            pfnDestroySemaphore(mDevice, target.imageAvailableSem, nullptr);
            target.imageAvailableSem = VK_NULL_HANDLE;
        }
        if (target.renderFinishedSem != VK_NULL_HANDLE) {
            pfnDestroySemaphore(mDevice, target.renderFinishedSem, nullptr);
            target.renderFinishedSem = VK_NULL_HANDLE;
        }
    }
    if (mInstance != VK_NULL_HANDLE && target.surface != VK_NULL_HANDLE) {
        pfnDestroySurfaceKHR(mInstance, target.surface, nullptr);
        target.surface = VK_NULL_HANDLE;
    }
    target.images.clear();
    target.needsRecreate = false;
    if (releaseWindow && target.window != nullptr) {
        ANativeWindow_release(target.window);
        target.window = nullptr;
    }
}

bool VulkanPipeline::ensureTargetSwapchain(SwapchainTarget& target) {
    if (target.window == nullptr) return false;
    if (target.swapchain != VK_NULL_HANDLE && !target.needsRecreate) {
        return true;
    }

    if (target.surface == VK_NULL_HANDLE) {
        VkAndroidSurfaceCreateInfoKHR surfInfo{};
        surfInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfInfo.window = target.window;
        if (pfnCreateAndroidSurfaceKHR(mInstance, &surfInfo, nullptr, &target.surface) != VK_SUCCESS) {
            LOGE("vkCreateAndroidSurfaceKHR failed");
            return false;
        }
        VkBool32 supported = VK_FALSE;
        pfnGetPhysicalDeviceSurfaceSupportKHR(mPhysicalDevice, mQueueFamilyIndex, target.surface, &supported);
        if (!supported) {
            LOGW("Selected queue does not support presenting to Android surface");
        }
    }

    VkSurfaceCapabilitiesKHR caps{};
    if (pfnGetPhysicalDeviceSurfaceCapabilitiesKHR(mPhysicalDevice, target.surface, &caps) != VK_SUCCESS) {
        return false;
    }

    uint32_t fmtCount = 0;
    pfnGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, target.surface, &fmtCount, nullptr);
    if (fmtCount == 0) return false;
    std::vector<VkSurfaceFormatKHR> formats(fmtCount);
    pfnGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, target.surface, &fmtCount, formats.data());

    VkSurfaceFormatKHR chosen = formats[0];
    if (target.prefer10Bit) {
        for (const auto& f : formats) {
            if (f.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32) {
                chosen = f;
                break;
            }
        }
    }
    if (chosen.format != VK_FORMAT_A2B10G10R10_UNORM_PACK32) {
        for (const auto& f : formats) {
            if (f.format == VK_FORMAT_R8G8B8A8_UNORM) {
                chosen = f;
                break;
            }
        }
    }

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX || extent.width == 0) {
        extent.width = static_cast<uint32_t>(std::max(2, ANativeWindow_getWidth(target.window)));
        extent.height = static_cast<uint32_t>(std::max(2, ANativeWindow_getHeight(target.window)));
    }
    extent.width = std::clamp(extent.width, caps.minImageExtent.width, caps.maxImageExtent.width);
    extent.height = std::clamp(extent.height, caps.minImageExtent.height, caps.maxImageExtent.height);

    uint32_t imgCount = std::max(caps.minImageCount, 3u);
    if (caps.maxImageCount > 0 && imgCount > caps.maxImageCount) {
        imgCount = caps.maxImageCount;
    }

    VkCompositeAlphaFlagBitsKHR compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    const std::array<VkCompositeAlphaFlagBitsKHR, 4> alphaCandidates = {
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR
    };
    for (auto candidate : alphaCandidates) {
        if (caps.supportedCompositeAlpha & candidate) {
            compositeAlpha = candidate;
            break;
        }
    }

    VkSwapchainKHR oldSwapchain = target.swapchain;
    VkSwapchainCreateInfoKHR scInfo{};
    scInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    scInfo.surface = target.surface;
    scInfo.minImageCount = imgCount;
    scInfo.imageFormat = chosen.format;
    scInfo.imageColorSpace = chosen.colorSpace;
    scInfo.imageExtent = extent;
    scInfo.imageArrayLayers = 1;
    scInfo.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                        (caps.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
    scInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    scInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    scInfo.compositeAlpha = compositeAlpha;
    scInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    scInfo.clipped = VK_TRUE;
    scInfo.oldSwapchain = oldSwapchain;

    VkSwapchainKHR newSwapchain = VK_NULL_HANDLE;
    if (pfnCreateSwapchainKHR(mDevice, &scInfo, nullptr, &newSwapchain) != VK_SUCCESS) {
        LOGE("vkCreateSwapchainKHR failed");
        return false;
    }
    if (oldSwapchain != VK_NULL_HANDLE) {
        pfnDestroySwapchainKHR(mDevice, oldSwapchain, nullptr);
    }

    target.swapchain = newSwapchain;
    target.format = chosen.format;
    target.extent = extent;
    target.needsRecreate = false;

    uint32_t actualImages = 0;
    pfnGetSwapchainImagesKHR(mDevice, target.swapchain, &actualImages, nullptr);
    target.images.resize(actualImages);
    pfnGetSwapchainImagesKHR(mDevice, target.swapchain, &actualImages, target.images.data());

    if (target.imageAvailableSem == VK_NULL_HANDLE) {
        VkSemaphoreCreateInfo semInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        pfnCreateSemaphore(mDevice, &semInfo, nullptr, &target.imageAvailableSem);
        pfnCreateSemaphore(mDevice, &semInfo, nullptr, &target.renderFinishedSem);
    }
    return true;
}

void VulkanPipeline::setPreviewWindow(ANativeWindow* window, uint32_t width, uint32_t height, bool prefer10Bit) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mInitialized) {
        if (window) ANativeWindow_release(window);
        return;
    }
    destroyTargetSwapchain(mPreviewTarget, true);
    if (window != nullptr) {
        mPreviewTarget.window = window; // ownership transferred
        mPreviewTarget.extent = {width, height};
        mPreviewTarget.prefer10Bit = prefer10Bit;
        ensureTargetSwapchain(mPreviewTarget);
    }
}

void VulkanPipeline::setEncoderWindow(ANativeWindow* window, uint32_t width, uint32_t height, bool prefer10Bit) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mInitialized) {
        if (window) ANativeWindow_release(window);
        return;
    }
    destroyTargetSwapchain(mEncoderTarget, true);
    if (window != nullptr) {
        mEncoderTarget.window = window; // ownership transferred
        mEncoderTarget.extent = {width, height};
        mEncoderTarget.prefer10Bit = prefer10Bit;
        ensureTargetSwapchain(mEncoderTarget);
    }
}

void VulkanPipeline::recordImageBarrier(
    VkCommandBuffer cmd,
    VkImage image,
    VkImageLayout oldLayout,
    VkImageLayout newLayout,
    VkAccessFlags srcAccess,
    VkAccessFlags dstAccess,
    VkPipelineStageFlags srcStage,
    VkPipelineStageFlags dstStage
) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;

    pfnCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

bool VulkanPipeline::processHardwareBuffer(
    AHardwareBuffer* buffer,
    float u0, float v0, float uw, float vh,
    uint32_t dstWidth, uint32_t dstHeight,
    uint32_t colorMode, uint32_t frameIndex
) {
    if (buffer == nullptr) return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.width == 0 || desc.height == 0) return false;

    void* mapped = nullptr;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &mapped) != 0 ||
        mapped == nullptr) {
        return false;
    }

    const size_t rowBytes = static_cast<size_t>(desc.width) * 4u;
    const size_t totalBytes = rowBytes * static_cast<size_t>(desc.height);

    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mInitialized && ensureSourceImage(desc.width, desc.height) &&
            ensureProcessedImage(dstWidth, dstHeight) && ensureStagingBuffer(totalBytes)) {

            void* gpuPtr = nullptr;
            if (pfnMapMemory(mDevice, mStagingMemory, 0, totalBytes, 0, &gpuPtr) == VK_SUCCESS) {
                const auto* srcBytes = static_cast<const uint8_t*>(mapped);
                auto* dstBytes = static_cast<uint8_t*>(gpuPtr);
                const size_t strideBytes = static_cast<size_t>(desc.stride) * 4u;
                if (strideBytes == rowBytes) {
                    std::memcpy(dstBytes, srcBytes, totalBytes);
                } else {
                    for (uint32_t row = 0; row < desc.height; ++row) {
                        std::memcpy(dstBytes + row * rowBytes, srcBytes + row * strideBytes, rowBytes);
                    }
                }
                pfnUnmapMemory(mDevice, mStagingMemory);

                ColorPushConstants pc{};
                pc.uvRect[0] = u0;
                pc.uvRect[1] = v0;
                pc.uvRect[2] = uw;
                pc.uvRect[3] = vh;
                pc.dstSize[0] = dstWidth;
                pc.dstSize[1] = dstHeight;
                pc.colorMode = colorMode;
                pc.frameIndex = frameIndex;

                ok = dispatchAndPresent(mSourceImage, mSourceView, mSourceLayout, pc, false);
                if (ok) {
                    mSourceLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                }
            }
        }
    }

    AHardwareBuffer_unlock(buffer, nullptr);
    return ok;
}

bool VulkanPipeline::processRgbaBytes(
    const uint8_t* rgbaData,
    size_t dataSize,
    uint32_t srcWidth, uint32_t srcHeight,
    float u0, float v0, float uw, float vh,
    uint32_t dstWidth, uint32_t dstHeight,
    uint32_t colorMode, uint32_t frameIndex
) {
    if (rgbaData == nullptr || srcWidth == 0 || srcHeight == 0) return false;
    const size_t required = static_cast<size_t>(srcWidth) * static_cast<size_t>(srcHeight) * 4u;
    if (dataSize < required) return false;

    std::lock_guard<std::mutex> lock(mMutex);
    if (!mInitialized) return false;
    if (!ensureSourceImage(srcWidth, srcHeight) ||
        !ensureProcessedImage(dstWidth, dstHeight) ||
        !ensureStagingBuffer(required)) {
        return false;
    }

    void* gpuPtr = nullptr;
    if (pfnMapMemory(mDevice, mStagingMemory, 0, required, 0, &gpuPtr) != VK_SUCCESS) {
        return false;
    }
    std::memcpy(gpuPtr, rgbaData, required);
    pfnUnmapMemory(mDevice, mStagingMemory);

    ColorPushConstants pc{};
    pc.uvRect[0] = u0;
    pc.uvRect[1] = v0;
    pc.uvRect[2] = uw;
    pc.uvRect[3] = vh;
    pc.dstSize[0] = dstWidth;
    pc.dstSize[1] = dstHeight;
    pc.colorMode = colorMode;
    pc.frameIndex = frameIndex;

    bool ok = dispatchAndPresent(mSourceImage, mSourceView, mSourceLayout, pc, false);
    if (ok) {
        mSourceLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }
    return ok;
}

bool VulkanPipeline::dispatchAndPresent(
    VkImage sourceImage,
    VkImageView sourceView,
    VkImageLayout currentSourceLayout,
    const ColorPushConstants& pushConstants,
    bool isExternalAhbSource
) {
    // Update descriptors for uSourceTex and uOutputImage
    VkDescriptorImageInfo srcInfo{};
    srcInfo.sampler = mLinearSampler;
    srcInfo.imageView = sourceView;
    srcInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkDescriptorImageInfo dstInfo{};
    dstInfo.imageView = mProcessedView;
    dstInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    std::array<VkWriteDescriptorSet, 2> writes{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = mDescriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &srcInfo;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = mDescriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &dstInfo;

    pfnUpdateDescriptorSets(mDevice, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

    // Prepare swapchain targets
    struct ActiveTarget {
        SwapchainTarget* ptr;
        uint32_t imageIndex;
    };
    std::vector<ActiveTarget> activeTargets;

    auto tryAcquire = [&](SwapchainTarget& target) {
        if (target.window == nullptr) return;
        if (!ensureTargetSwapchain(target)) return;
        uint32_t imgIdx = 0;
        VkResult res = pfnAcquireNextImageKHR(
            mDevice, target.swapchain, 50'000'000ULL, target.imageAvailableSem, VK_NULL_HANDLE, &imgIdx
        );
        if (res == VK_ERROR_OUT_OF_DATE_KHR) {
            target.needsRecreate = true;
            if (ensureTargetSwapchain(target)) {
                res = pfnAcquireNextImageKHR(
                    mDevice, target.swapchain, 50'000'000ULL, target.imageAvailableSem, VK_NULL_HANDLE, &imgIdx
                );
            }
        }
        if (res == VK_SUCCESS || res == VK_SUBOPTIMAL_KHR) {
            if (res == VK_SUBOPTIMAL_KHR) target.needsRecreate = true;
            activeTargets.push_back({&target, imgIdx});
        }
    };

    tryAcquire(mPreviewTarget);
    tryAcquire(mEncoderTarget);

    pfnResetCommandBuffer(mCommandBuffer, 0);
    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (pfnBeginCommandBuffer(mCommandBuffer, &beginInfo) != VK_SUCCESS) {
        return false;
    }

    if (!isExternalAhbSource) {
        recordImageBarrier(
            mCommandBuffer,
            sourceImage,
            currentSourceLayout,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            0,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT
        );

        VkBufferImageCopy copyRegion{};
        copyRegion.bufferOffset = 0;
        copyRegion.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copyRegion.imageExtent = {mSourceWidth, mSourceHeight, 1};
        pfnCmdCopyBufferToImage(
            mCommandBuffer, mStagingBuffer, sourceImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion
        );

        recordImageBarrier(
            mCommandBuffer,
            sourceImage,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        );
    }

    // Transition mProcessedImage -> GENERAL for compute shader write
    recordImageBarrier(
        mCommandBuffer,
        mProcessedImage,
        mProcessedLayout,
        VK_IMAGE_LAYOUT_GENERAL,
        0,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
    );

    pfnCmdBindPipeline(mCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mComputePipeline);
    pfnCmdBindDescriptorSets(
        mCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelineLayout, 0, 1, &mDescriptorSet, 0, nullptr
    );
    pfnCmdPushConstants(
        mCommandBuffer, mPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ColorPushConstants), &pushConstants
    );

    const uint32_t groupsX = (pushConstants.dstSize[0] + 15u) / 16u;
    const uint32_t groupsY = (pushConstants.dstSize[1] + 15u) / 16u;
    pfnCmdDispatch(mCommandBuffer, groupsX, groupsY, 1);

    // Transition mProcessedImage -> TRANSFER_SRC_OPTIMAL for shared blit to Preview + MediaCodec
    recordImageBarrier(
        mCommandBuffer,
        mProcessedImage,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT
    );
    mProcessedLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    std::vector<VkSemaphore> waitSems;
    std::vector<VkPipelineStageFlags> waitStages;
    std::vector<VkSemaphore> signalSems;

    for (auto& act : activeTargets) {
        VkImage scImg = act.ptr->images[act.imageIndex];
        recordImageBarrier(
            mCommandBuffer,
            scImg,
            VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            0,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT
        );

        VkImageBlit blit{};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.srcOffsets[0] = {0, 0, 0};
        blit.srcOffsets[1] = {
            static_cast<int32_t>(pushConstants.dstSize[0]),
            static_cast<int32_t>(pushConstants.dstSize[1]),
            1
        };
        blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.dstOffsets[0] = {0, 0, 0};
        blit.dstOffsets[1] = {
            static_cast<int32_t>(act.ptr->extent.width),
            static_cast<int32_t>(act.ptr->extent.height),
            1
        };
        pfnCmdBlitImage(
            mCommandBuffer,
            mProcessedImage,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            scImg,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &blit,
            VK_FILTER_LINEAR
        );

        recordImageBarrier(
            mCommandBuffer,
            scImg,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            0,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
        );

        waitSems.push_back(act.ptr->imageAvailableSem);
        waitStages.push_back(VK_PIPELINE_STAGE_TRANSFER_BIT);
        signalSems.push_back(act.ptr->renderFinishedSem);
    }

    if (pfnEndCommandBuffer(mCommandBuffer) != VK_SUCCESS) {
        return false;
    }

    pfnResetFences(mDevice, 1, &mSubmitFence);
    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.waitSemaphoreCount = static_cast<uint32_t>(waitSems.size());
    submitInfo.pWaitSemaphores = waitSems.empty() ? nullptr : waitSems.data();
    submitInfo.pWaitDstStageMask = waitStages.empty() ? nullptr : waitStages.data();
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &mCommandBuffer;
    submitInfo.signalSemaphoreCount = static_cast<uint32_t>(signalSems.size());
    submitInfo.pSignalSemaphores = signalSems.empty() ? nullptr : signalSems.data();

    if (pfnQueueSubmit(mQueue, 1, &submitInfo, mSubmitFence) != VK_SUCCESS) {
        LOGE("vkQueueSubmit failed");
        return false;
    }

    pfnWaitForFences(mDevice, 1, &mSubmitFence, VK_TRUE, 1'000'000'000ULL);

    for (auto& act : activeTargets) {
        VkPresentInfoKHR presentInfo{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &act.ptr->renderFinishedSem;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &act.ptr->swapchain;
        presentInfo.pImageIndices = &act.imageIndex;

        VkResult presRes = pfnQueuePresentKHR(mQueue, &presentInfo);
        if (presRes == VK_ERROR_OUT_OF_DATE_KHR || presRes == VK_SUBOPTIMAL_KHR) {
            act.ptr->needsRecreate = true;
        }
    }

    return true;
}

std::string VulkanPipeline::getDeviceInfoString() const {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ostringstream oss;
    oss << mGpuName << "|"
        << mApiVersion << "|"
        << (mSupportsAhbImport ? "1" : "0") << "|"
        << (mSupports10BitOutput ? "1" : "0");
    return oss.str();
}

void VulkanPipeline::shutdown() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mDevice != VK_NULL_HANDLE) {
        pfnDeviceWaitIdle(mDevice);
    }

    destroyTargetSwapchain(mPreviewTarget, true);
    destroyTargetSwapchain(mEncoderTarget, true);

    if (mDevice != VK_NULL_HANDLE) {
        if (mProcessedView != VK_NULL_HANDLE) pfnDestroyImageView(mDevice, mProcessedView, nullptr);
        if (mProcessedImage != VK_NULL_HANDLE) pfnDestroyImage(mDevice, mProcessedImage, nullptr);
        if (mProcessedMemory != VK_NULL_HANDLE) pfnFreeMemory(mDevice, mProcessedMemory, nullptr);

        if (mSourceView != VK_NULL_HANDLE) pfnDestroyImageView(mDevice, mSourceView, nullptr);
        if (mSourceImage != VK_NULL_HANDLE) pfnDestroyImage(mDevice, mSourceImage, nullptr);
        if (mSourceMemory != VK_NULL_HANDLE) pfnFreeMemory(mDevice, mSourceMemory, nullptr);

        if (mStagingBuffer != VK_NULL_HANDLE) pfnDestroyBuffer(mDevice, mStagingBuffer, nullptr);
        if (mStagingMemory != VK_NULL_HANDLE) pfnFreeMemory(mDevice, mStagingMemory, nullptr);

        if (mComputePipeline != VK_NULL_HANDLE) pfnDestroyPipeline(mDevice, mComputePipeline, nullptr);
        if (mShaderModule != VK_NULL_HANDLE) pfnDestroyShaderModule(mDevice, mShaderModule, nullptr);
        if (mPipelineLayout != VK_NULL_HANDLE) pfnDestroyPipelineLayout(mDevice, mPipelineLayout, nullptr);
        if (mDescriptorPool != VK_NULL_HANDLE) pfnDestroyDescriptorPool(mDevice, mDescriptorPool, nullptr);
        if (mDescriptorSetLayout != VK_NULL_HANDLE) pfnDestroyDescriptorSetLayout(mDevice, mDescriptorSetLayout, nullptr);
        if (mLinearSampler != VK_NULL_HANDLE) pfnDestroySampler(mDevice, mLinearSampler, nullptr);

        if (mSubmitFence != VK_NULL_HANDLE) pfnDestroyFence(mDevice, mSubmitFence, nullptr);
        if (mCommandPool != VK_NULL_HANDLE) pfnDestroyCommandPool(mDevice, mCommandPool, nullptr);

        pfnDestroyDevice(mDevice, nullptr);
        mDevice = VK_NULL_HANDLE;
    }

    if (mInstance != VK_NULL_HANDLE) {
        pfnDestroyInstance(mInstance, nullptr);
        mInstance = VK_NULL_HANDLE;
    }

    if (mVulkanLib != nullptr) {
        dlclose(mVulkanLib);
        mVulkanLib = nullptr;
    }

    mProcessedView = VK_NULL_HANDLE;
    mProcessedImage = VK_NULL_HANDLE;
    mProcessedMemory = VK_NULL_HANDLE;
    mSourceView = VK_NULL_HANDLE;
    mSourceImage = VK_NULL_HANDLE;
    mSourceMemory = VK_NULL_HANDLE;
    mStagingBuffer = VK_NULL_HANDLE;
    mStagingMemory = VK_NULL_HANDLE;
    mComputePipeline = VK_NULL_HANDLE;
    mShaderModule = VK_NULL_HANDLE;
    mPipelineLayout = VK_NULL_HANDLE;
    mDescriptorPool = VK_NULL_HANDLE;
    mDescriptorSetLayout = VK_NULL_HANDLE;
    mLinearSampler = VK_NULL_HANDLE;
    mSubmitFence = VK_NULL_HANDLE;
    mCommandPool = VK_NULL_HANDLE;
    mInitialized = false;
}

} // namespace framecut
