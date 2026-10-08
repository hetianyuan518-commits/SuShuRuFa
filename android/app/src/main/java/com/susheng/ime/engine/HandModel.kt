package com.susheng.ime.engine

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import kotlin.math.roundToInt

/**
 * 手写单字识别。模型是 Apache-2.0 的 GB2312 一级字网络，输入是白纸黑字归一化后的 64×64。
 */
internal object HandModel {
    private const val SIZE = 64
    private const val CONTENT = 56
    private var ready = false
    private var libraryLoaded = false
    private var charset = ""

    fun ensure(context: Context): Boolean {
        if (ready) return true
        synchronized(this) {
            if (ready) return true
            val dir = File(context.filesDir, "hand-model")
            dir.mkdirs()
            copyAsset(context, "hand-zh.param", File(dir, "hand-zh.param"))
            copyAsset(context, "hand-zh.bin", File(dir, "hand-zh.bin"))
            if (!libraryLoaded) {
                System.loadLibrary("handrec")
                libraryLoaded = true
            }
            ready = nativeLoad(dir.absolutePath)
            if (ready && charset.isEmpty()) {
                charset = context.assets.open("hand-chars.txt").bufferedReader().use { it.readText() }
            }
            return ready
        }
    }

    fun recognize(bitmap: Bitmap, limit: Int = 8): List<String> {
        if (!ready || charset.length < 3755) return emptyList()
        val input = normalize(bitmap)
        if (input.all { it == 0f }) return emptyList()
        val logits = synchronized(this) { nativeLogits(input) } ?: return emptyList()
        val count = minOf(logits.size, charset.length)
        val order = (0 until count).sortedByDescending { logits[it] }.take(limit)
        return order.map { charset[it].toString() }
    }

    private fun copyAsset(context: Context, name: String, dest: File) {
        if (dest.length() > 0L) return
        context.assets.open(name).use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    /** 和训练时一样：抠出笔画，长边缩到 56，居中放到 64 的白底上，再反转成黑底白字。 */
    private fun normalize(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(width * height)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = (color shr 16) and 0xff
            val green = (color shr 8) and 0xff
            val blue = color and 0xff
            val tone = (red * 30 + green * 59 + blue * 11) / 100
            gray[index] = tone
            if (tone < 180) {
                val x = index % width
                val y = index / width
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        val input = FloatArray(SIZE * SIZE)
        if (maxX < minX || maxY < minY) return input
        val cropW = maxX - minX + 1
        val cropH = maxY - minY + 1
        val scale = CONTENT.toFloat() / maxOf(cropW, cropH)
        val newW = maxOf(1, (cropW * scale).roundToInt())
        val newH = maxOf(1, (cropH * scale).roundToInt())
        val offX = (SIZE - newW) / 2
        val offY = (SIZE - newH) / 2
        for (y in 0 until newH) {
            val srcY = (y + 0.5f) * cropH / newH - 0.5f
            val y0 = srcY.toInt().coerceIn(0, cropH - 1)
            val y1 = (y0 + 1).coerceAtMost(cropH - 1)
            val fy = (srcY - y0).coerceIn(0f, 1f)
            val row0 = (minY + y0) * width
            val row1 = (minY + y1) * width
            for (x in 0 until newW) {
                val srcX = (x + 0.5f) * cropW / newW - 0.5f
                val x0 = srcX.toInt().coerceIn(0, cropW - 1)
                val x1 = (x0 + 1).coerceAtMost(cropW - 1)
                val fx = (srcX - x0).coerceIn(0f, 1f)
                val value = gray[row0 + minX + x0] * (1f - fx) * (1f - fy) +
                    gray[row0 + minX + x1] * fx * (1f - fy) +
                    gray[row1 + minX + x0] * (1f - fx) * fy +
                    gray[row1 + minX + x1] * fx * fy
                input[(offY + y) * SIZE + offX + x] = (255f - value) / 255f
            }
        }
        return input
    }

    private external fun nativeLoad(dir: String): Boolean
    private external fun nativeLogits(input: FloatArray): FloatArray?
}
