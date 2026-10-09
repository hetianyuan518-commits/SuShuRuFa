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
    // 模型输出类别数，与 hand-chars.txt 的字符数一致；recognize 用它校验字符集是否完整加载。
    private const val CLASS_COUNT = 3755
    // recognize() 在锁外读取 ready/charset，@Volatile 保证跨线程可见性与安全发布
    @Volatile private var ready = false
    private var libraryLoaded = false
    @Volatile private var charset = ""

    fun ensure(context: Context): Boolean {
        if (ready) return true
        synchronized(this) {
            if (ready) return true
            // 资产读取 / loadLibrary / nativeLoad / 解析都可能抛异常（缺文件、UnsatisfiedLinkError、IOException）。
            // 全部兜住并返回 false，让调用方能区分“模型不可用”与崩溃。
            val ok = try {
                val dir = File(context.filesDir, "hand-model")
                if (!dir.isDirectory && !dir.mkdirs()) {
                    false
                } else {
                    copyAsset(context, "hand-zh.param", File(dir, "hand-zh.param"))
                    copyAsset(context, "hand-zh.bin", File(dir, "hand-zh.bin"))
                    if (!libraryLoaded) {
                        System.loadLibrary("handrec")
                        libraryLoaded = true
                    }
                    // 先填充 charset 再发布 ready：避免其他线程看到 ready=true 但 charset 仍为空，
                    // 导致 recognize() 因 charset.length < CLASS_COUNT 静默返回空结果
                    if (charset.isEmpty()) {
                        charset = context.assets.open("hand-chars.txt").bufferedReader().use { it.readText() }
                    }
                    nativeLoad(dir.absolutePath)
                }
            } catch (e: Throwable) {
                false
            }
            ready = ok
            return ok
        }
    }

    fun recognize(bitmap: Bitmap, limit: Int = 8): List<String> {
        val chars = charset
        if (!ready || chars.length < CLASS_COUNT) return emptyList()
        // getPixels 对已回收的 bitmap 会抛 IllegalStateException；识别跑在后台线程，提前挡掉。
        if (bitmap.isRecycled) return emptyList()
        val input = normalize(bitmap)
        if (input.all { it == 0f }) return emptyList()
        val logits = synchronized(this) { nativeLogits(input) } ?: return emptyList()
        // native 返回的 logits 数必须与字符集大小一致；不一致说明模型/字符集不匹配，
        // 截断后索引仍会映射到真实字符，产生看似合理实则错误的结果，因此直接放弃。
        if (logits.size != chars.length) return emptyList()
        return topKIndices(logits, limit).map { chars[it].toString() }
    }

    /** 有界 top-k：只维护 limit 个槽的降序数组，避免对全部 ~CLASS_COUNT 个候选装箱并整表排序。 */
    private fun topKIndices(logits: FloatArray, limit: Int): List<Int> {
        val k = minOf(limit, logits.size)
        if (k <= 0) return emptyList()
        val idx = IntArray(k)
        val vals = FloatArray(k)
        var size = 0
        for (i in logits.indices) {
            val v = logits[i]
            if (size < k) {
                var j = size
                while (j > 0 && vals[j - 1] < v) { vals[j] = vals[j - 1]; idx[j] = idx[j - 1]; j-- }
                vals[j] = v; idx[j] = i; size++
            } else if (v > vals[k - 1]) {
                var j = k - 1
                while (j > 0 && vals[j - 1] < v) { vals[j] = vals[j - 1]; idx[j] = idx[j - 1]; j-- }
                vals[j] = v; idx[j] = i
            }
        }
        val out = ArrayList<Int>(size)
        for (i in 0 until size) out.add(idx[i])
        return out
    }

    private fun copyAsset(context: Context, name: String, dest: File) {
        if (dest.length() > 0L) return
        // 先写临时文件再原子重命名：中断的拷贝不会留下“非空但损坏”的模型被 length()>0 误判为已就绪。
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw java.io.IOException("rename failed: ${tmp.absolutePath} -> ${dest.absolutePath}")
        }
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
