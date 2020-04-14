package com.guymichael.apromise.promise

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit


object Utils {

    /** executes `consumer` on fixed time intervals and in a non-blocking manner */
    @JvmStatic
    @JvmOverloads
    fun periodic(periodMs: Long
            , scheduler: Scheduler = Schedulers.computation()
            , consumer: Consumer<Long>
    ): Disposable {

        return Observable.interval(periodMs, TimeUnit.MILLISECONDS, scheduler)
            .retry()
            .subscribe(consumer, Consumer {})
    }

    /** executes `consumer` on fixed time intervals and in a non-blocking manner */
    fun periodic(periodMs: Long
            , scheduler: Scheduler = Schedulers.computation()
            , consumer: (Long) -> Unit): Disposable {

        return periodic(periodMs, scheduler, Consumer(consumer))
    }

}