plugins {
    id("minecraft-setup")
    id("publish-setup")
}

impactMod {
    modId.set("modid")
    modName.set("Mod Name")
    modGroup.set("space.impact.modid")
    mavenGroup.set("space.impact")
    mavenUrl.set("https://maven.accident.space/repository/maven-releases/")
    injectGenerics.set(true)
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
}
