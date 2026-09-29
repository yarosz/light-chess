// The opening Book's builder (docs/book.md, scripts/build-book.sh). Not part of the Tool build: a
// plain JVM program over the Tool's own rules core and Book reader, compiled from tool/src/main.
// Its build output lives in the repo's gitignored build/book-build.
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "book-build"
