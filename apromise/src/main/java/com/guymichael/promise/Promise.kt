package com.guymichael.apromise.promise

import com.guymichael.apromise.BuildConfig
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.functions.Function
import io.reactivex.rxjava3.functions.Predicate
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

open class Promise<T>(single: Single<T>) {
    val single: Single<T>
    private var onErrorConsumer: Consumer<in Throwable>? = null
    private var asyncErrorConsumer: Consumer<in Throwable>? = null
    private var promiseSubscriber: Disposable? = null
    private var isResolved: Boolean = false
    protected var cancelExecutor: () -> Unit = {
        promiseSubscriber?.apply {
            if( !isDisposed) {
                dispose() //THINK local boolean 'isCancelled' to avoid further 'then' executions
                Logger.w(this@Promise.javaClass, "Promise Cancelled. isResolved:$isResolved")
            }
        }
    }

    init {
        this.single = single.doOnSuccess { isResolved = true }
    }

    fun isExecuted() : Boolean {
        return promiseSubscriber != null
    }

    private fun <S> passOnErrorConsumer(nextPromise: Promise<S>): Promise<S> {
        return this.onErrorConsumer?.let {
            nextPromise.catch(it)
        } ?: nextPromise
    }

    private fun <S> bindCancelExecution(nextPromise: Promise<S>): Promise<S> {
        return nextPromise.also {
            //when someone call cancel() on 'us', we want the next promise to be cancelled.
            //eventually, we need the very last promise to be the actual executor, as it
            //holds the Disposable and only it can actually cancel the whole Promise chain
            this.cancelExecutor = it::cancel
        }
    }

    private fun <S> createInstance(single: Single<S>, bindCancel: Boolean = true): Promise<S> {
        //schedule this 'next' promise to execute instead of 'this' promise

        checkIfExecutedAndThrow()

        return createInstanceImpl(single)
            .let(::passOnErrorConsumer)
            .letIf({ bindCancel }, ::bindCancelExecution)
    }

    fun execute() {
        checkIfExecutedAndThrow()

        this.promiseSubscriber = this.single.subscribe({
            isResolved = true
            Logger.dLazy(this@Promise.javaClass) { "Promise resolved: ${if (it is Any) it.javaClass.simpleName else ""} : $it" }

        }, { error ->
            Logger.w(this@Promise.javaClass,
                if (error is TimeoutException) "on timeout"
                else "on error: ${error.javaClass}, ${error.message}"
            )

            if (error !is SilentRejectionException) {
                onPromiseError(error)

                onErrorConsumer?.accept(error)
                    //or fail silently
                    ?: Logger.e(this@Promise.javaClass, "uncaught error: ${error.message}")
            }

            promiseSubscriber?.takeIf { !it.isDisposed }?.dispose()//THINK should dispose?
        })
    }

    @Throws(Exception::class)
    fun blockingExecuteOrThrow(): T {
        return single.blockingGet()
    }


    private fun checkIfExecutedAndThrow() {
        if (isExecuted()) {
            if (BuildConfig.DEBUG) {
                throw IllegalStateException("Promise: thenAndExecute() should only be called once, and after it only consumer/runnable then called are allowed")
            }
        }
    }

    protected open fun <R> createInstanceImpl(single: Single<R>) : Promise<R> {
        return Promise(single)
    }

    @Throws(java.lang.RuntimeException::class)
    protected open fun reject(e: Throwable): Nothing {
        throw if (e is RuntimeException) e else RuntimeException(e) //THINK check if already completed?
    }

    protected open fun onPromiseError(e: Throwable) {}




    open fun <R> thenAwait(function: Function<T, Promise<R>>) : Promise<R> {
        // we want to have an error consumer, so that it will pass on to future promises.
        // The fact that we have a separate asyncErrorConsumer helps both for having a late-init error consumer
        // and for maintaining the correct order of 'catch' sequence
        return catch { e -> asyncErrorConsumer?.takeIf { e !is SilentRejectionException }?.accept(e) }
            .createInstance(singleOfAsync(function))
    }

    open fun <R> thenAwait(function: (T) -> Promise<R>) : Promise<R> {
        return thenAwait(Function(function))
    }

    open fun <R> thenAwaitOrReject(function: (T) -> Promise<R>?) : Promise<R> {
        return thenAwait(Function {
            function(it) ?: reject(Throwable("thenAwaitOrReject(): 'function' returned null"))
        })
    }

