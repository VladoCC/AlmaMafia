package org.example

import kotlin.math.ceil


public fun <T> reordered(list: List<T>) = with(ceil(list.size / 2.0).toInt()) {
    List(list.size) {
        list[if (it % 2 == 0) it / 2 else this + it / 2]
    }
}