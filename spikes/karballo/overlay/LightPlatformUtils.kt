package karballo.util

import kotlin.random.Random

/** Replaces JvmPlatformUtils: no System.exit/gc, no java.text, stdlib only. */
class LightPlatformUtils(seed: Long? = null) : PlatformUtils {
    private val random = if (seed != null) Random(seed) else Random.Default
    override fun randomFloat(): Float = random.nextFloat()
    override fun randomInt(bound: Int): Int = random.nextInt(bound)
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun getCurrentDateIso(): String = "????-??-??"
    override fun arrayFill(array: ShortArray, value: Short) = array.fill(value)
    override fun arrayFill(array: IntArray, value: Int) = array.fill(value)
    override fun arrayFill(array: LongArray, value: Long) = array.fill(value)
    override fun arrayCopy(src: IntArray, srcPos: Int, dest: IntArray, destPos: Int, length: Int) {
        src.copyInto(dest, destPos, srcPos, srcPos + length)
    }
    override fun exit(code: Int) {}
    override fun gc() {}
}
