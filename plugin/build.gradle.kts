import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/") // packetevents
    maven("https://repo.helpch.at/releases/") // placeholderapi
}

// microbenchmarks for the packet/esp/sync/bars/ws hot paths. they compile
// against the plugin sources but never ship in the jar and never run as part
// of build. run them with `./gradlew :plugin:benchmark`
sourceSets {
    create("benchmark") {
        compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
        runtimeClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
    }

    // the example presets live in the repo root; put them on the test classpath
    // so the parity test reads the shipped files instead of a copy
    test {
        resources.srcDir(rootProject.file("examples/plugin"))
    }
}

configurations["benchmarkImplementation"].extendsFrom(configurations["implementation"])
configurations["benchmarkRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

dependencies {
    compileOnly(libs.folia.api)

    // these are server-provided plugins, not ours to bundle
    compileOnly(libs.packetevents.spigot)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.placeholderapi)
    // flectonepulse ships its own impl on the server, only its api goes on our classpath
    compileOnly(libs.flectonepulse.api)

    // compileonly until something actually dispatches on it; bump to implementation
    // when used so shadow bundles it (the server has no coroutines at runtime)
    compileOnly(libs.kotlinx.coroutines.core)

    // the snapshot + wire dto + locale layer serializes at runtime, so it gets bundled
    implementation(libs.kotlinx.serialization.json)

    // folia ships no kotlin at runtime, so the stdlib has to ride inside the fat jar
    implementation(libs.kotlin.stdlib)

    // cross-implementation protocol tests (test source set only)
    testImplementation(libs.kotlin.test)

    // tests need the adventure + snakeyaml transitives on the classpath;
    // pure-logic tests also touch bukkit enums (GameMode), the api jar is enough
    testImplementation(libs.folia.api)
    // the fp api surface is exercised directly in tests via its real interfaces
    testImplementation(libs.flectonepulse.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // the benchmark runner boots the same pure layers without a server; folia
    // api brings adventure + snakeyaml the locale and presentation benches need
    "benchmarkImplementation"(libs.folia.api)
    "benchmarkImplementation"(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(25)
}

// run-paper registers a runFolia task that boots a folia jar instead of paper,
// pinned through the catalog so the wrapper and the plugin move together
runPaper {
    folia.registerTask()
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    // the shaded jar takes the plain name (classifier "") so releases ship a
    // single, predictable plugin-<version>.jar. the thin jar would otherwise
    // land on the same path and whichever task wrote last would win - a thin
    // jar in a release is an unloadable plugin (no kotlin inside)
    jar {
        enabled = false
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("failed", "passed")
        }
    }

    // a benchmark that no longer compiles is a stale doc; keep it building
    // without ever running it as part of check
    check {
        dependsOn(named("compileBenchmarkKotlin"))
    }

    withType<RunServer> {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        // yaml single-quoted scalars escape an apostrophe by doubling it, so a
        // free-text description can never break plugin metadata again
        val props = mapOf(
            "version" to project.version,
            "description" to project.description.orEmpty().replace("'", "''"),
        )
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    named<ShadowJar>("shadowJar") {
        archiveClassifier = ""

        // INCLUDE, not EXCLUDE: the KotlinModuleMetadataTransformer has to see
        // every kotlin_module file (they collide across stdlib variants) or it
        // silently drops one, and shadow warns about exactly that. nothing in
        // today's dependency set actually collides, and the flag below turns a
        // future collision into a failed build instead of a zip with two entries
        // for the same path and an undefined winner
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        failOnDuplicateEntries.set(true)

        // the bundled jars are multi-release (module-info under META-INF/versions),
        // but those descriptors are useless to a bukkit plugin: the server never
        // loads us as a named module. shadow drops the versioned entries, and this
        // switch keeps it from also advertising Multi-Release in the manifest —
        // otherwise the jar claims versioned classes it does not carry
        addMultiReleaseAttribute.set(false)
        exclude("META-INF/versions/**", "module-info.class")

        // folia loads all plugins in one classloader space; relocate the stdlib
        // so we can't clash with any other kotlin plugin on the server
        relocate("kotlin", "kz.bejiihiu.servercore.libs.kotlin")
        relocate("kotlinx", "kz.bejiihiu.servercore.libs.kotlinx")
        mergeServiceFiles()

        manifest {
            attributes(
                "Implementation-Title" to "ServerCore",
                "Implementation-Version" to project.version.toString(),
                "Implementation-Vendor" to "bejiihiu",
                "Implementation-Vendor-Id" to project.group.toString(),
            )
        }
    }

    // same inputs -> same bytes, so a rebuild can be checked against the
    // published SHA256SUMS entry instead of eyeballed against another jar.
    // gradle 9 already archives reproducibly by default; pinning it here keeps
    // a future default flip or a -Dorg.gradle.archives.* override from quietly
    // making release hashes machine-dependent
    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

// `./gradlew :plugin:benchmark` runs the deterministic hot-path suite. it is a
// plain javaexec on purpose (no jmh fork/annotation processor): warmup plus a
// fixed wall-clock measurement window, one process, results printed as markdown
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Runs the hot-path microbenchmarks (no server needed)"
    dependsOn(tasks.named("classes"))
    mainClass = "kz.bejiihiu.servercore.bench.BenchmarkMainKt"
    classpath = sourceSets["benchmark"].runtimeClasspath
    jvmArgs("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch")
    // let the suite pick a narrower set: -Pbench.filter=grid
    systemProperty("bench.filter", providers.gradleProperty("bench.filter").getOrElse(""))
}
