package com.guymichael.apromise

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.guymichael.promise.Promise
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.functions.Function
import io.reactivex.rxjava3.functions.Predicate
import io.reactivex.rxjava3.schedulers.Schedulers
import java.lang.ref.WeakReference

/**
 * an Android (or rather, Api) Promise.
 * Handles all kinds of Context/Activity/View related tasks, to skip, reject or cancel the Promise automatically
 * upon context destroy.
 * Also, it lets you automatically Toast error messages for you. Or, if you override this class, just any type of UI error handling
 */
open class APromise<T>(single: Single<T>) : Promise<T>(single) {
    private var contextRef = WeakReference<Activity>(null)
    private var failSilently = true

    override fun <S> createInstanceImpl(single: Single<S>): APromise<S> {
        return APromise(single)
    }

    final override fun onPromiseError(e: Throwable) {
        if( !this.failSilently) {
            getContext(this.contextRef)?.let { onPromiseErrorAutoHandle(it, e) }
        }
    }

    protected open fun onPromiseErrorAutoHandle(context: Activity, e: Throwable) {
        globalErrorHandler(context, e)
    }

    /** cancels (disposes) this promise when activity is destroyed.
     * This will also call 'finally'.
     * Note that it won't necessarily stop the promise execution chain immediately (as per [cancel])*/
    private fun autoCancel(context: Activity): APromise<T> {
        this.contextRef = WeakReference(context)//THINK local ref to let auto cancel on multiple contexts

        val activityDestroyPromise = ViewUtils.waitForDestroy(context).then {
            //TODO break the execution chain immediately. see cancel() docs and singleOfCancelPredicate()
            this@APromise.cancel()
        }

        return doOnExecution {
            activityDestroyPromise.execute()
        }.finally {
            activityDestroyPromise.cancel()
        }
    }

    private fun autoCancel(view: View): APromise<T> {//TODO QA

        val viewDetachPromise = ViewUtils.waitForDetach(view).then {
            //TODO break the execution chain immediately. see cancel() docs and singleOfCancelPredicate()
            this@APromise.cancel()
        }

        return doOnExecution {
            viewDetachPromise.execute()
        }.finally {
            viewDetachPromise.cancel()
        }
    }

    /**
     * @param context if not null, any HTTP/API errors will be auto-shown to user (as a Toast)
     * @param autoCancel if true, executes with auto cancel - [executeWhileAlive]
     */
    @JvmOverloads
    fun executeAutoHandleErrorMessage(context: Activity, autoCancel: Boolean = false): Disposable {
        this.contextRef = WeakReference(context)
        this.failSilently = false

        return if (autoCancel) {
            executeWhileAlive(context)
        } else {
            execute()
        }
    }

    /**
     * @param view if context not null, any HTTP/API errors will be auto-shown to user (as a Toast)
     * @param autoCancel if true, executes with auto cancel - [executeWhileAlive]
     */
    @JvmOverloads
    fun executeAutoHandleErrorMessage(view: View, autoCancel: Boolean = false): Disposable {
        return AndroidUtils.getActivity(view)?.let {
            executeAutoHandleErrorMessage(it, autoCancel)
        } ?: DisposedDisposable
    }

    /** cancels when context is destroyed
     * auto cancels sometime after activity is destroyed. See [cancel] */
    fun executeWhileAlive(context: Activity): Disposable {
        return autoCancel(context).execute()
    }

    /** cancelled when view is destroyed / detached from window.
     * Note: does not execute if view's context is (already) null */
    fun executeWhileAlive(view: View): Disposable {//TODO THINK fail the promise(!) ? cancel (hasn't been executed)
        return AndroidUtils.getActivity(view)?.let { activity ->
            autoCancel(view).executeWhileAlive(activity)
        } ?: DisposedDisposable
    }

    fun doOnExecutionOrReject(consumer: Consumer<Activity>, context: Activity): APromise<T> {
        this.contextRef = WeakReference(context) //THINK local context

        return doOnExecution {
            getContext(contextRef)?.let { consumer.accept(it) }
                ?: reject(Throwable("APromise - null context"))
        }
    }

    /** skips this consumer (only) if the context became null */
    fun <A : Activity> doOnExecutionWithContext(context: A, consumer: Consumer<A>): APromise<T> {
        val ref = WeakReference(context)

        return doOnExecution {
            getContext(ref)?.let { consumer.accept(it) } //or skip
        }
    }

    /** skips this consumer (only) if the context became null */
    fun <A : Activity> doOnExecutionWithContext(context: A, consumer: (A) -> Unit): APromise<T> {
        return doOnExecutionWithContext(context, Consumer(consumer))
    }

    fun <A : Activity> thenWithContextOrReject(context: A, consumer: Consumer<Pair<A, T>>) : APromise<T> {
        this.contextRef = WeakReference(context) //THINK local context

        return then { getContext(contextRef)?.let { it as? A }?.let { activity ->
                consumer.accept(Pair(activity, it))

            } ?: reject(Throwable("APromise - null context"))
        }
    }