    open fun <R> thenAwaitOrCancel(function: (T) -> Promise<R>?) : Promise<R> {
        return thenAwait(Function {
            function(it) ?: cancelImmediately("thenAwaitOrReject(): 'function' returned null")
        })
    }

    open fun <R> thenMap(function: Function<T, R>) : Promise<R> {
        return createInstance(singleOfMap(function))
    }

    open fun <R> thenMap(function: (T) -> R) : Promise<R> {
        return thenMap(Function(function))
    }

    /** Rejects if 'function' returns null */
    @JvmOverloads
    open fun <R> thenMapOrReject(function: (T) -> R?
            , rejectionExceptionSupplier: () -> Throwable = { Throwable("thenMapOrReject(): 'function' returned null") })
            : Promise<R> {

        return thenMap(Function {
            function(it) ?: reject(rejectionExceptionSupplier())
        })
    }

    /** Cancels if 'function' returns null */
    open fun <R> thenMapOrCancel(function: Function<T, R?>
            , withMsg: (() -> String)? = { "thenMapOrCancel(): 'function' returned null" })
            : Promise<R> {

        return thenMap(Function {
            function.apply(it) ?: cancelImmediately(withMsg?.invoke())
        })
    }

    /** Cancels if 'function' returns null */
    open fun <R> thenMapOrCancel(function: (T) -> R?
            , withMsg: (() -> String)? = { "thenMapOrCancel(): 'function' returned null" })
            : Promise<R> {

        return thenMapOrCancel(Function(function), withMsg)
    }

    open fun then(consumer: Consumer<T>) : Promise<T> {
        return createInstance(singleOfConsumer(consumer))
    }

    open fun then(consumer: (T) -> Unit) : Promise<T> {
        return then(Consumer(consumer))
    }

    open fun catch(consumer: Consumer<in Throwable>) : Promise<T> {
        this.onErrorConsumer = this.onErrorConsumer?.let { currentConsumer ->
            //chain catches
            Consumer<Throwable> {
                currentConsumer.accept(it)
                consumer.accept(it)
            }

        } ?: consumer //or this is the first error consumer

        return this
    }

    open fun catch(consumer: (Throwable) -> Unit) : Promise<T> {
        return catch(Consumer(consumer))
    }

    /**for Java, where 'catch' is prohibited as a method name*/
    open fun onError(consumer: Consumer<in Throwable>): Promise<T> {
        return catch(consumer)
    }

    /**for Java, where 'catch' is prohibited as a method name*/
    open fun onError(consumer: (Throwable) -> Unit): Promise<T> {
        return onError(Consumer(consumer))
    }

    open fun catchReturn(function: Function<Throwable, T>) : Promise<T> {
        return createInstance(singleOfErrorReturn(function))
    }

    open fun catchReturn(function: (Throwable) -> T) : Promise<T> {
        return catchReturn(Function(function))
    }

    open fun catchReturnNotNull(function: (Throwable) -> T?) : Promise<T> {
        return catchReturn(Function {
            function(it) ?: reject(Throwable("catchReturnNotNull(): 'function' returned null "))
        })
    }

    //TODO untested
    open fun catchResume(function: Function<Throwable, Promise<T>>) : Promise<T> {
        return createInstance(singleOfErrorResumeNext(function))
    }

    open fun catchResume(function: (Throwable) -> Promise<T>) : Promise<T> {
        return catchResume(Function(function))
    }

    open fun catchIgnore(consumer: Consumer<Throwable>) : Promise<Unit> {
        return thenMap { Unit }.catchReturn {
            consumer.accept(it)
            Unit
        }
    }

    open fun catchIgnore(consumer: (Throwable) -> Unit) : Promise<Unit> {
        return catchIgnore(Consumer(consumer))
    }

    open fun catchIgnore() : Promise<Unit> {
        return thenMap { Unit }.catchReturn { Unit }
    }

    //TODO untested
    open fun catchIgnoreResume(function: Function<Throwable, Promise<Unit>>) : Promise<Unit> {
        return thenMap { Unit }.catchResume(function)
    }

    open fun catchIgnoreResume(function: (Throwable) -> Promise<Unit>) : Promise<Unit> {
        return catchIgnoreResume(Function(function))
    }

    open fun rejectIf(predicate: Predicate<T>, errorSupplier: (T) -> Throwable) : Promise<T> {
        return createInstance(singleOfPredicate(predicate, errorSupplier))
    }

