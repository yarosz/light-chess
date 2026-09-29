plugins {
    kotlin("jvm") version "2.3.20"
    application
}

layout.buildDirectory.set(file("../../build/book-build"))

kotlin {
    jvmToolchain(17)
    sourceSets.getByName("main").kotlin.srcDirs(
        file("../../tool/src/main/kotlin/com/yarosz/chess/rules"),
        file("../../tool/src/main/kotlin/com/yarosz/chess/book"),
        file("src"),
    )
}

application {
    mainClass.set("com.yarosz.chess.bookbuild.BookBuildKt")
    applicationName = "book-build"
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}
