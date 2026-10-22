package com.gososmed.agent.stream

import org.json.JSONObject

/**
 * Pure JVM image configuration normalizer for OBSERVE and live streaming.
 * Enforces pure JPEG format, strict bounds, alias resolution, and rejects WebP.
 */
data class NormalizedImageConfig(
    val includeImage: Boolean,
    val format: String = FORMAT_JPEG,
    val quality: Int? = DEFAULT_QUALITY,
    val maxWidth: Int = DEFAULT_MAX_WIDTH,
    val scale: Float? = null
) {
    companion object {
        const val FORMAT_JPEG = "jpeg"
        const val FORMAT_PNG = "png"
        const val DEFAULT_QUALITY = 75
        const val MIN_QUALITY = 30
        const val MAX_QUALITY = 95
        const val DEFAULT_MAX_WIDTH = 480
        const val MIN_MAX_WIDTH = 120
        const val MAX_MAX_WIDTH = 1920

        const val ERR_UNSUPPORTED_FORMAT = "unsupported_image_format"
        const val ERR_INVALID_ARG = "invalid_image_arg"
        const val ERR_CONFLICTING_ARGS = "conflicting_image_args"
        const val ERR_OPTIONS_REQUIRE_INCLUDE = "image_options_require_include_image"

        /**
         * Normalizes image options from JSONObject args.
         * Throws [ImageConfigException] on invalid or forbidden arguments.
         */
        fun normalize(args: JSONObject): NormalizedImageConfig {
            // 1. Resolve include_image vs legacy screenshot alias
            val hasInclude = args.has("include_image")
            val hasScreenshot = args.has("screenshot")
            if (hasInclude && hasScreenshot) {
                val incVal = args.getBoolean("include_image")
                val scrVal = args.getBoolean("screenshot")
                if (incVal != scrVal) {
                    throw ImageConfigException(
                        reasonCode = ERR_CONFLICTING_ARGS,
                        message = "Both 'include_image' ($incVal) and 'screenshot' ($scrVal) provided with conflicting values"
                    )
                }
            }

            val includeImage = when {
                hasInclude -> args.getBoolean("include_image")
                hasScreenshot -> args.getBoolean("screenshot")
                else -> false
            }

            if (!includeImage) {
                if (args.has("image_format") || args.has("image_quality") ||
                    args.has("image_scale") || args.has("max_width") ||
                    args.has("format") || args.has("quality") || args.has("scale")
                ) {
                    throw ImageConfigException(
                        reasonCode = ERR_OPTIONS_REQUIRE_INCLUDE,
                        message = "Image options provided without include_image=true"
                    )
                }
                return NormalizedImageConfig(includeImage = false)
            }

            // 2. Resolve image_format vs legacy format
            if (args.has("image_format") && args.has("format")) {
                val f1 = args.getString("image_format").trim().lowercase()
                val f2 = args.getString("format").trim().lowercase()
                val c1 = if (f1 == "jpg") "jpeg" else f1
                val c2 = if (f2 == "jpg") "jpeg" else f2
                if (c1 != c2) {
                    throw ImageConfigException(
                        reasonCode = ERR_CONFLICTING_ARGS,
                        message = "Both 'image_format' ($f1) and 'format' ($f2) provided with conflicting values"
                    )
                }
            }

            val rawFormat = when {
                args.has("image_format") -> args.getString("image_format")
                args.has("format") -> args.getString("format")
                else -> FORMAT_JPEG
            }.trim().lowercase()

            if (rawFormat == "webp") {
                throw ImageConfigException(
                    reasonCode = ERR_UNSUPPORTED_FORMAT,
                    message = "Format 'webp' is obsolete and forbidden. Use 'jpeg'."
                )
            }

            val format = when (rawFormat) {
                "jpeg", "jpg" -> FORMAT_JPEG
                "png" -> FORMAT_PNG
                else -> throw ImageConfigException(
                    reasonCode = ERR_UNSUPPORTED_FORMAT,
                    message = "Unsupported image format '$rawFormat'. Only 'jpeg' is supported."
                )
            }

            // 3. Resolve quality
            if (args.has("image_quality") && args.has("quality")) {
                val q1 = args.getInt("image_quality")
                val q2 = args.getInt("quality")
                if (q1 != q2) {
                    throw ImageConfigException(
                        reasonCode = ERR_CONFLICTING_ARGS,
                        message = "Both 'image_quality' ($q1) and 'quality' ($q2) provided with conflicting values"
                    )
                }
            }

            val rawQuality = when {
                args.has("image_quality") -> args.getInt("image_quality")
                args.has("quality") -> args.getInt("quality")
                else -> null
            }

            val quality = if (format == FORMAT_PNG) {
                null
            } else {
                if (rawQuality != null) {
                    if (rawQuality < 1 || rawQuality > 100) {
                        throw ImageConfigException(
                            reasonCode = ERR_INVALID_ARG,
                            message = "Image quality must be between 1 and 100, got $rawQuality"
                        )
                    }
                    rawQuality.coerceIn(MIN_QUALITY, MAX_QUALITY)
                } else {
                    DEFAULT_QUALITY
                }
            }

            // 4. Resolve max_width
            val maxWidth = if (args.has("max_width")) {
                val mw = args.getInt("max_width")
                if (mw < MIN_MAX_WIDTH || mw > MAX_MAX_WIDTH) {
                    throw ImageConfigException(
                        reasonCode = ERR_INVALID_ARG,
                        message = "max_width must be between $MIN_MAX_WIDTH and $MAX_MAX_WIDTH, got $mw"
                    )
                }
                mw
            } else {
                DEFAULT_MAX_WIDTH
            }

            // 5. Resolve scale
            if (args.has("image_scale") && args.has("scale")) {
                val s1 = args.getDouble("image_scale").toFloat()
                val s2 = args.getDouble("scale").toFloat()
                if (Math.abs(s1 - s2) > 0.001f) {
                    throw ImageConfigException(
                        reasonCode = ERR_CONFLICTING_ARGS,
                        message = "Both 'image_scale' ($s1) and 'scale' ($s2) provided with conflicting values"
                    )
                }
            }

            val scale = if (args.has("scale") || args.has("image_scale")) {
                val rawScale = if (args.has("image_scale")) {
                    args.getDouble("image_scale").toFloat()
                } else {
                    args.getDouble("scale").toFloat()
                }
                if (rawScale <= 0f || rawScale > 1f) {
                    throw ImageConfigException(
                        reasonCode = ERR_INVALID_ARG,
                        message = "Scale must be between 0.01 and 1.0, got $rawScale"
                    )
                }
                rawScale
            } else {
                null
            }

            return NormalizedImageConfig(
                includeImage = true,
                format = format,
                quality = quality,
                maxWidth = maxWidth,
                scale = scale
            )
        }
    }
}
object ImageConfigNormalizer {
    fun normalize(args: JSONObject): NormalizedImageConfig = NormalizedImageConfig.normalize(args)
}

class ImageConfigException(
    val reasonCode: String,
    message: String
) : IllegalArgumentException(message)