    fun <A : Activity> thenWithContextOrReject(context: A, consumer: (Pair<A, T>) -> Unit) : APromise<T> {
        return thenWithContextOrReject(context, Consumer(consumer))
    }

    /** skips this consumer (only) if the context became null */
    fun <A : Activity> thenWithContext(context: A, consumer: Consumer<Pair<A, T>>) : APromise<T> {
        this.contextRef = WeakReference(context) //THINK local context

        return then {
            getContext(contextRef)?.let { it as? A }?.let { activity ->
                consumer.accept(Pair(activity, it))
            } //or skip
        }
    }

    /** skips this consumer (only) if the context became null */
    fun <A : Activity> thenWithContext(context: A, consumer: (Pair<A, T>) -> Unit) : APromise<T> {
        return thenWithContext(context, Consumer(consumer))
    }

    /** skips this consumer (only) if the view or its context became null, or view isn't attached to window */
    fun <V : View> thenWithView(view: V, consumer: Consumer<Pair<V, T>>
            , requireAttachedToWindow: Boolean = true) : APromise<T> {

        val viewRef = WeakReference(view)

        return then {
            viewRef.getIfAlive(requireAttachedToWindow)?.let { v ->
                consumer.accept(Pair(v, it))
            } //or skip
        }
    }

    /** skips this consumer (only) if the context became null */
    fun <V : View> thenWithView(view: V, consumer: (Pair<V, T>) -> Unit
            , requireAttachedToWindow: Boolean = true) : APromise<T> {
        return thenWithView(view, Consumer(consumer), requireAttachedToWindow)
    }

    /** skips this consumer (only) if the view or its context became null, or view isn't attached to window */
    fun <V : View> then(view: V, consumer: Consumer<T>, requireAttachedToWindow: Boolean = true) : APromise<T> {
        return thenWithView(view, { (_, t) ->
            consumer.accept(t)
        }, requireAttachedToWindow)
    }

    /** skips this consumer (only) if the view or its context became null, or view isn't attached to window */
    fun <V : View> then(view: V, consumer: (T) -> Unit, requireAttachedToWindow: Boolean = true) : APromise<T> {
        return then(view, Consumer(consumer), requireAttachedToWindow)
    }

    /** cancels the entire promise if the context became null */
    fun <A : Activity, R> thenAwaitWithContextOrCancel(context: A, function: (Pair<A, T>) -> Promise<R>) : APromise<R> {
        this.contextRef = WeakReference(context) //THINK local context

        return thenAwait {
            getContext(contextRef)?.let { it as? A }?.let { activity ->
                function.invoke(Pair(activity, it))

            } ?: cancelImmediately("APromise - null context")
        }
    }

    /** cancels the entire promise if the context became null */
    fun <R> thenAwaitOrCancel(context: Activity, function: Function<T, Promise<R>>): APromise<R> {
        return thenAwaitWithContextOrCancel(context) { (_, t) ->
            function.apply(t)
        }
    }

    /** cancels the entire promise if the context became null */
    fun <R> thenAwaitOrCancel(context: Activity, function: (T) -> Promise<R>): APromise<R> {
        return thenAwaitOrCancel(context, Function(function))
    }

    /** cancels the entire promise if the view became null */
    fun <V : View, R> thenAwaitWithViewOrCancel(view: V, function: (Pair<V, T>) -> Promise<R>
            , requireAttachedToWindow: Boolean = true) : APromise<R> {
        val viewRef = WeakReference(view)

        return thenAwait {
            viewRef.getIfAlive(requireAttachedToWindow)?.let { v ->
                function.invoke(Pair(v, it))

            } ?: cancelImmediately("APromise - null view or view context, or view is not attached to window")
        }
    }

    /** cancels the entire promise if the view became null or detached */
    fun <V : View> thenWithViewOrCancel(viewRef: WeakReference<V>, requireAttachedToWindow: Boolean = true) : APromise<Pair<T, V>> {
        return thenMap { t ->
            viewRef.getIfAlive(requireAttachedToWindow)?.let { v ->
                Pair(t, v)

            } ?: cancelImmediately("APromise - null view or view context, or view is not attached to window")
        }
    }

    /** cancels the entire promise if the view became null or detached */
    fun <V : View> thenWithViewOrCancel(view: V, requireAttachedToWindow: Boolean = true) : APromise<Pair<T, V>> {
        return thenWithViewOrCancel(WeakReference(view), requireAttachedToWindow)
    }

    /** cancels the entire promise if the view became null or detached */
    fun <V : View, R> thenMapWithViewOrCancel(view: V, function: Function<Pair<T, V>, R>
            , requireAttachedToWindow: Boolean = true) : APromise<R> {
        return thenWithViewOrCancel(view, requireAttachedToWindow).thenMap(function)
    }

