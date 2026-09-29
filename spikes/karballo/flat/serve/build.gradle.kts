plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":engine")) }
application { mainClass.set("ServeKt"); applicationDefaultJvmArgs = listOf("-Xmx512m") }
