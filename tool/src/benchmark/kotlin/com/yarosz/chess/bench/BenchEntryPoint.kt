package com.yarosz.chess.bench

import android.os.Process
import android.util.Log
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import com.yarosz.chess.BuildConfig
import com.yarosz.chess.engine.EngineHost
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The benchmark build type only (src/benchmark): runs [BenchSuite] once per build when the Tool starts.
 * scripts/bench.sh builds the APK with a fresh `bench.id` and the number of runs (BuildConfig), installs
 * it and starts the Tool. The Tool records the id in its own files before the suite starts, so a crash
 * never loops and a later start of the same build does nothing. Results go to logcat under ChessBench.
 *
 * Why the trigger is baked into the build: the build is not debuggable, so adb has no `run-as` into the
 * Tool's files; a file adb creates under /sdcard/Android/data/<package> belongs to the shell and the
 * Tool cannot read it; and the entry point has neither a Context nor the launch Intent. It runs before
 * any screen, so its own files are found by path. Should the Tool need its own @EntryPoint in main,
 * this moves into it.
 */
@EntryPoint
object BenchEntryPoint : LightEntryPoint {

    private const val TAG = "ChessBench"
    private val DATA_DIRS = listOf("/data/user/0/com.yarosz.chess", "/data/data/com.yarosz.chess")

    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        val id = BuildConfig.BENCH_ID
        if (id.isEmpty()) return
        val marker = DATA_DIRS.map(::File).firstOrNull { it.isDirectory }?.resolve("files/chess-bench-ran") ?: run {
            Log.e(TAG, "failed no data directory")
            return
        }
        if (marker.isFile && marker.readText() == id) return
        try {
            marker.parentFile?.mkdirs()
            marker.writeText(id)
        } catch (e: Exception) {
            Log.e(TAG, "failed cannot write ${marker.path}: ${e.message}")
            return
        }
        try {
            BenchSuite.run(EngineHost.shared, BuildConfig.BENCH_RUNS, { Log.i(TAG, it) }, ::currentCore, { Process.myTid().toLong() })
        } catch (e: Exception) {
            Log.e(TAG, "failed ${e.message}")
        }
    }

    /** Field 39 ("processor") of /proc/self/task/<tid>/stat for the calling thread. */
    private fun currentCore(): String = try {
        val stat = File("/proc/self/task/${Process.myTid()}/stat").readText()
        // Fields 3 onward follow the ")" that closes the command name, which may contain spaces.
        stat.substring(stat.lastIndexOf(')') + 2).split(' ')[39 - 3]
    } catch (e: Exception) {
        "?"
    }
}
