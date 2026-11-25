package kz.bejiihiu.servercore.bench

import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * tiny deterministic microbenchmark harness.
 *
 * why not jmh: the plugin has no benchmark source set, no annotation
 * processor and no need for fork-per-benchmark isolation - the layers measured
 * here are pure kotlin with no static global state beyond their own instances,
 * so one warm jvm is enough. jmh's blackhole, dead-code defeaters and warmup
 * protocol are what we actually use, so they are reimplemented in ~120 lines
 * instead of dragging in a 3 mb annotation-processor pipeline.
 *
 * protocol per benchmark:
 * - calibrate a batch size so one sample is ~2 ms (samples are never
 *   sub-microsecond, so nanoTime granularity and timer overhead stay small)
 * - warm up for a wall-clock window (default 1 s) so c2 has compiled the path
 * - collect samples for a wall-clock window (default 1.5 s), each sample is
 *   one batch, ns/op = batch time / batch size
 * - report p50 and p99 of the sample distribution, plus bytes allocated per
 *   operation from the thread allocation counter
 */
object Harness {

    /** every result passes through here so the jit cannot delete the work */
    @Volatile
    private var sink: Int = 0

    private var filter: String = ""

    private val rows = ArrayList<Row>()
    private var section = ""

    private class Row(
        val section: String,
        val name: String,
        val p50: Double,
        val p99: Double,
        val bytesPerOp: Double,
        val note: String,
    )

    private val threadMx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    fun configure(filter: String) {
        this.filter = filter.trim().lowercase(Locale.ROOT)
    }

    fun section(title: String) {
        section = title
    }

    /** true when the caller should run a bench; keeps the suite filterable by group or name */
    fun wanted(name: String): Boolean {
        if (filter.isEmpty()) return true
        return name.lowercase(Locale.ROOT).contains(filter) || section.lowercase(Locale.ROOT).contains(filter)
    }

    /**
     * times [op] once per invocation. [op] must return something derived from
     * its result - it is hashed into [sink], which is what defeats dead code
     * elimination.
     */
    fun bench(
        name: String,
        note: String = "",
        warmupMs: Long = 1_000,
        measureMs: Long = 1_500,
        op: () -> Any?,
    ) {
        if (!wanted(name)) return

        // calibrate: grow the batch until one sample is worth timing
        var batch = 1
        while (true) {
            val elapsed = timeNanos { repeat(batch) { swallow(op()) } }
            if (elapsed >= TARGET_SAMPLE_NANOS) break
            val grown = if (elapsed <= 0) batch * 8 else (batch * TARGET_SAMPLE_NANOS / elapsed).toInt()
            batch = maxOf(batch + 1, grown)
            if (batch > MAX_BATCH) break
        }

        val warmupEnd = nanoTime() + warmupMs * 1_000_000
        while (nanoTime() < warmupEnd) repeat(batch) { swallow(op()) }

        val samples = ArrayList<Double>(256)
        val allocBefore = allocatedBytes()
        var ops = 0L
        val measureEnd = nanoTime() + measureMs * 1_000_000
        while (true) {
            val elapsed = timeNanos { repeat(batch) { swallow(op()) } }
            samples += elapsed.toDouble() / batch
            ops += batch
            if (nanoTime() >= measureEnd && samples.size >= MIN_SAMPLES) break
            if (samples.size > MAX_SAMPLES) break
        }
        val allocAfter = allocatedBytes()

        samples.sort()
        val p50 = percentile(samples, 0.50)
        val p99 = percentile(samples, 0.99)
        val bytesPerOp = if (allocAfter > allocBefore) (allocAfter - allocBefore).toDouble() / ops else 0.0
        rows += Row(section, name, p50, p99, bytesPerOp, note)
    }

    fun printReport(header: String) {
        println()
        println("## $header")
        println()
        println("| benchmark | p50 ns/op | p99 ns/op | ops/s (p50) | B/op | notes |")
        println("|---|---:|---:|---:|---:|---|")
        var current = ""
        for (row in rows) {
            if (row.section != current) {
                current = row.section
                println("| **$current** | | | | | |")
            }
            println(
                "| ${row.name} | ${fmt(row.p50)} | ${fmt(row.p99)} | ${fmt(1_000_000_000.0 / row.p50)} | " +
                    "${fmt(row.bytesPerOp)} | ${row.note} |",
            )
        }
        println()
        println("sink=$sink")
    }

    /** machine summary, so a table pasted into a doc says where it came from */
    fun environment(): String {
        val runtime = Runtime.getRuntime()
        val os = ManagementFactory.getOperatingSystemMXBean()
        return buildString {
            append("jvm ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")}), ")
            append("${runtime.availableProcessors()} cpus, ")
            append("${os.name} ${os.arch}, ")
            append("heap ${runtime.maxMemory() / (1024 * 1024)} MiB")
        }
    }

    private fun fmt(value: Double): String = when {
        value <= 0.0 -> "0"
        value >= 100_000 -> String.format(Locale.ROOT, "%,.0f", value)
        value >= 100 -> String.format(Locale.ROOT, "%.0f", value)
        value >= 10 -> String.format(Locale.ROOT, "%.1f", value)
        else -> String.format(Locale.ROOT, "%.2f", value)
    }

    private fun percentile(sorted: List<Double>, quantile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * quantile).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun swallow(value: Any?) {
        sink = sink xor (value?.hashCode() ?: 0x5EED)
    }

    private inline fun timeNanos(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }

    private fun nanoTime(): Long = System.nanoTime()

    private fun allocatedBytes(): Long {
        val mx = threadMx ?: return 0
        return try {
            mx.getCurrentThreadAllocatedBytes()
        } catch (_: UnsupportedOperationException) {
            0
        }
    }

    private const val TARGET_SAMPLE_NANOS = 2_000_000L
    private const val MAX_BATCH = 20_000_000
    private const val MIN_SAMPLES = 20
    private const val MAX_SAMPLES = 4_000
}