    /** cancels the entire promise if the view became null or detached */
    fun <V : View, R> thenMapWithViewOrCancel(view: V, function: (Pair<T, V>) -> R
            , requireAttachedToWindow: Boolean = true) : APromise<R> {
        return thenWithViewOrCancel(view, requireAttachedToWindow).thenMap(function)
    }

    /** cancels the entire promise if the view became null or detached, or 'function' returned null */
    fun <V : View, R> thenMapOrCancelWithViewOrCancel(view: V, function: (Pair<T, V>) -> R?
            , requireAttachedToWindow: Boolean = true) : APromise<R> {
        return thenWithViewOrCancel(view, requireAttachedToWindow).thenMapOrCancel(function)
    }

    /** cancels the entire promise if the view became null or detached */
    fun <V : View> thenViewOrCancel(view: V, requireAttachedToWindow: Boolean = true) : APromise<V> {
        return thenWithViewOrCancel(view, requireAttachedToWindow).thenMap { it.second }
    }

    fun <V : View> delayWithViewOrCancel(view: V, ms: Long, requireAttachedToWindow: Boolean = true) : APromise<Pair<T, V>> {
        val viewRef = WeakReference(view)                                                   //weak ref until execution

        return thenMapOrCancel({ t -> viewRef.takeIfAlive(requireAttachedToWindow)?.let {   //weak ref until delayed
                Pair(t, it)
            }})
            .delay(ms)
            .thenMapOrCancel({ (t, ref) -> ref.getIfAlive(requireAttachedToWindow)?.let {   //ref.get()
                Pair(t, it)
            }})
    }

    fun <V : View> delayOrCancel(view: V, ms: Long, requireAttachedToWindow: Boolean = true) : APromise<T> {
        return delayWithViewOrCancel(view, ms, requireAttachedToWindow)
            .thenMap { it.first }
    }

    /** cancels the entire promise if the reference became null */
    fun <R> thenWithRefOrCancel(ref: WeakReference<R>) : APromise<R> {
        return thenMapOrCancel({ ref.get() }, { "thenWithRefOrCancel(): 'ref' returned null" })
    }

    fun <A : Activity, R> thenAwaitWithContextOrReject(context: A, function: Function<Pair<A, T>, Promise<R>>) : APromise<R> {
        this.contextRef = WeakReference(context) //THINK local context

        return thenAwait { getContext(contextRef)?.let { it as? A }?.let { activity ->
                function.apply(Pair(activity, it))

            } ?: reject(Throwable("Context became null"))
        }
    }

    fun <A : Activity, R> thenAwaitWithContextOrReject(context: A, function: (Pair<A, T>) -> Promise<R>) : APromise<R> {
        return thenAwaitWithContextOrReject(context, Function(function))
    }

    /** skips this consumer (only) if the context became null */
    fun then(context: Activity, consumer: Consumer<T>) : APromise<T> {
        return thenWithContext(context, Consumer { (_, t) ->
            consumer.accept(t)
        })
    }

    /** skips this consumer (only) if the context became null */
    fun then(context: Activity, consumer: (T) -> Unit) : APromise<T> {
        return then(context, Consumer(consumer))
    }

    fun thenOrReject(context: Activity, consumer: Consumer<T>) : APromise<T> {
        return thenWithContextOrReject(context, Consumer { (_, t) ->
            consumer.accept(t)
        })
    }

    fun thenOrReject(context: Activity, consumer: (T) -> Unit) : APromise<T> {
        return thenOrReject(context, Consumer(consumer))
    }

    fun <R> thenAwaitOrReject(context: Activity, function: Function<T, Promise<R>>) : APromise<R> {
        return thenAwaitWithContextOrReject(context, Function { (_, t) ->
            function.apply(t)
        })
    }

    fun <R> thenAwaitOrReject(context: Activity, function: (T) -> Promise<R>) : APromise<R> {
        return thenAwaitOrReject(context, Function(function))
    }

    /** skips this consumer (only) if the context became null */
    fun <A : Activity> catchWithContext(context: A, consumer: Consumer<Pair<A, Throwable>>): APromise<T> {
        this.contextRef = WeakReference(context) //THINK local context. Note: mutable (returns 'this') so context may be overridden

        return catch { error -> getContext(contextRef)?.let { it as? A }?.let {
            consumer.accept(Pair(it, error))
        }}
    }

    /** skips this consumer (only) if the context became null */
    fun <A : Activity> catchWithContext(context: A, consumer: (Pair<A, Throwable>) -> Unit): APromise<T> {
        return catchWithContext(context, Consumer(consumer))
    }

    /** skips this consumer (only) if the context became null */
    fun catch(context: Activity, consumer: Consumer<in Throwable>): APromise<T> {
        return catchWithContext(context, Consumer { (_, e) ->
            consumer.accept(e)
        })
    }

    /** skips this consumer (only) if the context became null */
    fun catch(context: Activity, consumer: (Throwable) -> Unit): APromise<T> {
        return catch(context, Consumer(consumer))
    }

