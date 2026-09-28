// GENERATED: rules copied verbatim from light-sdk/plugin/src/main/kotlin/com/thelightphone/plugin/LightSdkPlugin.kt
import java.io.File
object Rules {
        val BLOCKED_IMPORTS = listOf(
            "android.app.",
            "android.content.Context",
            "android.content.Intent",
            "android.content.ComponentName",
            "android.content.BroadcastReceiver",
            "android.content.ContentProvider",
            "android.content.ServiceConnection",
            "androidx.compose.ui.platform.LocalContext",
            "androidx.compose.ui.platform.LocalView",
            "androidx.compose.ui.platform.LocalLifecycleOwner",
            "androidx.lifecycle.compose.LocalLifecycleOwner",
            "androidx.activity.",
            "androidx.appcompat.",
            "java.lang.reflect.",
            "java.lang.invoke.",
            "kotlin.reflect.",
        )

        val BLOCKED_CODE_PATTERNS = listOf(
            Regex("""\bLocalContext\b""") to "LocalContext is not allowed — use LightScreen APIs instead",
            Regex("""\bLocalView\b""") to "LocalView is not allowed — use LightScreen APIs instead",
            Regex("""\bLocalActivity\b""") to "LocalActivity is not allowed — use LightScreen APIs instead",
            Regex("""\bLocalLifecycleOwner\b""") to "LocalLifecycleOwner is not allowed — use LightScreen APIs instead",
            Regex("""\bas\??\s+(?:\w+\.)*\w*Activity\b""") to "Casting to Activity is not allowed",
            Regex("""\bas\??\s+(?:\w+\.)*(?:Context|ContextWrapper|ContextThemeWrapper|Application|Service|ContentProvider|BroadcastReceiver)\b""") to "Casting to Android framework type is not allowed",
            Regex("""\bstartActivity\s*\(""") to "startActivity() is not allowed — use LightScreen.navigateTo() instead",
            Regex("""\bstartService\s*\(""") to "startService() is not allowed",
            Regex("""\bbindService\s*\(""") to "bindService() is not allowed",
            Regex("""\bregisterReceiver\s*\(""") to "registerReceiver() is not allowed",
            Regex("""\bgetSystemService\s*\(""") to "getSystemService() is not allowed",
            Regex("""\bcontentResolver\b""") to "contentResolver access is not allowed",
            Regex("""\bgetBaseContext\s*\(""") to "getBaseContext() is not allowed",
            Regex("""\battachBaseContext\s*\(""") to "attachBaseContext() is not allowed",
            Regex("""\bcreatePackageContext\s*\(""") to "createPackageContext() is not allowed",
            Regex("""\bcreateConfigurationContext\s*\(""") to "createConfigurationContext() is not allowed",
            Regex("""\bcreateDeviceProtectedStorageContext\s*\(""") to "createDeviceProtectedStorageContext() is not allowed",
            Regex("""\bcreateContextForSplit\s*\(""") to "createContextForSplit() is not allowed",
            Regex("""\bcreateAttributionContext\s*\(""") to "createAttributionContext() is not allowed",
            Regex("""\bcreateWindowContext\s*\(""") to "createWindowContext() is not allowed",
            Regex("""\bcreateDisplayContext\s*\(""") to "createDisplayContext() is not allowed",
            Regex("""\b\.javaClass\b""") to "Reflection is not allowed",
            Regex("""\b\.java\s*\.\s*\w""") to "Reflection is not allowed",
            Regex("""\bClass\s*\.\s*forName\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getDeclaredMethod\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getMethod\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getDeclaredField\s*\(""") to "Reflection is not allowed",
            Regex("""\b\.getField\s*\(""") to "Reflection is not allowed",
            Regex("""\bMethodHandles\b""") to "java.lang.invoke.MethodHandles is not allowed",
        )
        fun findSourceLineViolations(line: String): List<String> {
            val violations = mutableListOf<String>()
            line.split(';').forEach { statement ->
                val trimmed = statement.trim()
                if (trimmed.startsWith("import ")) {
                    val importPath = trimmed.removePrefix("import ").trim()
                    BLOCKED_IMPORTS.forEach { blocked ->
                        if (importPath.startsWith(blocked)) {
                            violations.add("blocked import '$importPath'")
                        }
                    }
                    return@forEach
                }
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEach
                BLOCKED_CODE_PATTERNS.forEach { (regex, msg) ->
                    if (regex.containsMatchIn(statement)) {
                        violations.add(msg)
                    }
                }
            }
            return violations
        }
}
fun main(args: Array<String>) {
    val root = File(args[0])
    var n = 0; var files = 0
    root.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { println("JAVA FILE: $it"); n++ }
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
        files++
        f.readLines().forEachIndexed { i, line ->
            Rules.findSourceLineViolations(line).forEach { println("${f.relativeTo(root)}:${i + 1}: $it"); n++ }
        }
    }
    println("scanned $files .kt files, $n violations")
}