    open fun rejectIf(predicate: (T) -> Boolean, errorSupplier: (T) -> Throwable) : Promise<T> {
        return rejectIf(Predicate(predicate), errorSupplier)
    }

    @JvmOverloads
    open fun cancelIf(predicate: Predicate<T>, logMessageSupplier: ((T) -> String)? = null) : Promise<T> {
        return createInstance(singleOfCancelPredicate(predicate, logMessageSupplier))
    }

    @JvmOverloads
    open fun cancelIf(predicate: (T) -> Boolean, logMessageSupplier: ((T) -> String)? = null) : Promise<T> {
        return cancelIf(Predicate(predicate), logMessageSupplier)
    }

    open fun catchIf(predicate: Predicate<Throwable>, consumer: Consumer<Throwable>) : Promise<T> {
        return catch {
            if (predicate.test(it)) {
                consumer.accept(it)
            }
        }
    }

    open fun catchIf(predicate: (Throwable) -> Boolean, consumer: (Throwable) -> Unit) : Promise<T> {
        return catchIf(Predicate(predicate), Consumer(consumer))
    }

    open fun catchIgnoreIf(predicate: Predicate<Throwable>, consumer: Consumer<Throwable>) : Promise<Unit> {
        return catchIgnore {
            if (predicate.test(it)) {
                consumer.accept(it)
            } else {
                reject(it)//rethrow
            }
        }
    }

    open fun catchIgnoreIf(predicate: (Throwable) -> Boolean, consumer: (Throwable) -> Unit) : Promise<Unit> {
        return catchIgnoreIf(Predicate(predicate), Consumer(consumer))
    }

    open fun catchIgnoreUnmetCondition(consumer: Consumer<ConditionException>) : Promise<Unit> {
        return catchIgnoreIf({
            ConditionException::class.java.isInstance(it)
        }) {
            (it as? ConditionException)?.let { ce ->
                consumer.accept(ce)
            }
        }
    }

    open fun catchIgnoreUnmetCondition(consumer: (ConditionException) -> Unit) : Promise<Unit> {
        return catchIgnoreUnmetCondition(Consumer(consumer))
    }

    open fun catchIgnoreUnmetCondition() : Promise<Unit> {
        return catchIgnoreUnmetCondition(Consumer{})
    }

    /** delays and resumes by default on the Computation Scheduler (!) due to Single.delay impl. */
    open fun delay(ms: Long, resumeOn: Scheduler? = null): Promise<T> {
        if (ms == 0L) { return this }

        return createInstance(singleOfDelay(ms, resumeOn))
    }

    /** timeouts and resumes by default on the Computation Scheduler (!) due to Single.delay impl. */
    open fun timeout(ms: Long, resumeOn: Scheduler? = null): Promise<T> {
        return createInstance(singleOfTimeout(ms, resumeOn)) //THINK if 0 return? maybe it's smart to actually use 0
    }

    /** calls 'consumer' when this Promise finishes, with boolean 'isResolved' - true if Promise succeeded, false if failed or cancelled */
    open fun finally(consumer: (Boolean) -> Unit): Promise<T> {
        return createInstance(this.single.doFinally{ consumer(isResolved) })
    }

    /** same as [finally] for Java usage (safe word) */
    open fun doFinally(consumer: (Boolean) -> Unit): Promise<T> {
        return finally(consumer)
    }

    open fun doOnExecution(runnable: Runnable): Promise<T> {
        return createInstance(this.single.doOnSubscribe { runnable.run() })
    }

    open fun doOnExecution(runnable: () -> Unit): Promise<T> {
        return doOnExecution(Runnable(runnable))
    }

    /** Cancels the promise sometime in the future (disposes the underlying observable).
     * For immediate execution-chain cancellation, use [cancelIf]
     * will also run 'finally' */
    fun cancel() {
        cancelExecutor()
    }





    /* actions with other promises */

    //TODO errors in one promise stop the others. A parallel single should not know about the others
    infix fun or(other: Promise<T>): Promise<T> {
        return createInstance(
            this.single.doOnSuccess {
//                    Logger.w("Promise.or()", "this.doOnSuccess")
                    other.cancel() //THINK check if !resolved?
//                    other.cancelImmediately("Promise.or() - 'this' succeeded first - cancelling 'other'")
                }.mergeWith(other.single.doOnSuccess {
//                    Logger.w("Promise.or()", "other.doOnSuccess")
                    this.cancel() //THINK check if !resolved?
//                    this.cancelImmediately("Promise.or() - 'other' succeeded first - cancelling 'this'")
                }).firstOrError() //rx Single 'or'

            , false //we don't want cancelling in one stream to cancel the merged stream
        )
        //TODO we'd prefer cancelImmediately but any error signal will propagate to the merged promise.
        // currently because the cancel is not immediate, the second promise may succeed as well,
        // but it's safe because promise (the merged one this time) can't be resolved twice
    }


