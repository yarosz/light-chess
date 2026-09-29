// Modified for the Chess Tool (github.com/yarosz/light-chess), 2026-09-28.
// Original: Pirarucu (github.com/ratosh/pirarucu) at 987dd02, GPL-3.0; this file stays GPL-3.0.
// Change: the `expect object` replaced with a plain stdlib object.
package pirarucu.util

// Plain-Kotlin replacement for upstream's `expect object` (its JVM `actual` used java.lang and
// reflection). Dropped, as nothing left calls them: applyConfig (reflection on TunableConstants),
// exit, gc, getVersion, formatString.
object PlatformSpecific {

    fun currentTimeMillis(): Long = System.currentTimeMillis()

    fun numberOfTrailingZeros(value: Long): Int = value.countTrailingZeroBits()

    fun numberOfTrailingZeros(value: Int): Int = value.countTrailingZeroBits()

    fun bitCount(value: Long): Int = value.countOneBits()

    fun reverseBytes(value: Long): Long {
        var v = value
        v = ((v ushr 8) and 0x00FF00FF00FF00FFL) or ((v and 0x00FF00FF00FF00FFL) shl 8)
        v = ((v ushr 16) and 0x0000FFFF0000FFFFL) or ((v and 0x0000FFFF0000FFFFL) shl 16)
        return (v ushr 32) or (v shl 32)
    }

    fun arraySort(array: IntArray, start: Int, end: Int) = array.sort(start, end)

    fun arrayFill(array: ShortArray, value: Short) = array.fill(value)

    fun arrayFill(array: IntArray, value: Int) = array.fill(value)

    fun arrayFill(array: LongArray, value: Long) = array.fill(value)

    fun arrayFill(array: Array<IntArray>, value: Int) = array.forEach { it.fill(value) }

    fun arrayFill(array: Array<Array<IntArray>>, value: Int) = array.forEach { arrayFill(it, value) }

    fun arrayCopy(src: IntArray, srcPos: Int, dest: IntArray, destPos: Int, length: Int) {
        src.copyInto(dest, destPos, srcPos, srcPos + length)
    }

    fun arrayCopy(src: Array<IntArray>, dest: Array<IntArray>) {
        for (i in src.indices) src[i].copyInto(dest[i])
    }

    fun arrayCopy(src: LongArray, srcPos: Int, dest: LongArray, destPos: Int, length: Int) {
        src.copyInto(dest, destPos, srcPos, srcPos + length)
    }

    fun arrayCopy(src: Array<LongArray>, dest: Array<LongArray>) {
        for (i in src.indices) src[i].copyInto(dest[i])
    }
}
