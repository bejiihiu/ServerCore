package kz.bejiihiu.servercore.bench

/**
 * entry point of `./gradlew :plugin:benchmark`.
 *
 * runs the suite on one warm jvm, prints a markdown table. pass
 * `-Pbench.filter=<substring>` to run a subset (e.g. `-Pbench.filter=grid`).
 */
fun main() {
    Harness.configure(System.getProperty("bench.filter").orEmpty())

    println("# ServerCore hot-path benchmarks")
    println()
    println("environment: ${Harness.environment()}")
    println()
    println("note: ci-class runner, relative numbers only - do not read absolutes as server capacity")

    HotPathBenchmarks.run()
    ProtocolBenchmarks.run()

    Harness.printReport("results")
}