    /* merge back with rx */

    /*fun toMaybe(): Maybe<T> {//THINK what about all our listeners?
        return Maybe.fromSingle(this.single)
    }*/

    /*fun toObservable(): Observable<T> {//THINK what about all our listeners?
        return this.single.toObservable().doOnSubscribe {
            execute()
        }
    }*/













    /**
     * Cancels and rejects right away ([SilentRejectionException]) to prevent further advance of the chain
     * Remember to call from within a promise context (E.g. some 'then' consumer), as reject
     * practically throws an exception..
     */
    @Throws(SilentRejectionException::class)
    protected fun cancelImmediately(reason: String? = null): Nothing {
        cancel()

        //because cancel() [--> dispose()] will not stop the execution chain immediately, we need to (silently) reject
        reject(SilentRejectionException("Silent rejection due to Promise cancellation. With reason: $reason"))
    }



    private fun singleOfDelay(ms: Long, resumeOn: Scheduler? = null): Single<T> {
        return this.single.delay(ms, TimeUnit.MILLISECONDS).run {
            resumeOn?.let(::observeOn) ?: this
        }
    }

    private fun singleOfTimeout(ms: Long, resumeOn: Scheduler? = null): Single<T> {
        return this.single.timeout(ms, TimeUnit.MILLISECONDS).run {
            resumeOn?.let(::observeOn) ?: this
        }
    }

    private fun <R> singleOfAsync(function: Function<T, Promise<R>>) : Single<R> {
        return this.single.flatMap { t ->
            function.apply(t).let { nextPromise ->

                //pass the error consumer forward (nextPromise's catches will execute first)
                nextPromise.onErrorConsumer?.let {
                    this.asyncErrorConsumer = it
                }

                nextPromise.single
            }
        }
    }

    private fun singleOfErrorReturn(function: Function<Throwable, T>) : Single<T> {
        return this.single.onErrorReturn(function)
    }

    //TODO untested (especially the error handlers chain)
    private fun singleOfErrorResumeNext(function: Function<Throwable, Promise<T>>) : Single<T> {
        return this.single.onErrorResumeNext {e ->
            function.apply(e).let { nextPromise ->

                //pass the error consumer forward (nextPromise's catches will execute first)
                nextPromise.onErrorConsumer?.let {
                    this.asyncErrorConsumer = it
                }

                nextPromise.single
            }
        }
    }

    private fun singleOfConsumer(consumer: Consumer<T>) : Single<T> {
        return this.single.map {
            consumer.accept(it)
            it
        }
    }

    private fun singleOfPredicate(predicate: Predicate<T>, errorSupplier: (T) -> Throwable) : Single<T> {
        return this.single.map {
            if (predicate.test(it)) {
                reject(errorSupplier(it))
            }

            it
        }
    }

    /** cancels and immediately stops the whole execution (using a silent rejection) */
    private fun singleOfCancelPredicate(predicate: Predicate<T>, logMessageSupplier: ((T) -> String)? = null) : Single<T> {
        return this.single.map {
            if (predicate.test(it)) {
                val reason: String? = logMessageSupplier?.invoke(it)?.also { reason ->
                    Logger.w(this@Promise.javaClass, "Promise cancelled due to predicate, with reason: $reason")
                }

                cancelImmediately(reason)
            }

            it
        }
    }

    private fun <R> singleOfMap(function: Function<T, R>) : Single<R> {
        return this.single.map(function)
    }

