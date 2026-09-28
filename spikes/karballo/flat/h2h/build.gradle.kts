plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":engine")); implementation(project(":pengine")) }
application { mainClass.set("H2hKt"); applicationDefaultJvmArgs = listOf("-Xmx2g") }