    /** skips this consumer (only) if the context became null */
    fun <A : Activity> catchIgnoreWithContext(context: A, consumer: Consumer<Pair<A, Throwable>>): APromise<Unit> {
        this.contextRef = WeakReference(context) //THINK local context. Note: mutable (returns 'this') so context may be overridden

        return catchIgnore { error -> getContext(contextRef)?.let { it as? A }?.let {
            consumer.accept(Pair(it, error))
        }}
    }

    /** skips this consumer (only) if the context became null */
    fun <A : Activity> catchIgnoreWithContext(context: A, consumer: (Pair<A, Throwable>) -> Unit): APromise<Unit> {
        return catchIgnoreWithContext(context, Consumer(consumer))
    }

    fun <A : Activity> catchIgnore(context: A, consumer: Consumer<Throwable>): APromise<Unit> {
        return catchIgnoreWithContext(context, Consumer { (_, e) ->
            consumer.accept(e)
        })
    }

    fun <A : Activity> catchIgnore(context: A, consumer: (Throwable) -> Unit): APromise<Unit> {
        return catchIgnore(context, Consumer(consumer))
    }


    /*overridden method just for the response type*/
    override fun <R> thenAwait(function: Function<T, Promise<R>>, executeOn: Scheduler?, resumeOn: Scheduler?): APromise<R> {
        return super.thenAwait(function, executeOn, resumeOn) as APromise<R>
    }

    override fun <R> thenAwait(function: Function<T, Promise<R>>): APromise<R> {
        return super.thenAwait(function) as APromise<R>
    }

    override fun <R> thenAwait(function: (T) -> Promise<R>, executeOn: Scheduler?, resumeOn: Scheduler?): APromise<R> {
        return super.thenAwait(function, executeOn, resumeOn) as APromise<R>
    }

    override fun <R> thenAwait(function: (T) -> Promise<R>): APromise<R> {
        return super.thenAwait(function) as APromise<R>
    }

    override fun <R> thenAwaitOrReject(function: (T) -> Promise<R>?, executeOn: Scheduler?, resumeOn: Scheduler?) : APromise<R> {
        return super.thenAwaitOrReject(function, executeOn, resumeOn) as APromise<R>
    }

    override fun <R> thenAwaitOrCancel(function: (T) -> Promise<R>?, executeOn: Scheduler?, resumeOn: Scheduler?) : APromise<R> {
        return super.thenAwaitOrCancel(function, executeOn, resumeOn) as APromise<R>
    }

    override fun <R> thenAwaitOrReject(function: (T) -> Promise<R>?) : APromise<R> {
        return super.thenAwaitOrReject(function) as APromise<R>
    }

    override fun <R> thenAwaitOrCancel(function: (T) -> Promise<R>?) : APromise<R> {
        return super.thenAwaitOrCancel(function) as APromise<R>
    }

    @JvmOverloads
    fun <R> thenAsyncAwait(function: (T) -> Promise<R>
            , executeOn: Scheduler? = Schedulers.computation()
            , resumeOn: Scheduler? = AndroidSchedulers.mainThread()): APromise<R> {
        return super.thenAwait(function, executeOn, resumeOn) as APromise<R>
    }

    override fun <R> thenMap(function: Function<T, R>): APromise<R> {
        return super.thenMap(function) as APromise<R>
    }

    override fun <R> thenMap(function: (T) -> R): APromise<R> {
        return super.thenMap(Function(function)) as APromise<R>
    }

    /** Rejects if 'function' returns null */
    override fun <R> thenMapOrReject(function: (T) -> R?, rejectionExceptionSupplier: () -> Throwable): APromise<R> {
        return super.thenMapOrReject(function, rejectionExceptionSupplier) as APromise<R>
    }

    /** Cancels if 'function' returns null */
    override fun <R> thenMapOrCancel(function: (T) -> R?, withMsg: (() -> String)?): APromise<R> {
        return super.thenMapOrCancel(function, withMsg) as APromise<R>
    }

    /** Cancels if 'function' returns null */
    override fun <R> thenMapOrCancel(function: Function<T, R?>, withMsg: (() -> String)?): APromise<R> {
        return super.thenMapOrCancel(function, withMsg) as APromise<R>
    }

    override fun then(consumer: Consumer<T>): APromise<T> {
        return super.then(consumer) as APromise<T>
    }

    override fun then(consumer: (T) -> Unit): APromise<T> {
        return super.then(Consumer(consumer)) as APromise<T>
    }

   /* override fun thenRun(runnable: Runnable): APromise<T> {
        return super.thenRun(runnable) as APromise<T>
    }

    override fun thenRun(runnable: () -> Unit): APromise<T> {
        return super.thenRun(runnable) as APromise<T>
    }*/

    override fun catch(consumer: Consumer<in Throwable>): APromise<T> {
        return super.catch(consumer) as APromise<T>
    }

