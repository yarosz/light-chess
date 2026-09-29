pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "karballo-flat"
include("engine", "bench", "scanner", "serve")
// The head-to-head also needs the flattened Pirarucu of the Pirarucu spike.
if (file("../pirarucu-spike/flat/engine/src").exists()) {
    include("pengine", "h2h")
    project(":pengine").projectDir = file("../pirarucu-spike/flat/engine")
}
