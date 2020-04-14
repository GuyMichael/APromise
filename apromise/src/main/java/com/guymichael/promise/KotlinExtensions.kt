package com.guymichael.apromise.promise

inline fun <T> T.letIf(condition: (T) -> Boolean, crossinline block: (T) -> T) :T {
    return this.takeIf(condition)?.let(block) ?: this
}