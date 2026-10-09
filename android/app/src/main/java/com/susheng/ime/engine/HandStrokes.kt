package com.susheng.ime.engine

import android.content.Context
import android.graphics.PointF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 在线手写：吃的是笔画轨迹，不是画布照片。
 * 每个字和模板都变成 8×8 格子上的 8 方向直方图，再和标准笔顺的模板做相似度。
 * 算法要和 android/scripts/build-hand-strokes.py 保持一致。
 */
internal object HandStrokes {
    private const val GRID = 8
    private const val BOX = 64
    private const val DIRS = 8
    private const val DIM = GRID * GRID * DIRS
    private const val RECORD = 1 + 4 + DIM

    @Volatile private var labels = emptyArray<String>()
    @Volatile private var packed = ByteArray(0)
    @Volatile private var norm2 = IntArray(0)
    @Volatile private var strokeCounts = IntArray(0)
    // 双重检查锁定在锁外读 ready，@Volatile 保证可见性；labels/packed 等在 ready=true 之前写入，通过 volatile 语义安全发布
    @Volatile private var ready = false
    // “一点”类输入的候选回退，两个分支共用，避免两处字面量漂移。
    private val DOT_FALLBACK = listOf("丶", "。", "〇", "一")

    fun ensure(context: Context): Boolean {
        if (ready) return true
        synchronized(this) {
            if (ready) return true
            val chars = context.assets.open("hand-chars.txt").bufferedReader().use { it.readText() }.trim()
            val bytes = context.assets.open("hand-strokes.bin").use { it.readBytes() }
            // 头部（magic 4 字节 + count/dim 各 2 字节）读取前先校验长度，避免截断资源抛 BufferUnderflowException
            if (bytes.size < 8) return false
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buffer.get(magic)
            if (magic.decodeToString() != "HSF1") return false
            val count = buffer.short.toInt() and 0xffff
            val dim = buffer.short.toInt() and 0xffff
            if (count != chars.length || dim != DIM || bytes.size < 8 + count * RECORD) return false
            val texts = Array(count) { chars.substring(it, it + 1) }
            val norms = IntArray(count)
            val counts = IntArray(count)
            val data = ByteArray(count * DIM)
            for (index in 0 until count) {
                counts[index] = buffer.get().toInt() and 0xff
                norms[index] = buffer.int
                buffer.get(data, index * DIM, DIM)
            }
            labels = texts
            norm2 = norms
            strokeCounts = counts
            packed = data
            ready = true
            return true
        }
    }

    fun recognize(
        strokes: List<List<PointF>>,
        boost: Map<String, Float> = emptyMap(),
        limit: Int = 8
    ): List<String> {
        if (!ready || strokes.isEmpty()) return emptyList()
        // 一次性把 volatile 引用捕获到局部，既明确安全发布，又避免热循环里反复做 volatile 读。
        val labelsRef = labels
        val packedRef = packed
        val norm2Ref = norm2
        val strokeCountsRef = strokeCounts
        if (isDot(strokes)) return DOT_FALLBACK.take(limit)
        val user = quantize(histogram(strokes))
        var userNorm = 0
        for (value in user) userNorm += value * value
        if (userNorm == 0) return DOT_FALLBACK.take(limit)
        // 与 build-hand-strokes.py 的 count = sum(1 for stroke in raw if len(stroke) >= 2) 对齐：
        // 模板 strokeCounts 按“点数 >= 2”统计，这里也按点数统计，避免像素长度阈值造成加成分歧。
        val written = strokes.count { it.size >= 2 }
        val keep = max(limit, 8)
        val bestAt = IntArray(keep) { -1 }
        val bestScore = FloatArray(keep)
        val count = labelsRef.size
        for (index in 0 until count) {
            val tmplNorm = norm2Ref[index]
            if (tmplNorm <= 0) continue
            var dot = 0
            val base = index * DIM
            for (dim in 0 until DIM) {
                dot += user[dim] * packedRef[base + dim]
            }
            var score = (dot / sqrt(userNorm.toDouble() * tmplNorm)).toFloat()
            if (written > 0 && strokeCountsRef[index] == written) score += 0.02f
            score += boost[labelsRef[index]] ?: 0f
            var slot = -1
            for (i in 0 until keep) {
                if (bestAt[i] < 0 || score > bestScore[i]) {
                    slot = i
                    break
                }
            }
            if (slot < 0) continue
            for (i in keep - 1 downTo slot + 1) {
                bestAt[i] = bestAt[i - 1]
                bestScore[i] = bestScore[i - 1]
            }
            bestAt[slot] = index
            bestScore[slot] = score
        }
        return bestAt.filter { it >= 0 }.take(limit).map { labelsRef[it] }
    }

