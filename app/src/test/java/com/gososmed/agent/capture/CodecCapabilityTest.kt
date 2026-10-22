package com.gososmed.agent.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CodecCapability].
 *
 * These run on the JVM, so the Android-specific `negotiate()` (which touches
 * MediaCodecList) is NOT exercised here — only the pure preference/mime logic.
 * Device negotiation is verified on hardware via logcat (see the plan).
 */
class CodecCapabilityTest {

    @Test
    fun `preference order is av1 then hevc then avc`() {
        assertEquals(
            listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC),
            CodecCapability.preferenceOrder
        )
    }

    @Test
    fun `mime for each kind`() {
        assertEquals("video/av01", CodecCapability.mimeOf(CodecKind.AV1))
        assertEquals("video/hevc", CodecCapability.mimeOf(CodecKind.HEVC))
        assertEquals("video/avc", CodecCapability.mimeOf(CodecKind.AVC))
    }

    @Test
    fun `avc is always the fallback`() {
        assertTrue(CodecCapability.isFallback(CodecKind.AVC))
        assertFalse(CodecCapability.isFallback(CodecKind.HEVC))
        assertFalse(CodecCapability.isFallback(CodecKind.AV1))
    }

    @Test
    fun `viewer codec string maps to media source codec`() {
        // MSE/WebCodecs use different codec identifiers than MediaCodec MIMEs.
        assertEquals("av01", CodecCapability.viewerCodecOf(CodecKind.AV1))
        assertEquals("hvc1", CodecCapability.viewerCodecOf(CodecKind.HEVC))
        assertEquals("avc1", CodecCapability.viewerCodecOf(CodecKind.AVC))
    }
}
