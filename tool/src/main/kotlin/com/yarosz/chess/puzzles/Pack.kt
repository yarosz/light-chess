package com.yarosz.chess.puzzles

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One Band's entry in the Pack manifest: its file and the Puzzle Ratings in it. */
@Serializable
data class Band(
    /** The Band's lower bound: 1500 for 1500 to 1599. The lowest Band also holds lower ratings. */
    val band: Int,
    val file: String,
    val puzzles: Int,
    val minRating: Int,
    val maxRating: Int,
    val bytes: Long,
    val sha256: String,
)

/** `pack/manifest.json`, written by `scripts/build-pack.py`. */
@Serializable
data class PackManifest(
    val schemaVersion: Int,
    /** SHA-256 over the rest of the manifest; the save file keeps it (A8) to notice a new Pack (F1). */
    val packSha256: String,
    val puzzles: Int,
    val bands: List<Band>,
    /** The Lichess dump the Pack was built from; About shows its date (D7). */
    val source: PackSource? = null,
)

/** The manifest's `source`: the dump's Last-Modified date, `2026-09-09`. */
@Serializable
data class PackSource(val date: String)

/**
 * The Puzzles that ship inside the Tool, read from `pack/` in the Tool's assets: the manifest, then
 * each Band's file the first time a question needs it. [readAsset] is `lightContext.readAsset` in
 * the Tool and reads files in tests. Safe to call from any thread.
 */
class Pack(private val readAsset: (String) -> ByteArray) {

    val manifest: PackManifest by lazy {
        json.decodeFromString(PackManifest.serializer(), readAsset("$DIR/manifest.json").decodeToString())
    }

    private val bands: List<Band> get() = manifest.bands

    private val loaded = HashMap<Int, List<Puzzle>>()

    /** The Puzzles the last [prefetch] found, by Lichess id. */
    private val found = HashMap<String, Puzzle>()

    /** The Band whose 100-point slice holds [rating]: the lowest or top Band beyond the Pack's range. */
    fun bandFor(rating: Int): Band = bands.lastOrNull { it.band <= rating } ?: bands.first()

    /**
     * Every Puzzle in [band], sorted by Puzzle Rating. Read once, on first use; each Puzzle's FEN and
     * Moves are parsed when first asked for, and CommittedPackTest checks them all.
     */
    fun puzzles(band: Band): List<Puzzle> = synchronized(loaded) {
        loaded.getOrPut(band.band) {
            readAsset("$DIR/${band.file}").decodeToString().lineSequence()
                .filter { it.isNotEmpty() }
                .map(Puzzle::read)
                .toList()
        }
    }

    /**
     * The Puzzles within ±100 of [playerRating] that aren't in [finished] (Lichess ids), sorted by
     * Puzzle Rating. When none are left there, the window widens by 100 at a time (A7); empty only
     * when every Puzzle in the Pack is finished.
     */
    fun candidates(playerRating: Int, finished: Set<String>): List<Puzzle> {
        val lowest = bands.minOf { it.minRating }
        val highest = bands.maxOf { it.maxRating }
        var reach = WINDOW
        while (true) {
            val low = playerRating - reach
            val high = playerRating + reach
            val found = bands.filter { it.maxRating >= low && it.minRating <= high }
                .flatMap { band -> puzzles(band).filter { it.rating in low..high && it.id !in finished } }
            if (found.isNotEmpty() || (low <= lowest && high >= highest)) return found
            reach += WINDOW
        }
    }

    /**
     * The Puzzle with this Lichess id, or null when the Pack doesn't have it (F1: a Puzzle can leave
     * with a new Pack). [ratingHint] names the Band to look in first; the others are searched as
     * text, nearest first, and only the matching line is parsed.
     */
    fun puzzle(id: String, ratingHint: Int? = null): Puzzle? {
        synchronized(found) { found[id] }?.let { return it }
        val order = if (ratingHint == null) bands else bands.sortedBy { kotlin.math.abs(it.band - bandFor(ratingHint).band) }
        for (band in order) {
            val cached = synchronized(loaded) { loaded[band.band] }
            if (cached != null) {
                cached.firstOrNull { it.id == id }?.let { return it }
                continue
            }
            lineOf(readAsset("$DIR/${band.file}").decodeToString(), id)?.let { return Puzzle.parse(it) }
        }
        return null
    }

    /**
     * Reads ahead the Puzzles [wanted] names (Lichess id to Puzzle Rating), so that [puzzle] and
     * [cached] find them without reading a file, and returns the ids the Pack doesn't have. First the
     * Bands their ratings name: those Puzzles are found as soon as that pass ends, replacing what the
     * last call found (one found by an earlier call is kept without a read). Then, for any still
     * missing, the other Bands. Each Band file is read at most once in the call, whatever the number
     * of ids, and only the matching lines are parsed. For a background thread (Missed, D2, N13).
     */
    fun prefetch(wanted: Map<String, Int>): Set<String> {
        val out = HashMap<String, Puzzle>()
        synchronized(found) { for (id in wanted.keys) found[id]?.let { out[id] = it } }
        val searched = HashSet<Int>()
        for ((band, ids) in wanted.keys.filter { it !in out }.groupBy { bandFor(wanted.getValue(it)) }) {
            searched += band.band
            out += find(band, ids)
        }
        synchronized(found) {
            found.clear()
            found.putAll(out)
        }
        val missing = wanted.keys.filterTo(HashSet()) { it !in out }
        for (band in bands) {
            if (missing.isEmpty()) break
            if (!searched.add(band.band)) continue
            val hits = find(band, missing)
            missing -= hits.keys
            synchronized(found) { found.putAll(hits) }
        }
        return missing
    }

    /** The Puzzle with this Lichess id if the last [prefetch] found it: reads no file. */
    fun cached(id: String): Puzzle? = synchronized(found) { found[id] }

    /** Those of [ids] in [band]: from its Puzzles when loaded, else from one read of its file. */
    private fun find(band: Band, ids: Collection<String>): Map<String, Puzzle> {
        val cached = synchronized(loaded) { loaded[band.band] }
        if (cached != null) return ids.toHashSet().let { want -> cached.filter { it.id in want }.associateBy { it.id } }
        val text = readAsset("$DIR/${band.file}").decodeToString()
        return ids.mapNotNull { id -> lineOf(text, id)?.let { id to Puzzle.parse(it) } }.toMap()
    }

    /** The line of [text] (a Band file) for [id], or null. */
    private fun lineOf(text: String, id: String): String? {
        val prefix = "$id;"
        val at = if (text.startsWith(prefix)) 0 else text.indexOf("\n$prefix").let { if (it < 0) return null else it + 1 }
        return text.substring(at, text.indexOf('\n', at).let { if (it < 0) text.length else it })
    }

    /**
     * Every Lichess id in the Pack, read as text from every Band file without parsing a Puzzle. For
     * the carry-over after a Pack update (F1), which asks about many ids at once.
     */
    val ids: Set<String> by lazy {
        val out = HashSet<String>(manifest.puzzles * 2)
        for (band in bands) {
            val cached = synchronized(loaded) { loaded[band.band] }
            if (cached != null) {
                cached.mapTo(out) { it.id }
                continue
            }
            readAsset("$DIR/${band.file}").decodeToString().lineSequence()
                .filter { it.isNotEmpty() }
                .mapTo(out) { it.substringBefore(';') }
        }
        out
    }

    companion object {
        const val DIR = "pack"
        /** The Puzzle window's half-width, and how far it widens each time it is empty (A7). */
        const val WINDOW = 100
        private val json = Json { ignoreUnknownKeys = true }
    }
}
