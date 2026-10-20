package com.lastwave.app.data.autoeq

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Fits a parametric EQ to a desired correction curve, with AutoEQ's default
 * layout: a fixed 105 Hz / Q 0.7 low shelf, eight peaking filters
 * (20 Hz - 10 kHz) and a high shelf (5 - 12 kHz, Q 0.4 - 0.7).
 *
 * Used when the user changes the target, bass or tilt of an AutoEQ profile:
 * the published profile is turned into a curve, the target delta is added,
 * and that curve is refitted. Levenberg-Marquardt on log-frequency /
 * log-Q / gain parameters, greedy initialisation on the largest residuals.
 */
object PeqFitter {
    private const val PEAKS = 8
    private const val MIN_PK_FC = 20.0
    private const val MAX_PK_FC = 10_000.0
    private const val MIN_Q = 0.18248
    private const val MAX_Q = 6.0
    private const val MAX_GAIN = 20.0
    private const val SHELF_FC = 105.0
    private const val SHELF_Q = 0.7
    private const val HS_MIN_FC = 5_000.0
    private const val HS_MAX_FC = 12_000.0
    private const val HS_MIN_Q = 0.4
    private const val HS_MAX_Q = 0.7

    /**
     * @param desiredDb correction to reproduce on [grid] (dB).
     * @return filters plus AutoEQ-style preamp (negative largest boost).
     */
    fun fit(desiredDb: DoubleArray, grid: DoubleArray = PeqMath.LOG_GRID, iterations: Int = 80): PeqSettings {
        require(desiredDb.size == grid.size) { "curve/grid size mismatch" }
        val weights = DoubleArray(grid.size) { i ->
            when {
                grid[i] > 16_000.0 -> 0.0
                grid[i] > MAX_PK_FC -> 0.35
                else -> 1.0
            }
        }
        var params = initialGuess(desiredDb, grid)
        var lambda = 1e-2
        var error = cost(params, desiredDb, grid, weights)
        repeat(iterations) {
            val residual = residuals(params, desiredDb, grid, weights)
            val jacobian = jacobian(params, desiredDb, grid, weights, residual)
            val n = params.size
            val jtj = Array(n) { DoubleArray(n) }
            val jtr = DoubleArray(n)
            for (k in grid.indices) {
                for (a in 0 until n) {
                    val ja = jacobian[k][a]
                    if (ja == 0.0) continue
                    jtr[a] += ja * residual[k]
                    for (b in 0 until n) jtj[a][b] += ja * jacobian[k][b]
                }
            }
            var improved = false
            var attempts = 0
            while (!improved && attempts < 6) {
                val system = Array(n) { a -> DoubleArray(n) { b -> jtj[a][b] + if (a == b) lambda * (jtj[a][a] + 1e-6) else 0.0 } }
                val step = solve(system, DoubleArray(n) { -jtr[it] }) ?: break
                val candidate = clampParams(DoubleArray(n) { params[it] + step[it] })
                val candidateError = cost(candidate, desiredDb, grid, weights)
                if (candidateError < error) {
                    params = candidate
                    error = candidateError
                    lambda = (lambda * 0.4).coerceAtLeast(1e-7)
                    improved = true
                } else {
                    lambda = (lambda * 4.0).coerceAtMost(1e6)
                }
                attempts++
            }
            if (!improved) return@repeat
        }
        val filters = toFilters(params)
        return PeqSettings(preampDb = PeqMath.autoPreampDb(filters, grid), filters = filters)
    }

    /** Root-mean-square error (dB, weighted) between a settings response and a curve. */
    fun rmsError(settings: PeqSettings, desiredDb: DoubleArray, grid: DoubleArray = PeqMath.LOG_GRID): Double {
        var sum = 0.0
        var count = 0
        for (i in grid.indices) {
            if (grid[i] > MAX_PK_FC) continue
            val d = PeqMath.filtersDb(settings.filters, grid[i]) - desiredDb[i]
            sum += d * d
            count++
        }
        return sqrt(sum / count.coerceAtLeast(1))
    }

    // Parameter layout: [0] low-shelf gain, [1..3] high shelf (ln fc, ln q, gain),
    // then per peak (ln fc, ln q, gain).
    private fun toFilters(p: DoubleArray): List<PeqFilter> = buildList {
        add(PeqFilter(PeqFilterType.LOW_SHELF, SHELF_FC, round1(p[0]), SHELF_Q))
        for (i in 0 until PEAKS) {
            val base = 4 + i * 3
            add(
                PeqFilter(
                    PeqFilterType.PEAKING,
                    roundFc(exp(p[base])),
                    round1(p[base + 2]),
                    round2(exp(p[base + 1])),
                ),
            )
        }
        add(PeqFilter(PeqFilterType.HIGH_SHELF, roundFc(exp(p[1])), round1(p[3]), round2(exp(p[2]))))
    }

