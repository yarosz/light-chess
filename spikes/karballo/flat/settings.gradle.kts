pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "karballo-flat"
include("engine", "bench", "scanner")
include("pengine", "h2h")
project(":pengine").projectDir = file("../pirarucu-spike/flat/engine")
