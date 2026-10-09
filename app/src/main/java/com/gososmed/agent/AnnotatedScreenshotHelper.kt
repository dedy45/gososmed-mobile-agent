package com.gososmed.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Menghasilkan screenshot dengan Set-of-Marks numbered bounding boxes (Ember 2048 theme)
 * agar AI Vision dapat membaca elemen interaktif dengan akurasi 100%.
 */
object AnnotatedScreenshotHelper {

    data class MarkedElement(
        val id: Int,
        val className: String,
        val text: String,
        val bounds: Rect,
        val centerX: Int,
        val centerY: Int
    )

    fun annotate(
        bitmap: Bitmap,
        rootNode: AccessibilityNodeInfo?,
        scale: Float = 0.6f,
        quality: Int = 75,
        roi: Rect? = null
    ): Pair<String, JSONArray> {
        val elements = mutableListOf<MarkedElement>()
        if (rootNode != null) {
            collectInteractiveNodes(rootNode, elements, 1, 0)
        }

        // Filter elemen jika ada Region of Interest (ROI)
        val validRoi = if (roi != null && roi.width() > 10 && roi.height() > 10) {
            Rect(
                roi.left.coerceIn(0, bitmap.width - 1),
                roi.top.coerceIn(0, bitmap.height - 1),
                roi.right.coerceIn(1, bitmap.width),
                roi.bottom.coerceIn(1, bitmap.height)
            )
        } else null

        val targetElements = if (validRoi != null) {
            elements.filter { validRoi.contains(it.centerX, it.centerY) || Rect.intersects(it.bounds, validRoi) }
        } else {
            elements
        }

        // Duplikat bitmap agar bisa digambar Canvas
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val boxPaint = Paint().apply {
            color = Color.parseColor("#FF7A2F") // Ember Orange
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        val badgeBgPaint = Paint().apply {
            color = Color.parseColor("#E6130A1B") // Dark Plum Translucent
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val badgeBorderPaint = Paint().apply {
            color = Color.parseColor("#FF7A2F")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.parseColor("#FFF7F2")
            textSize = 22f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val elementsJson = JSONArray()

        for (elem in targetElements) {
            // Gambar kotak sekeliling elemen
            canvas.drawRect(elem.bounds, boxPaint)

            // Gambar kotak nomor badge di pojok kiri atas elemen
            val badgeText = elem.id.toString()
            val textWidth = textPaint.measureText(badgeText)
            val badgeWidth = (textWidth + 14f).coerceAtLeast(26f)
            val badgeHeight = 26f

            val badgeLeft = elem.bounds.left.toFloat().coerceAtLeast(0f)
            val badgeTop = (elem.bounds.top.toFloat() - badgeHeight).coerceAtLeast(0f)
            val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)

            canvas.drawRoundRect(badgeRect, 4f, 4f, badgeBgPaint)
            canvas.drawRoundRect(badgeRect, 4f, 4f, badgeBorderPaint)
            canvas.drawText(badgeText, badgeLeft + 7f, badgeTop + 20f, textPaint)

            // Catat ke JSON (koordinat tetap koordinat fisik layar asli)
            val item = JSONObject().apply {
                put("id", elem.id)
                put("class", elem.className)
                put("text", elem.text)
                put("bounds", JSONArray(listOf(elem.bounds.left, elem.bounds.top, elem.bounds.right, elem.bounds.bottom)))
                put("center", JSONObject().apply {
                    put("x", elem.centerX)
                    put("y", elem.centerY)
                })
            }
            elementsJson.put(item)
        }

        // Potong sub-region (ROI) jika diminta, atau gunakan full bitmap
        val processedBitmap = if (validRoi != null) {
            val cropped = Bitmap.createBitmap(
                mutableBitmap,
                validRoi.left,
                validRoi.top,
                validRoi.width(),
                validRoi.height()
            )
            mutableBitmap.recycle()
            cropped
        } else {
            mutableBitmap
        }

        // Downscale untuk menghemat token LLM Vision jika scale < 1.0f dan bukan ROI kecil
        val finalBitmap = if (validRoi == null && scale in 0.2f..0.99f) {
            val scaledW = (processedBitmap.width * scale).toInt().coerceAtLeast(100)
            val scaledH = (processedBitmap.height * scale).toInt().coerceAtLeast(100)
            val scaled = Bitmap.createScaledBitmap(processedBitmap, scaledW, scaledH, true)
            processedBitmap.recycle()
            scaled
        } else {
            processedBitmap
        }

        // Kompresi JPEG
        val baos = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        finalBitmap.recycle()

        return base64 to elementsJson
    }

    private fun collectInteractiveNodes(
        node: AccessibilityNodeInfo,
        list: MutableList<MarkedElement>,
        nextId: Int,
        depth: Int = 0
    ): Int {
        if (depth > 20 || list.size >= 150) return nextId
        var currentId = nextId
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        var label = if (txt.isNotEmpty()) txt else desc
        if (label.isEmpty()) {
            label = extractDescendantLabel(node, 0)
        }

        val hasSemanticDesc = desc.isNotEmpty() && !desc.equals("null", ignoreCase = true)
        val hasLabel = label.isNotEmpty()
        val isActionableClass = node.className?.contains("Button") == true ||
                node.className?.contains("EditText") == true ||
                (node.className?.contains("ImageView") == true && hasSemanticDesc)

        // Saring kontainer layout raksasa kosongan yang membungkus hampir seluruh layar
        val isHugeEmptyLayout = (node.className?.contains("Layout") == true || node.className?.contains("ViewGroup") == true) &&
                !hasLabel && rect.width() > 1000 && rect.height() > 1800

        val isInteractive = (node.isClickable || isActionableClass || hasSemanticDesc || (hasLabel && node.className?.contains("TextView") == true)) &&
                !isHugeEmptyLayout &&
                rect.width() > 15 && rect.height() > 15

        val isDuplicateBounds = list.any { it.bounds == rect }

        if (isInteractive && !isDuplicateBounds && rect.left >= 0 && rect.top >= 0) {
            val className = node.className?.toString()?.substringAfterLast(".") ?: "View"
            list.add(MarkedElement(
                id = currentId++,
                className = className,
                text = label,
                bounds = rect,
                centerX = rect.centerX(),
                centerY = rect.centerY()
            ))
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                currentId = collectInteractiveNodes(child, list, currentId, depth + 1)
                child.recycle()
            }
        }
        return currentId
    }

    private fun extractDescendantLabel(node: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 8) return ""
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val cTxt = child.text?.toString()?.trim().orEmpty()
                if (cTxt.isNotEmpty()) return cTxt
                val cDesc = child.contentDescription?.toString()?.trim().orEmpty()
                if (cDesc.isNotEmpty()) return cDesc
                val nested = extractDescendantLabel(child, depth + 1)
                if (nested.isNotEmpty()) return nested
            } finally {
                child.recycle()
            }
        }
        return ""
    }
}
