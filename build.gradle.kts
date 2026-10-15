plugins {
    id("minecraft-setup")
    id("publish-setup")
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
    mixinConfigs.set(listOf("mixins.crashtracer.json"))
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
    api(libs.impact.forgelin)
    compileOnly("com.github.GTNewHorizons:BetterCrashes:1.4.7-GTNH:dev") {
        isTransitive = false
    }
}
