import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
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

    // dto layer will use this later; compileOnly until something actually serializes at runtime
    compileOnly(libs.kotlinx.serialization.json)

    // folia ships no kotlin at runtime, so the stdlib has to ride inside the fat jar
    implementation(libs.kotlin.stdlib)
}

kotlin {
    jvmToolchain(25)
}

// run-paper 3.1.0: registers a runFolia task that boots a folia jar instead of paper
runPaper {
    folia.registerTask()
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    withType<RunServer> {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "description" to project.description,
        )
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    named<ShadowJar>("shadowJar") {
        archiveClassifier = ""

        // kotlin module metadata files collide across stdlib variants; let the
        // transformer see all of them instead of silently dropping duplicates
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        // folia loads all plugins in one classloader space; relocate the stdlib
        // so we can't clash with any other kotlin plugin on the server
        relocate("kotlin", "kz.bejiihiu.servercore.libs.kotlin")
        relocate("kotlinx", "kz.bejiihiu.servercore.libs.kotlinx")
        mergeServiceFiles()
    }
}
