import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask
import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar

plugins {
    id("minecraft-setup")
    id("publish-setup")
}

val analyzerRuntime = configurations.create("analyzerRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

impactMod {
    modId.set("crashtracer")
    modName.set("Crash Tracer")
    modGroup.set("space.impact.crashtracer")
    mavenGroup.set("space.impact")
    mavenUrl.set("https://maven.accident.space/repository/maven-releases/")
    injectGenerics.set(true)
    usesMixins.set(true)
    coreModClass.set("mixinplugin.TracerEarlyMixins")
    mixinConfigs.set(listOf("mixins.crashtracer.fml.json", "mixins.crashtracer.json"))
    mixinRefmap.set("mixins.crashtracer.refmap.json")
}

buildConfig {
    useJavaOutput()
    packageName(impactMod.modGroup.get())
    buildConfigField("String", "MODID", "\"${impactMod.modId.get()}\"")
    buildConfigField("String", "MODNAME", "\"${impactMod.modName.get()}\"")
    buildConfigField("String", "VERSION", "\"${project.version}\"")
    buildConfigField("String", "GROUPNAME", "\"${impactMod.modGroup.get()}\"")
}

dependencies {
    analyzerRuntime(libs.kotlin.stdlib.core)
    analyzerRuntime("com.google.code.gson:gson:2.13.2")
    api(libs.impact.forgelin)
    testImplementation(libs.test.junit)
    compileOnly("com.github.GTNewHorizons:BetterCrashes:1.4.7-GTNH:dev") {
        isTransitive = false
    }
}

val analyzerJar = tasks.register<ShadowJar>("analyzerJar") {
    group = "build"
    description = "Build the standalone local analyzer (Java 8, no Minecraft runtime)."
    archiveBaseName.set("crashtracer-analyzer")
    archiveClassifier.set("standalone")
    from(sourceSets.main.get().output) {
        include("space/impact/crashtracer/cli/**", "space/impact/crashtracer/diagnostics/**")
        exclude("**/TracerRuntime*", "**/ContextCollector*", "**/ForensicsCollector*", "**/RuntimeEvents*", "**/Watchdog*", "**/BetterCrashesIntegration*", "**/LogCapture*")
    }
    configurations = listOf(analyzerRuntime)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    manifest.attributes["Main-Class"] = "space.impact.crashtracer.cli.AnalyzerMain"
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
}

tasks.assemble { dependsOn(analyzerJar) }

tasks.test {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
}

// IDEA's Gradle runClient launch gets the manual test fixture automatically.
// Explicit properties still allow clean launches and tests without BetterCrashes.
val clientLaunchRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in setOf("runClient", "runObfClient")
}
val clientSmokeEnabled = providers.gradleProperty("clientSmoke").map(String::toBoolean).getOrElse(clientLaunchRequested)
if (clientSmokeEnabled) {
    if (providers.gradleProperty("clientSmokeBetterCrashes").map(String::toBoolean).getOrElse(true)) {
        dependencies.add("runtimeOnlyNonPublishable", "com.github.GTNewHorizons:BetterCrashes:1.4.7-GTNH:dev")
    }
    val clientSmoke = sourceSets.create("clientSmoke") {
        compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().runtimeClasspath
    }
    val clientSmokeJar = tasks.register<Jar>("clientSmokeJar") {
        from(clientSmoke.output)
        archiveClassifier.set("client-smoke-fixture")
    }
    val reobfClientSmoke = tasks.named<ReobfuscatedJar>("reobfClientSmokeJar") {
        archiveClassifier.set("client-smoke-fixture-reobf")
    }
    tasks.named<RunMinecraftTask>("runClient") {
        dependsOn(clientSmokeJar)
        classpath(clientSmokeJar)
    }
    tasks.named<RunMinecraftTask>("runObfClient") { dependsOn(reobfClientSmoke) }
    tasks.named<Copy>("prepareObfModsFolder") { from(reobfClientSmoke) }
}

// Isolated, opt-in server fixture. No fixture classes or dependencies enter the release JAR.
if (providers.gradleProperty("serverSmokeScenario").isPresent) {
    if (providers.gradleProperty("serverSmokeBetterCrashes").orNull == "true") {
        dependencies.add("runtimeOnlyNonPublishable", "com.github.GTNewHorizons:BetterCrashes:1.4.7-GTNH:dev")
    }
    val smoke = sourceSets.create("serverSmoke") {
        compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().runtimeClasspath
    }
    val smokeJar = tasks.register<Jar>("serverSmokeJar") {
        from(smoke.output)
        archiveClassifier.set("server-smoke-fixture")
    }
    tasks.named<RunMinecraftTask>("runServer") {
        dependsOn(smokeJar)
        classpath(smokeJar)
        systemProperty("crashtracer.smoke", providers.gradleProperty("serverSmokeScenario").get())
        systemProperty("mixin.debug.verbose", "true")
        if (providers.gradleProperty("serverSmokeScenario").get() == "oom") {
            minHeapSize = "256m"
            maxHeapSize = "256m"
        }
        if (providers.gradleProperty("serverSmokeScenario").get() == "native") {
            jvmArgs("-XX:-ShowMessageBoxOnError", "-XX:-CreateMinidumpOnCrash", "-XX:ErrorFile=hs_err_pid%p.log")
        }
        if (providers.gradleProperty("serverSmokeScenario").get().startsWith("mixin") || providers.gradleProperty("serverSmokeScenario").get() == "early-fml") {
            systemProperty("fml.coreMods.load", "space.impact.crashtracer.mixinplugin.TracerEarlyMixins,space.impact.crashtracer.smoke.mixinplugin.SmokeEarlyMixins")
        }
    }
    val reobfSmoke = tasks.named<ReobfuscatedJar>("reobfServerSmokeJar") {
        archiveClassifier.set("server-smoke-fixture-reobf")
    }
    tasks.named<RunMinecraftTask>("runObfServer") {
        dependsOn(reobfSmoke)
        systemProperty("crashtracer.smoke", providers.gradleProperty("serverSmokeScenario").get())
        systemProperty("mixin.debug.verbose", "true")
    }
    tasks.named<Copy>("prepareObfModsFolder") {
        from(reobfSmoke)
    }
}