    private fun responseAt(p: DoubleArray, f: Double): Double {
        var total = PeqMath.filterDb(PeqFilter(PeqFilterType.LOW_SHELF, SHELF_FC, p[0], SHELF_Q), f)
        total += PeqMath.filterDb(PeqFilter(PeqFilterType.HIGH_SHELF, exp(p[1]), p[3], exp(p[2])), f)
        for (i in 0 until PEAKS) {
            val base = 4 + i * 3
            total += PeqMath.filterDb(PeqFilter(PeqFilterType.PEAKING, exp(p[base]), p[base + 2], exp(p[base + 1])), f)
        }
        return total
    }

    private fun residuals(p: DoubleArray, desired: DoubleArray, grid: DoubleArray, w: DoubleArray): DoubleArray =
        DoubleArray(grid.size) { i -> if (w[i] == 0.0) 0.0 else (responseAt(p, grid[i]) - desired[i]) * w[i] }

    private fun cost(p: DoubleArray, desired: DoubleArray, grid: DoubleArray, w: DoubleArray): Double =
        residuals(p, desired, grid, w).sumOf { it * it }

    private fun jacobian(
        p: DoubleArray,
        desired: DoubleArray,
        grid: DoubleArray,
        w: DoubleArray,
        base: DoubleArray,
    ): Array<DoubleArray> {
        val n = p.size
        val j = Array(grid.size) { DoubleArray(n) }
        for (a in 0 until n) {
            val h = if (isGain(a)) 1e-3 else 1e-4
            val shifted = p.copyOf()
            shifted[a] += h
            val r = residuals(shifted, desired, grid, w)
            for (k in grid.indices) j[k][a] = (r[k] - base[k]) / h
        }
        return j
    }

    private fun isGain(index: Int): Boolean = index == 0 || index == 3 || (index >= 4 && (index - 4) % 3 == 2)

    private fun clampParams(p: DoubleArray): DoubleArray {
        val out = p.copyOf()
        out[0] = out[0].coerceIn(-MAX_GAIN, MAX_GAIN)
        out[1] = out[1].coerceIn(ln(HS_MIN_FC), ln(HS_MAX_FC))
        out[2] = out[2].coerceIn(ln(HS_MIN_Q), ln(HS_MAX_Q))
        out[3] = out[3].coerceIn(-MAX_GAIN, MAX_GAIN)
        for (i in 0 until PEAKS) {
            val b = 4 + i * 3
            out[b] = out[b].coerceIn(ln(MIN_PK_FC), ln(MAX_PK_FC))
            out[b + 1] = out[b + 1].coerceIn(ln(MIN_Q), ln(MAX_Q))
            out[b + 2] = out[b + 2].coerceIn(-MAX_GAIN, MAX_GAIN)
        }
        return out
    }

    private fun initialGuess(desired: DoubleArray, grid: DoubleArray): DoubleArray {
        val p = DoubleArray(4 + PEAKS * 3)
        val lowIdx = grid.indices.filter { grid[it] <= 60.0 }
        p[0] = (lowIdx.map { desired[it] }.average().takeIf { it.isFinite() } ?: 0.0).coerceIn(-12.0, 12.0)
        p[1] = ln(10_000.0)
        p[2] = ln(0.7)
        val highIdx = grid.indices.filter { grid[it] in 11_000.0..16_000.0 }
        p[3] = (highIdx.map { desired[it] }.average().takeIf { it.isFinite() } ?: 0.0).coerceIn(-12.0, 12.0)
        val current = DoubleArray(grid.size) { i -> responseAt(p, grid[i]) }
        for (i in 0 until PEAKS) {
            var bestIndex = -1
            var bestValue = 0.0
            for (k in grid.indices) {
                if (grid[k] < MIN_PK_FC || grid[k] > MAX_PK_FC) continue
                val r = desired[k] - current[k]
                if (abs(r) > abs(bestValue)) {
                    bestValue = r
                    bestIndex = k
                }
            }
            val b = 4 + i * 3
            if (bestIndex < 0 || abs(bestValue) < 0.05) {
                p[b] = ln(1000.0 * (i + 1))
                p[b + 1] = ln(1.0)
                p[b + 2] = 0.0
                continue
            }
            p[b] = ln(grid[bestIndex])
            p[b + 1] = ln(1.4)
            p[b + 2] = bestValue.coerceIn(-MAX_GAIN, MAX_GAIN)
            val added = PeqFilter(PeqFilterType.PEAKING, grid[bestIndex], p[b + 2], 1.4)
            for (k in grid.indices) current[k] += PeqMath.filterDb(added, grid[k])
        }
        return clampParams(p)
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val v = b.copyOf()
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            if (abs(m[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val t = m[pivot]; m[pivot] = m[col]; m[col] = t
                val tv = v[pivot]; v[pivot] = v[col]; v[col] = tv
            }
            for (row in col + 1 until n) {
                val factor = m[row][col] / m[col][col]
                if (factor == 0.0) continue
                for (k in col until n) m[row][k] -= factor * m[col][k]
                v[row] -= factor * v[col]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = v[row]
            for (k in row + 1 until n) sum -= m[row][k] * x[k]
            x[row] = sum / m[row][row]
        }
        return x
    }

    private fun round1(v: Double) = PeqMath.roundTo(v, 0.1)
    private fun round2(v: Double) = PeqMath.roundTo(v, 0.01)
    private fun roundFc(v: Double) = PeqMath.roundTo(v, 1.0)
}
