package com.pocketnode.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendInfoTest {
    @Test
    fun normalizeUsesNativeBackendNameWhenPresent() {
        assertEquals("OpenCL", BackendInfo.normalize(" OpenCL "))
        assertEquals("CPU", BackendInfo.normalize(null))
        assertEquals("CPU", BackendInfo.normalize("   "))
    }

    @Test
    fun vulkanDetectionIsStringBased() {
        assertTrue(BackendInfo.isVulkanFamily("Vulkan,CPU"))
        assertFalse(BackendInfo.isVulkanFamily("OpenCL"))
    }
}