    companion object {

        //TODO protected once kotlin supports the use of non-JVM static members protected in the super class
        //THINK parallelization - https://android.jlelse.eu/rxjava-parallelization-concurrency-zip-operator-fe87a36441ff
        fun singleOfZip(promises: List<Promise<*>>) : Single<Unit> {
            if (BuildConfig.DEBUG && RxJavaPlugins.getErrorHandler() == null) {
                throw IllegalStateException("Call to Promise.all() without setting a global RxJava error handler:\n" +
                        "Read more: https://github.com/ReactiveX/RxJava/issues/6249 : \n" +
                        "\"zip can only deal with one of its sources failing\n." +
                        "If multiple sources fail, RxJava can't tell if those other exceptions are significant for you and it can call onError again either.\n" +
                        "Thus it goes to the last resort handler as described in the wiki.\"")
            }

            return if (promises.isEmpty()) {
                //resolve
                singleOfCondition({ Unit }, { true })
            } else {
                Single.zip(promises.map {
                    it.single
                }) { Unit }
            }
        }

        //TODO protected once kotlin supports the use of non-JVM static members protected in the super class
        //THINK parallelization - https://android.jlelse.eu/rxjava-parallelization-concurrency-zip-operator-fe87a36441ff
        fun <R> singleOfZipResults(promises: List<Promise<R>>) : Single<List<R>> {
            if (BuildConfig.DEBUG && RxJavaPlugins.getErrorHandler() == null) {
                throw IllegalStateException("Call to Promise.all() without setting a global RxJava error handler:\n" +
                        "Read more: https://github.com/ReactiveX/RxJava/issues/6249 : \n" +
                        "\"zip can only deal with one of its sources failing\n." +
                        "If multiple sources fail, RxJava can't tell if those other exceptions are significant for you and it can call onError again either.\n" +
                        "Thus it goes to the last resort handler as described in the wiki.\"")
            }

            return if (promises.isEmpty()) {
                //resolve
                singleOfCondition({ emptyList<R>() }, { true })
            } else {
                Single.zip(promises.map { it.single }) {
                    (it.toList() as? List<R>?) ?: emptyList()
                }
            }
        }

        fun <R> singleOfSynchronousPromises(promises: List<Promise<R>>): Single<List<R>> {
            return Single.concat(promises.map { it.single }).toList()
        }

        //TODO protected once kotlin supports the use of non-JVM static members protected in the super class
        fun <R> singleOfCondition(supplier: () -> R?, predicate: (R) -> Boolean, rejectMsg: String = "Promise of condition: condition not met") : Single<R> {
            return Single.create {
                if( !it.isDisposed) {
                    val value = supplier()
                    if(value != null && predicate(value)) {
                        it.onSuccess(value)
                    } else {
                        it.onError(ConditionException(rejectMsg))
                    }
                }
            }
        }

        //TODO protected once kotlin supports the use of non-JVM static members protected in the super class
        fun <R> singleOfSupplier(supplier: () -> R) : Single<R> {
            return Single.create {
                if( !it.isDisposed) {
                    it.onSuccess(supplier())
                }
            }
        }

        //TODO protected once kotlin supports the use of non-JVM static members protected in the super class
        fun passOnErrorHandlers(from: List<Promise<*>>, to: Promise<*>) {
            from.forEach {
                it.onErrorConsumer?.let(to::catch)
            }
        }

        fun <R> of(supplier: () -> R) : Promise<R> {
            return Promise(singleOfSupplier(supplier))
        }

        fun <R> ofOrReject(supplier: () -> R?) : Promise<R> {
            return of(Unit)
                .thenMapOrReject({ supplier.invoke() })
        }

        fun <R> ofOrCancel(supplier: () -> R?) : Promise<R> {
            return of(Unit)
                .thenMapOrCancel({ supplier.invoke() })
        }


        /**
         * Note: catches may receive [Throwable] of type [SimpleCallbackException] with a nullable [R] as it's 'result'
         */
        @JvmStatic
        fun <R, CALLBACK: Any?> ofCallback(call: (SimpleCallback<R>) -> CALLBACK
               , finallyAction: ((CALLBACK) -> Unit)? = null): Promise<R> {
            var callback: CALLBACK? = null

            return Promise<R>(Single.create {
                if( !it.isDisposed) {
                    callback = call(object : SimpleCallback<R> {
                        override fun onSuccess(r: R) {
                            if( !it.isDisposed) {
                                it.onSuccess(r)
                            }
                        }

                        override fun onFailure(e: Throwable) {
                            if( !it.isDisposed) {
                                it.onError(e)
                            }
                        }
                    })
                }
            })
            .finally { callback?.let { finallyAction?.invoke(it) }}
        }

        /**
         * Note: catches may receive [Throwable] of type [SimpleCallbackException] with a nullable [R] as it's 'result'
         */
        @JvmStatic
        fun <R: Any?> ofCallback(call: Consumer<SimpleCallback<R>>): Promise<R> {
            return ofCallback<R, Unit>({ call.accept(it) }, null)
        }

        /**
         * Note: catches may receive [Throwable] of type [SimpleCallbackException] with a nullable [R] as it's 'result'
         */
        fun <R: Any?> ofCallback(call: (SimpleCallback<R>) -> Unit): Promise<R> {
            return ofCallback(call, null)
        }

        /**
         * @param supplier if returns null, the condition is not met, regardless of 'predicate'
         * @param predicate if 'supplier' returns not null, AND this predicate returns true, the promise will go forward. Else will fail
         */
        fun <R> ofConditionOrReject(supplier: () -> R?, predicate: (R) -> Boolean) : Promise<R> {
            return Promise(singleOfCondition(supplier, predicate))
        }

        fun <R> ofConditionOrReject(supplier: () -> R?) : Promise<R> {
            return Promise(singleOfCondition(supplier, { true }))
        }

        fun ofBooleanOrReject(supplier: () -> Boolean) : Promise<Unit> {
            return Promise(singleOfCondition(supplier, { it })).thenMap { Unit }
        }

        fun <R, S> ofConditionOrIgnore(supplier: () -> R?, predicate: (R) -> Boolean
                                    , function: (R) -> Promise<S>
        ) : Promise<Unit> {
            return ofConditionOrReject(supplier, predicate)
                    .thenAwait(Function(function))
                    .catchIgnoreUnmetCondition()
        }

        /**
         * @param supplier if returns null, the condition is not met, regardless of 'predicate'
         * @param predicate if 'supplier' returns not null, AND this predicate returns true, the promise will go forward.
         * @param function an async task to run if 'predicate' returned true
         * @param consumer an action to execute if 'predicate' returned false(or 'supplier' returned null) - hence promise got rejected
         * Else will fail - and be ignored(catchReturn)
         */
        fun <R, S> ofConditionOrIgnore(supplier: () -> R?, predicate: (R) -> Boolean
                                       , function: (R) -> Promise<S>, consumer: (Throwable) -> Unit) : Promise<Unit> {
            return ofConditionOrReject(supplier, predicate)
                    .thenAwait(Function(function))
                    .catchIgnoreUnmetCondition(consumer)
        }

        fun <R, S> ofConditionOrIgnore(supplier: () -> R?, function: (R) -> Promise<S>) : Promise<Unit> {
            return ofConditionOrReject(supplier, { true })
                    .thenAwait(Function(function))
                    .catchIgnoreUnmetCondition()
        }

        //TODO errors here stop all promises. A parallel single should not know about the others
        fun all(promises: List<Promise<*>>) : Promise<Unit> {
            return Promise(singleOfZip(promises)).apply {
                passOnErrorHandlers(promises, this)
            }
        }

        fun <R> runSynchronously(promises: List<Promise<R>>) : Promise<List<R>> {
            return Promise(singleOfSynchronousPromises(promises)).apply {
                passOnErrorHandlers(promises, this)//THINK needed?
            }
        }

        fun <R> allResults(promises: List<Promise<R>>) : Promise<List<R>> {
            return Promise(singleOfZipResults(promises)).apply {
                passOnErrorHandlers(promises, this)
            }
        }

        fun all(vararg promises: Promise<*>?) : Promise<Unit> {
            return all(promises.filterNotNull())
        }

        @JvmStatic
        @JvmOverloads
        fun <R> ofReject(rejectMsg: String = "Promise of default rejection") : Promise<R> {
            return Promise(singleOfCondition<R>({ null }, { false }, rejectMsg))
        }

        fun <R> of(r: R) : Promise<R> {
            return Promise(singleOfCondition({ r }, { true }))
        }

        fun of() : Promise<Unit> {
            return Promise(singleOfCondition({ Unit }, { true }))
        }

        class ConditionException(message: String?) : Throwable(message)
        interface SimpleCallback<R> {
            fun onSuccess(r: R)
            fun onFailure(e: Throwable)
            fun onFailure(r: R?) = onFailure(SimpleCallbackException(r))
            //THINK is it good ? safe? create Maybe instead?
            fun onCancel(reason: String? = null) = onFailure(SilentRejectionException("Silent rejection due to Promise-of-callback cancellation. With reason: $reason"))
        }
        class SimpleCallbackException(val result: Any?): RuntimeException()
        protected class SilentRejectionException(reason: String): RuntimeException(reason)
    }
}