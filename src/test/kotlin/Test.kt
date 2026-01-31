import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test


class Test {
    var index = 1

    @Test
    fun test() {
        val map = mutableMapOf("apple" to 0, "orange" to 0, "banana" to 0)
        for (i in 1..100) {
            val items = mapOf("apple" to 10.0, "orange" to 11.0, "banana" to 1.0)
            val randomizedOrder = items.map { (i, w) -> i to ln(Random.nextDouble() / w) }.sortedBy { it.second }.map { it.first }
            randomizedOrder.forEachIndexed { i, s -> map[s] = map.getOrDefault(s, 0) + i }
        }
        println(map.entries.sortedBy { it.value })
    }
}