    private fun isDot(strokes: List<List<PointF>>): Boolean {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var length = 0f
        for (stroke in strokes) {
            for (point in stroke) {
                minX = min(minX, point.x)
                minY = min(minY, point.y)
                maxX = max(maxX, point.x)
                maxY = max(maxY, point.y)
            }
            for (i in 1 until stroke.size) {
                length += hypot(stroke[i].x - stroke[i - 1].x, stroke[i].y - stroke[i - 1].y)
            }
        }
        if (minX == Float.POSITIVE_INFINITY) return true
        return max(maxX - minX, maxY - minY) < 28f && length < 36f
    }

    private fun quantize(hist: DoubleArray): IntArray {
        val out = IntArray(DIM)
        for (i in 0 until DIM) {
            // roundToInt 即 floor(x + 0.5)，与 build-hand-strokes.py 的 np.floor(vec*127+0.5) 一致（值非负，逢 .5 向正无穷进位）。
            out[i] = (hist[i] * 127.0).roundToInt().coerceIn(-127, 127)
        }
        return out
    }

    /** 和 build-hand-strokes.py 的 feature() 同一套归一化、采样、方向格和边缘模糊。 */
    private fun histogram(strokes: List<List<PointF>>): DoubleArray {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var any = false
        for (stroke in strokes) {
            for (point in stroke) {
                any = true
                minX = min(minX, point.x)
                minY = min(minY, point.y)
                maxX = max(maxX, point.x)
                maxY = max(maxY, point.y)
            }
        }
        val hist = DoubleArray(DIM)
        if (!any) return hist
        val width = max(maxX - minX, 1f)
        val height = max(maxY - minY, 1f)
        val scale = 48.0 / max(width, height)
        val ox = (BOX - width * scale) / 2.0
        val oy = (BOX - height * scale) / 2.0
        for (stroke in strokes) {
            if (stroke.size < 2) continue
            val segs = ArrayList<Seg>(stroke.size)
            var total = 0.0
            for (i in 1 until stroke.size) {
                val x0 = (stroke[i - 1].x - minX) * scale + ox
                val y0 = (stroke[i - 1].y - minY) * scale + oy
                val x1 = (stroke[i].x - minX) * scale + ox
                val y1 = (stroke[i].y - minY) * scale + oy
                val dist = hypot(x1 - x0, y1 - y0)
                if (dist < 1e-4) continue
                segs.add(Seg(x0, y0, x1, y1, dist, total))
                total += dist
            }
            if (total < 1.0 || segs.isEmpty()) continue
            val count = max(2, (total / 2.0).toInt() + 1)
            val samples = Array(count) { DoubleArray(2) }
            var si = 0
            for (i in 0 until count) {
                val target = total * i / (count - 1)
                while (si + 1 < segs.size && segs[si].base + segs[si].dist < target) si++
                val seg = segs[si]
                var t = if (seg.dist == 0.0) 0.0 else (target - seg.base) / seg.dist
                if (t < 0.0) t = 0.0
                if (t > 1.0) t = 1.0
                samples[i][0] = seg.x0 + (seg.x1 - seg.x0) * t
                samples[i][1] = seg.y0 + (seg.y1 - seg.y0) * t
            }
            for (i in 1 until count) {
                val dx = samples[i][0] - samples[i - 1][0]
                val dy = samples[i][1] - samples[i - 1][1]
                val length = hypot(dx, dy)
                if (length < 1e-4) continue
                val ang = atan2(dy, dx)
                var bin = (((ang + PI) / (2.0 * PI)) * 8.0).toInt() % 8
                if (bin < 0) bin += 8
                val mx = (samples[i - 1][0] + samples[i][0]) / 2.0
                val my = (samples[i - 1][1] + samples[i][1]) / 2.0
                val gx = min(7, max(0, mx.toInt() / 8))
                val gy = min(7, max(0, my.toInt() / 8))
                val cell = (gy * GRID + gx) * DIRS
                hist[cell + bin] += length
                hist[cell + (bin + 4) % 8] += length * 0.25
            }
        }
        val blurred = DoubleArray(DIM)
        for (direction in 0 until DIRS) {
            for (y in 0 until GRID) {
                for (x in 0 until GRID) {
                    var acc = 0.0
                    for (ky in -1..1) {
                        val sy = min(GRID - 1, max(0, y + ky))
                        val wy = if (ky == 0) 2.0 else 1.0
                        for (kx in -1..1) {
                            val sx = min(GRID - 1, max(0, x + kx))
                            val wx = if (kx == 0) 2.0 else 1.0
                            acc += hist[(sy * GRID + sx) * DIRS + direction] * wx * wy
                        }
                    }
                    blurred[(y * GRID + x) * DIRS + direction] = acc
                }
            }
        }
        var norm = 0.0
        for (value in blurred) norm += value * value
        norm = sqrt(norm)
        if (norm > 0.0) {
            for (i in blurred.indices) blurred[i] /= norm
        }
        return blurred
    }

    private class Seg(
        val x0: Double,
        val y0: Double,
        val x1: Double,
        val y1: Double,
        val dist: Double,
        val base: Double
    )
}
