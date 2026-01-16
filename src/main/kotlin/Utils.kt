package org.example

import org.example.telegram.KeyboardContext
import kotlin.math.ceil


fun <T> reordered(list: List<T>) = with(ceil(list.size / 2.0).toInt()) {
    List(list.size) {
        list[if (it % 2 == 0) it / 2 else this + it / 2]
    }
}

fun Number.pretty() = this.toString().map { numbers[it.toString().toInt()] }.joinToString("")

class DoubleColumnView<I, R>(private var list: List<I>, private val receiverFun: (R.() -> Unit) -> Unit) {
    fun default(transform: R.() -> Unit) = DoubleColumnViewWithDefault(list, receiverFun, transform)

    class DoubleColumnViewWithDefault<I, R>(
        private var list: List<I>,
        private val receiverFun: (R.() -> Unit) -> Unit,
        private val default: R.() -> Unit
    ) {
        fun build(transform: R.(I) -> Unit) {
            reordered(list).chunked(2).let { list ->
                list.forEach {
                    receiverFun {
                        transform(it[0])
                        if (it.size > 1) transform(it[1]) else default()
                    }
                }
            }
        }
    }
}

fun <I> KeyboardContext.doubleColumnView(list: List<I>): DoubleColumnView<I, KeyboardContext.RowContext> =
    DoubleColumnView(list) { row { it() } }