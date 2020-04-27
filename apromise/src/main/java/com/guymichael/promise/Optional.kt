package com.guymichael.promise

import java.io.Serializable

/**
 * To be used as a type of a [Promise] when nullable values are acceptable (such as an unique API
 * which may return true even on success
 */
data class Optional<T>(val value: T?) : Serializable {

    companion object {
        fun <R> empty() = Optional<R>(null)
    }
}