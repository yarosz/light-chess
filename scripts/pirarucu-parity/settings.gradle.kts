// Parity check for the vendored Pirarucu (scripts/vendor-pirarucu.py --parity). Not part of the Tool
// build: two plain JVM programs run the same fixed-depth suite, one over upstream 987dd02 (prepared in
// build/pirarucu-parity/upstream-src by the script), one over tool/src/main/kotlin/vendor/pirarucu.
// Their project directories (and so their outputs) live in the repo's gitignored build/.
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "pirarucu-parity"
for (side in listOf("upstream", "vendored")) {
    include(side)
    val dir = file("../../build/pirarucu-parity/$side")
    dir.mkdirs()
    project(":$side").projectDir = dir
}
