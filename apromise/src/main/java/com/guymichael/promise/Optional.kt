package com.guymichael.promise

import java.io.Serializable

data class Optional<T>(val value: T?) : Serializable {

    companion object {
        fun <R> empty() = Optional<R>(null)
    }
}