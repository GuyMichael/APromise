package com.guymichael.apromise

import com.guymichael.promise.Promise
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.Test

import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class CheckOrderUnitTest {
    @Test
    fun check_order_simple() {
        Promise.of(mutableListOf(1))
            .then { it += 2 }
            .thenAwait({
                Thread.sleep(500)
                Promise.of(it + 3)
            }, executeOn = Schedulers.computation())
            .thenMap { l -> l.map { it.toLong() } + 4L }
            .toObservable()
            .test()
            .awaitDone(700, TimeUnit.MILLISECONDS)
            .assertValue {
                for (i in 1 until it.size) {
                    if (it[i] == it[i - 1] + 1) continue
                    else return@assertValue false
                }
                true
            }
    }
}
