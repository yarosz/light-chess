plugins { kotlin("jvm") version "2.3.20" apply false }

val sources = mapOf(
    "upstream" to "../../build/pirarucu-parity/upstream-src",
    "vendored" to "../../tool/src/main/kotlin/vendor/pirarucu",
)

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "application")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
        sourceSets.getByName("main").kotlin.srcDirs(
            rootProject.file(sources.getValue(project.name)),
            rootProject.file("harness"),
        )
        // Upstream's JVM PlatformSpecific calls String.toUpperCase(), an error from API 2.1 on (only in
        // applyConfig, which the suite never calls). Compile upstream against API 2.0 rather than edit it.
        if (project.name == "upstream") {
            compilerOptions.apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
            compilerOptions.languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        }
    }
    extensions.configure<JavaApplication> {
        mainClass.set("ParityKt")
        applicationName = project.name
    }
}
