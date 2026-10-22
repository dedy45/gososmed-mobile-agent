package com.gososmed.agent.stream

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageConfigNormalizerTest {

    @Test
    fun testDefaultWhenIncludeImageFalse() {
        val json = JSONObject()
        val config = NormalizedImageConfig.normalize(json)
        assertFalse(config.includeImage)
    }

    @Test
    fun testDefaultWhenIncludeImageTrue() {
        val json = JSONObject().apply {
            put("include_image", true)
        }
        val config = NormalizedImageConfig.normalize(json)
        assertTrue(config.includeImage)
        assertEquals(NormalizedImageConfig.FORMAT_JPEG, config.format)
        assertEquals(75, config.quality)
        assertEquals(480, config.maxWidth)
        assertNull(config.scale)
    }

    @Test
    fun testLegacyScreenshotAlias() {
        val json = JSONObject().apply {
            put("screenshot", true)
            put("format", "jpg")
            put("quality", 80)
        }
        val config = NormalizedImageConfig.normalize(json)
        assertTrue(config.includeImage)
        assertEquals(NormalizedImageConfig.FORMAT_JPEG, config.format)
        assertEquals(80, config.quality)
    }

    @Test
    fun testWebPIsStrictlyForbidden() {
        val json = JSONObject().apply {
            put("include_image", true)
            put("image_format", "webp")
        }
        try {
            NormalizedImageConfig.normalize(json)
            fail("Expected ImageConfigException for webp")
        } catch (e: ImageConfigException) {
            assertEquals(NormalizedImageConfig.ERR_UNSUPPORTED_FORMAT, e.reasonCode)
        }
    }

    @Test
    fun testUnsupportedFormat() {
        val json = JSONObject().apply {
            put("include_image", true)
            put("image_format", "gif")
        }
        try {
            NormalizedImageConfig.normalize(json)
            fail("Expected ImageConfigException for gif")
        } catch (e: ImageConfigException) {
            assertEquals(NormalizedImageConfig.ERR_UNSUPPORTED_FORMAT, e.reasonCode)
        }
    }

    @Test
    fun testPngHasNullQuality() {
        val json = JSONObject().apply {
            put("include_image", true)
            put("image_format", "png")
            put("image_quality", 90)
        }
        val config = NormalizedImageConfig.normalize(json)
        assertEquals(NormalizedImageConfig.FORMAT_PNG, config.format)
        assertNull(config.quality)
    }

    @Test
    fun testQualityClampingAndBounds() {
        val jsonLow = JSONObject().apply {
            put("include_image", true)
            put("image_quality", 10)
        }
        val configLow = NormalizedImageConfig.normalize(jsonLow)
        assertEquals(30, configLow.quality) // Clamped to MIN_QUALITY (30)

        val jsonHigh = JSONObject().apply {
            put("include_image", true)
            put("image_quality", 99)
        }
        val configHigh = NormalizedImageConfig.normalize(jsonHigh)
        assertEquals(95, configHigh.quality) // Clamped to MAX_QUALITY (95)

        val jsonInvalid = JSONObject().apply {
            put("include_image", true)
            put("image_quality", 101)
        }
        try {
            NormalizedImageConfig.normalize(jsonInvalid)
            fail("Expected exception for quality 101")
        } catch (e: ImageConfigException) {
            assertEquals(NormalizedImageConfig.ERR_INVALID_ARG, e.reasonCode)
        }
    }

    @Test
    fun testMaxWidthBounds() {
        val jsonLow = JSONObject().apply {
            put("include_image", true)
            put("max_width", 50)
        }
        try {
            NormalizedImageConfig.normalize(jsonLow)
            fail("Expected exception for max_width 50")
        } catch (e: ImageConfigException) {
            assertEquals(NormalizedImageConfig.ERR_INVALID_ARG, e.reasonCode)
        }

        val jsonValid = JSONObject().apply {
            put("include_image", true)
            put("max_width", 720)
        }
        val config = NormalizedImageConfig.normalize(jsonValid)
        assertEquals(720, config.maxWidth)
    }

    @Test
    fun testScaleBounds() {
        val jsonInvalid = JSONObject().apply {
            put("include_image", true)
            put("image_scale", 1.5)
        }
        try {
            NormalizedImageConfig.normalize(jsonInvalid)
            fail("Expected exception for scale 1.5")
        } catch (e: ImageConfigException) {
            assertEquals(NormalizedImageConfig.ERR_INVALID_ARG, e.reasonCode)
        }

        val jsonValid = JSONObject().apply {
            put("include_image", true)
            put("image_scale", 0.5)
        }
        val config = NormalizedImageConfig.normalize(jsonValid)
        assertEquals(0.5f, config.scale ?: 0f, 0.001f)
    }

    @Test
    fun testConflictingArguments() {
        val jsonConflictingInclude = JSONObject().apply {
            put("include_image", true)
            put("screenshot", false)
        }
        try {
            NormalizedImageConfig.normalize(jsonConflictingInclude)
            fail("Expected exception for conflicting include/screenshot")
        } catch (e: ImageConfigException) {
            assertEquals(NormalizedImageConfig.ERR_CONFLICTING_ARGS, e.reasonCode)
        }

        val jsonConflictingFormat = JSONObject().apply {
            put("include_image", true)
            put("image_format", "png")
            put("format", "jpeg")
        }
        try {
            NormalizedImageConfig.normalize(jsonConflictingFormat)
            fail("Expected exception for conflicting format")
        } catch (e: ImageConfigException) {
            assertEquals(NormalizedImageConfig.ERR_CONFLICTING_ARGS, e.reasonCode)
        }

        val jsonConflictingQuality = JSONObject().apply {
            put("include_image", true)
            put("image_quality", 50)
            put("quality", 80)
        }
        try {
            NormalizedImageConfig.normalize(jsonConflictingQuality)
            fail("Expected exception for conflicting quality")
        } catch (e: ImageConfigException) {
            assertEquals(NormalizedImageConfig.ERR_CONFLICTING_ARGS, e.reasonCode)
        }
    }

    @Test
    fun testImageOptionsWithoutIncludeImageThrows() {
        val json = JSONObject().apply {
            put("image_quality", 80)
        }
        try {
            NormalizedImageConfig.normalize(json)
            fail("Expected exception for image option without include_image")
        } catch (e: ImageConfigException) {
            assertEquals(NormalizedImageConfig.ERR_OPTIONS_REQUIRE_INCLUDE, e.reasonCode)
        }
    }
}
