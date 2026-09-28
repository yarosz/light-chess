plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":engine")) }
application { mainClass.set("BenchKt") }
