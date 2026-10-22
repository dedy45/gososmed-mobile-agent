package com.gososmed.agent.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EncoderPipeline]'s fallback chain.
 *
 * `selectWithFallback` is pure Kotlin (the codec attempt is injected), so it
 * is fully testable on the JVM without MediaCodec.
 */
class EncoderPipelineTest {

    @Test
    fun `falls back to next codec when configure throws`() {
        val attempts = mutableListOf<CodecKind>()

        val selected = EncoderPipeline.selectWithFallback(
            order = listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC),
            tryConfigure = { kind ->
                attempts += kind
                if (kind != CodecKind.AVC) throw IllegalStateException("unsupported: $kind")
            }
        )

        assertEquals(CodecKind.AVC, selected)
        assertEquals(listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC), attempts)
    }

    @Test
    fun `stops at the first codec that configures successfully`() {
        val attempts = mutableListOf<CodecKind>()

        val selected = EncoderPipeline.selectWithFallback(
            order = listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC),
            tryConfigure = { kind -> attempts += kind }
        )

        assertEquals(CodecKind.AV1, selected)
        assertEquals(listOf(CodecKind.AV1), attempts)
    }

    @Test
    fun `returns AVC even when every attempt fails`() {
        val selected = EncoderPipeline.selectWithFallback(
            order = listOf(CodecKind.AV1, CodecKind.HEVC),
            tryConfigure = { throw IllegalStateException("nope") }
        )
        // AVC is not in the order list but is still the guaranteed answer.
        assertEquals(CodecKind.AVC, selected)
    }

    @Test
    fun `reports which codecs were attempted`() {
        val result = EncoderPipeline.attemptWithReport(
            order = listOf(CodecKind.AV1, CodecKind.HEVC, CodecKind.AVC),
            tryConfigure = { kind ->
                if (kind == CodecKind.AV1) throw IllegalStateException("no av1")
            }
        )
        assertEquals(CodecKind.HEVC, result.selected)
        assertEquals(listOf(CodecKind.AV1, CodecKind.HEVC), result.attempted)
        assertTrue(result.failures.isNotEmpty())
    }
}