    override fun catch(consumer: (Throwable) -> Unit): APromise<T> {
        return super.catch(Consumer(consumer)) as APromise<T>
    }

    override fun catchReturn(function: Function<Throwable, T>) : APromise<T> {
        return super.catchReturn(function) as APromise<T>
    }

    override fun catchReturn(function: (Throwable) -> T) : APromise<T> {
        return super.catchReturn(Function(function)) as APromise<T>
    }

    //TODO untested
    override fun catchResume(function: Function<Throwable, Promise<T>>) : APromise<T> {
        return super.catchResume(function) as APromise<T>
    }

    override fun catchResume(function: (Throwable) -> Promise<T>) : APromise<T> {
        return super.catchResume(Function(function)) as APromise<T>
    }

    fun <A : Activity> catchResumeWithContext(context: A, function: (Pair<A, Throwable>) -> Promise<T>) : APromise<T> {
        val contextRef = WeakReference(context)

        return catchResume { error -> getContext(contextRef)?.let { it as? A }?.let {
            function.invoke(Pair(it, error))
        } ?: reject(Throwable("APromise - null context")) }
    }

    override fun catchIgnore(consumer: Consumer<Throwable>) : APromise<Unit> {
        return super.catchIgnore(consumer) as APromise<Unit>
    }

    override fun catchIgnore(consumer: (Throwable) -> Unit) : APromise<Unit> {
        return super.catchIgnore(Consumer(consumer)) as APromise<Unit>
    }

    override fun catchIgnore() : APromise<Unit> {
        return super.catchIgnore() as APromise<Unit>
    }

    //TODO untested
    override fun catchIgnoreResume(function: Function<Throwable, Promise<Unit>>) : APromise<Unit> {
        return super.catchIgnoreResume(function) as APromise<Unit>
    }

    override fun catchIgnoreResume(function: (Throwable) -> Promise<Unit>) : APromise<Unit> {
        return super.catchIgnoreResume(Function(function)) as APromise<Unit>
    }

    /**for Java, where 'catch' is prohibited as a method name*/
    override fun onError(consumer: Consumer<in Throwable>): APromise<T> {
        return super.onError(consumer) as APromise<T>
    }

    /**for Java, where 'catch' is prohibited as a method name*/
    override fun onError(consumer: (Throwable) -> Unit): APromise<T> {
        return super.onError(Consumer(consumer)) as APromise<T>
    }

    override fun rejectIf(predicate: Predicate<T>, errorSupplier: (T) -> Throwable): APromise<T> {
        return super.rejectIf(predicate, errorSupplier) as APromise<T>
    }

    override fun rejectIf(predicate: (T) -> Boolean, errorSupplier: (T) -> Throwable): APromise<T> {
        return super.rejectIf(Predicate(predicate), errorSupplier) as APromise<T>
    }

    override fun cancelIf(predicate: Predicate<T>, logMessageSupplier: ((T) -> String)?): APromise<T> {
        return super.cancelIf(predicate, logMessageSupplier) as APromise<T>
    }

    override fun cancelIf(predicate: (T) -> Boolean, logMessageSupplier: ((T) -> String)?): APromise<T> {
        return super.cancelIf(Predicate(predicate), logMessageSupplier) as APromise<T>
    }

    fun cancelIf(predicate: (T) -> Boolean): APromise<T> {
        return super.cancelIf(Predicate(predicate),  null) as APromise<T>
    }

    override fun catchIf(predicate: Predicate<Throwable>, consumer: Consumer<Throwable>) : APromise<T> {
        return super.catchIf(predicate, consumer) as APromise<T>
    }

    override fun catchIf(predicate: (Throwable) -> Boolean, consumer: (Throwable) -> Unit) : APromise<T> {
        return super.catchIf(Predicate(predicate), Consumer(consumer)) as APromise<T>
    }

    /**
     * delays (on computation scheduler) and resumes on the calling thread,
     * **unless current thread has no prepared Looper, then resumes on the main thread/Looper !**.
     * Note that the super [Promise.delay] doesn't resumes to the calling thread, and this method aims to fix that
     */
    fun delay(ms: Long): APromise<T> {
        //single's delay() changes to computation scheduler!! See it's implementation
        //fix - resume on current thread
        //THINK create Looper for current thread if doesn't have one yet (instead of using main thread/looper)
        return super.delay(ms, androidSchedulerOrMain()) as APromise<T>
    }

    /**
     * timeouts if hasn't been resolved after 'ms' time and resumes on the calling thread,
     * **unless current thread has no prepared Looper, then resumes on the main thread/Looper !**.
     * Note that the super [Promise.timeout] doesn't resumes to the calling thread, and this method aims to fix that
     */
    fun timeout(ms: Long): APromise<T> {
        //single's timeout() changes to computation scheduler!! See it's implementation
        //fix - resume on current thread
        //THINK create Looper for current thread if doesn't have one yet (instead of using main thread/looper)
        return super.timeout(ms, androidSchedulerOrMain()) as APromise<T>
    }

