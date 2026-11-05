plugins {
    id("bending.base-conventions")
    id("net.neoforged.moddev")
}

neoForge {
    neoFormVersion = libs.versions.neoform.get()
}

tasks.withType<net.neoforged.nfrtgradle.NeoFormRuntimeTask>().configureEach {
    javaExecutable.set(ProcessHandle.current().info().command().orElse("java"))
}
tasks.withType<net.neoforged.nfrtgradle.CreateMinecraftArtifacts>().configureEach {
    toolsJavaExecutable.set(ProcessHandle.current().info().command().orElse("java"))
}

dependencies {
    compileOnlyApi(projects.bendingApi)
}
