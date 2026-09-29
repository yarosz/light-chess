plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
application { mainClass.set("ScanKt") }