    override fun thenOn(scheduler: Scheduler, consumer: (T) -> Unit): APromise<T> {
        return super.thenOn(scheduler, consumer) as APromise<T>
    }

    fun thenOnMainThread(consumer: (T) -> Unit): APromise<T> {
        return super.thenOn(AndroidSchedulers.mainThread(), consumer) as APromise<T>
    }

    /** calls 'consumer' when this Promise finishes, with boolean 'isResolved' - true if Promise succeeded, false if failed or cancelled */
    override fun finally(consumer: (Boolean) -> Unit): APromise<T> {
        return super.finally(consumer) as APromise<T>
    }

    /** skips this consumer (only) if the context became null */
    fun <A : Activity> finallyWithContext(context: A, consumer: (Pair<A, Boolean>) -> Unit) : APromise<T> {
        val ref = WeakReference(context)

        return super.finally { resolved ->
            getContext(ref)?.let { consumer.invoke(it to resolved) } //or skip
        } as APromise<T>
    }

    override fun doOnExecution(runnable: Runnable): APromise<T> {
        return super.doOnExecution(runnable) as APromise<T>
    }

    override fun doOnExecution(runnable: () -> Unit): APromise<T> {
        return super.doOnExecution(Runnable(runnable)) as APromise<T>
    }


    companion object {
        private var globalErrorHandler = fun (context: Activity, e: Throwable) {
            Toast.makeText(context, e.message ?: "Unknown API Error", Toast.LENGTH_LONG).show()
        }

        /**
         * Note: catches may receive [Throwable] of type [Promise.SimpleCallbackException] with a nullable [R] as it's 'result'
         */
        @JvmStatic
        fun <R, CALLBACK: Any?> ofCallback(call: (Promise.Companion.SimpleCallback<R>) -> CALLBACK
               , finallyAction: ((CALLBACK) -> Unit)? = null): APromise<R> {
            return from(Promise.ofCallback(call, finallyAction))
        }

        /**
         * Note: catches may receive [Throwable] of type [Promise.SimpleCallbackException] with a nullable [R] as it's 'result'
         */
        @JvmStatic
        fun <R: Any?> ofCallback(call: (Promise.Companion.SimpleCallback<R>) -> Unit): APromise<R> {
            return ofCallback(call, null)
        }

        /**
         * @param supplier if returns null, the condition is not met, regardless of 'predicate'
         * @param predicate if 'supplier' returns not null, AND this predicate returns true, the promise will go forward. Else will fail
         */
        fun <R> ofConditionOrReject(supplier: () -> R?, predicate: (R) -> Boolean) : APromise<R> {
            return APromise(singleOfCondition(supplier, predicate))
        }

        fun <R> ofConditionOrReject(supplier: () -> R?) : APromise<R> {
            return APromise(singleOfCondition(supplier, { true }))
        }

        fun <R, S> ofConditionOrIgnore(supplier: () -> R?, predicate: (R) -> Boolean
                                       , function: (R) -> Promise<S>) : APromise<Unit> {
            return ofConditionOrReject(supplier, predicate)
                    .thenAwait(Function(function))
                    .catchIgnoreUnmetCondition() as APromise<Unit>
        }

        /**
         * @param supplier if returns null, the condition is not met, regardless of 'predicate'
         * @param predicate if 'supplier' returns not null, AND this predicate returns true, the promise will go forward.
         * @param function an async task to run if 'predicate' returned true
         * @param consumer an action to execute if 'predicate' returned false(or 'supplier' returned null) - hence promise got rejected
         * Else will fail - and be ignored(catchReturn)
         */
        fun <R, S> ofConditionOrIgnore(supplier: () -> R?, predicate: (R) -> Boolean
                                       , function: (R) -> Promise<S>, consumer: (Throwable) -> Unit) : APromise<Unit> {
            return ofConditionOrReject(supplier, predicate)
                    .thenAwait(Function(function))
                    .catchIgnoreUnmetCondition(consumer) as APromise<Unit>
        }

        fun <R, S> ofConditionOrIgnore(supplier: () -> R?, function: (R) -> Promise<S>) : APromise<Unit> {
            return ofConditionOrReject(supplier, {true})
                    .thenAwait(Function(function))
                    .catchIgnoreUnmetCondition() as APromise<Unit>
        }

        //TODO errors here stop all promises. A parallel single should not know about the others
        fun all(promises: List<Promise<*>>) : APromise<Unit> {
            return APromise(singleOfZip(promises)).apply {
                passOnErrorHandlers(promises.toList(), this)
            }
        }

        fun <R> runSynchronously(promises: List<Promise<R>>) : APromise<List<R>> {
            return APromise(singleOfSynchronousPromises(promises)).apply {
                passOnErrorHandlers(promises, this)//THINK needed?
            }
        }

        //TODO errors here stop all promises. A parallel single should not know about the others
        fun <R> allResults(promises: List<Promise<R>>) : APromise<List<R>> {
            return APromise(singleOfZipResults(promises)).apply {
                passOnErrorHandlers(promises, this)
            }
        }

        fun all(vararg promises: Promise<*>) : APromise<Unit> {
            return all(promises.asList())
        }

        @JvmStatic
        fun <R> from(promise: Promise<R>): APromise<R> {
            return APromise(promise.single).apply {
                passOnErrorHandlers(listOf(promise), this)
            }
        }

        @JvmStatic
        @JvmOverloads
        fun <R> ofReject(rejectMsg: String = "Promise of default rejection") : APromise<R> {
            return APromise(singleOfCondition<R>({ null }, { false }, rejectMsg))
        }

        fun <R> of(supplier: () -> R) : APromise<R> {
            return APromise(singleOfSupplier(supplier))
        }

        fun <R> ofOrReject(supplier: () -> R?) : APromise<R> {
            return of(Unit)
                    .thenMapOrReject({ supplier.invoke() })
        }

        fun <R> ofOrCancel(supplier: () -> R?
                , withMsg: (() -> String)? = { "thenMapOrCancel(): 'function' returned null" })
                : APromise<R> {
            return of(Unit).thenMapOrCancel({ supplier.invoke() }, withMsg)
        }

        fun <R> ofWeakRefOrCancel(ref: WeakReference<R>) : APromise<R> {
            return ofOrCancel(ref::get)
        }

        /** A 'resolved' promise with initial value */
        fun <R> of(r: R) : APromise<R> {
            return APromise(singleOfCondition({ r }, { true }))
        }

        /** keeps a [WeakReference] of 'r' while until execution and cancels if garbage-collected */
        fun <R> ofOrCancel(r: R) : APromise<R> {
            return ofWeakRefOrCancel(WeakReference(r))
        }

        @JvmStatic
        fun ofDelay(ms: Long): APromise<Unit> {
            //note that this one is different than the parent Promise's, as APromise.delay() adds logic
            return of().delay(ms)
        }

        /** Delays on current thread, or main thread if current thread's [Looper] isn't prepared */
        fun delay(ms: Long, consumer: () -> Unit): Disposable {
            //note that this one is different than the parent Promise's, as APromise.delay() adds logic
            return ofDelay(ms).then { consumer() }.execute()
        }

        fun delayOn(ms: Long, scheduler: Scheduler, consumer: () -> Unit): Disposable {
            //note that this one is different than the parent Promise's, as APromise.delay() adds logic
            return of().delay(ms, scheduler).then { consumer() }.execute()
        }

        @JvmStatic
        fun delayOnMainThread(ms: Long, consumer: () -> Unit): Disposable {
            //note that this one is different than the parent Promise's, as APromise.delay() adds logic
            return delayOn(ms, AndroidSchedulers.mainThread(), consumer)
        }

        /**
         * Runs a task at the end of Android's [Handler] execution queue. Same as calling [APromise.delay] with `ms = 0`
         *
         * This is somewhat similar to JavaScript's `setTimeout(0)`.
         *
         * This is most helpful for UI-related tasks, when you need to run something from a UI callback
         * (e.g. [View.onFinishInflate]), but do it **'non-blocking'** (in this example, a `View`'s size might be
         * measured only **after** the [View.onFinishInflate] callback, so this method/utility is helpful for
         * that case).
         *
         * Detailed explanation: Android's thread [Handler] works with tasks.
         * When posting a task with `0` delay, that task is scheduled to run at the end of
         * the (tasks) execution queue, which is exactly how this method works.
         */
        @JvmStatic
        @JvmOverloads
        fun postAtEndOfExecutionQueue(scheduler: Scheduler = androidSchedulerOrMain()
                , consumer: () -> Unit): Disposable {
            return delayOn(0, scheduler, consumer)
        }

        /**
         * Runs a task at the end of Android's **main** thread / [Handler] execution queue.
         *
         * This is same as calling [delayOnMainThread] with `ms = 0`
         *
         * See [postAtEndOfExecutionQueue]
         */
        @JvmStatic
        fun postAtEndOfMainExecutionQueue(consumer: () -> Unit): Disposable {
            return delayOnMainThread(0, consumer)
        }

        @JvmStatic
        fun delayWhileAlive(view: View, ms: Long, consumer: () -> Unit): Disposable {
            //note that this one is different than the parent Promise's, as APromise.delay() adds logic
            return ofDelay(ms).then { consumer() }.executeWhileAlive(view)
        }

        @JvmStatic
        fun delayWhileAlive(activity: Activity, ms: Long, consumer: () -> Unit): Disposable {
            //note that this one is different than the parent Promise's, as APromise.delay() adds logic
            return ofDelay(ms).then { consumer() }.executeWhileAlive(activity)
        }

        /** keeps a [WeakReference] of 'r' while delaying and cancels if garbage-collected */
        fun <R> ofDelayOrCancel(r: R, ms: Long): APromise<R> {
            return of(WeakReference(r))                                 //weak ref until execution
                .thenMapOrCancel({ it.takeIf { it.get() != null } })    //weak ref until delayed
                .delay(ms)
                .thenMapOrCancel({ it.get() })                          //ref.get()
        }

        /** keeps a [WeakReference] of 'view' while delaying and cancels if garbage-collected */
        fun <V : View> ofDelayOrCancel(view: V, ms: Long, requireAttachedToWindow: Boolean = true): APromise<V> {
            return of(WeakReference(view))                                      //weak ref until execution
                .thenMapOrCancel({ it.takeIfAlive(requireAttachedToWindow) })   //weak ref until delayed
                .delay(ms)
                .thenMapOrCancel({ it.getIfAlive(requireAttachedToWindow) })    //ref.get()
        }

        fun <V : View> ofView(view: V, requireAttachedToWindow: Boolean = true) : APromise<V> {
            return of(WeakReference(view))
                .thenMapOrCancel({ it.getIfAlive(requireAttachedToWindow) })
        }

        fun <V : View> ofViewOrReject(view: V, requireAttachedToWindow: Boolean = true) : APromise<V> {
            return of(WeakReference(view))
                .thenMapOrReject({ it.getIfAlive(requireAttachedToWindow) })
        }

        @JvmStatic
        fun of() : APromise<Unit> {
            return APromise(singleOfCondition({ Unit }, { true }))
        }

        @JvmStatic
        fun on(scheduler: Scheduler) : APromise<Unit> {
            return APromise(Single.just(Unit).observeOn(scheduler))
        }

        /** @param supplier may throw exceptions, which will lead to standard rejection */
        @JvmOverloads
        fun <R> ofAsync(supplier: () -> R
                , executeOn: Scheduler? = null, resumeOn: Scheduler? = null) : APromise<R> {
            return APromise(singleOfAsyncAwait(supplier, executeOn, resumeOn))
        }

        /** @param supplier may throw exceptions, which will lead to standard rejection */
        @JvmOverloads
        fun <R> ofAsyncAwait(supplier: () -> R
                , executeOn: Scheduler? = Schedulers.computation(), resumeOn: Scheduler? = AndroidSchedulers.mainThread()) : APromise<R> {
            return APromise(singleOfAsyncAwait(supplier, executeOn, resumeOn))
        }

        /** @param supplier may throw exceptions, which will lead to standard rejection */
        @JvmOverloads
        fun <R> ofAsyncAwaitOrReject(supplier: () -> R?
                , executeOn: Scheduler = Schedulers.computation(), resumeOn: Scheduler? = AndroidSchedulers.mainThread()) : APromise<R> {
            return on(executeOn).thenMapOrReject({ supplier() })
                .run { resumeOn?.let { thenOn(it){} } ?: this }
        }

        /**
         * Uses a [View]'s [View.post] (or [View.postDelayed]) and converts it to a promise.
         * This is somewhat same as using [APromise.executeWhileAlive] and [APromise.ofDelay]
         * but uses Android/View infrastructure instead:
         * Easily resolve the promise **at end of execution queue** (delay = 0), while breaking
         * if the view is destroyed.
         *
         * NOTICE: does not yet support cancelling the promise if the view gets destroyed before
         *  the action has been executed. Also meaning that [finally] will not work when cancelled
         */
        @JvmStatic
        fun <V : View> post(view: V, delayMs: Long = 0L): APromise<V> {
            val viewRef = WeakReference(view)
            val viewName = view.javaClass.simpleName

            return ofCallback { promiseCallback ->
                val postRunnable = {
                    viewRef.get()
                        ?.let(promiseCallback::onSuccess)
                        ?: promiseCallback.onCancel("${viewName}'s Activity already destroyed")
                }

                viewRef.get()?.let { v ->
                    if (delayMs > 0L) {
                        v.postDelayed(postRunnable, delayMs)
                    } else {
                        v.post(postRunnable)
                    }
                }

                //if viewRef is empty
                ?: promiseCallback.onCancel("${viewName}'s Activity View already destroyed")
            }
        }

        @JvmStatic
        fun setGlobalAutoErrorHandler(handler: (Activity, Throwable) -> Unit) {
            globalErrorHandler = handler
        }
    }
}

private fun <A : Activity> getContext(ref: WeakReference<A>) : A? {
    val context = ref.get()

    context?.let {
        if (it.isDestroyed || it.isFinishing) {
            return null
        }
    }

    return context
}

private fun androidSchedulerOrMain(looperOrNull: Looper? = Looper.myLooper()): Scheduler {
    return looperOrNull?.let(AndroidSchedulers::from) ?: AndroidSchedulers.mainThread()
}

private object DisposedDisposable : Disposable {
    override fun isDisposed() = true
    override fun dispose() {}
}