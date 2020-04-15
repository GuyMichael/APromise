package com.guymichael.apromise

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import java.lang.ref.WeakReference

class ViewUtils { companion object {

    fun <V : View> waitForDetach(view: V): APromise<V> {
        val viewRef = WeakReference(view)

        return APromise.ofCallback<V, View.OnAttachStateChangeListener>({ promiseCallback ->
            (object: View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this) //THINK needed here as well as finally?
                    promiseCallback.onSuccess(v as V)
                }

            }).also { view.addOnAttachStateChangeListener(it) }

            //unregister (on errors or cancel mainly)
        }) {
            viewRef.get()?.removeOnAttachStateChangeListener(it)
        }
    }

    fun <A : Activity> waitForDestroy(activity: A): APromise<A> {
        val activityRef = WeakReference(activity)

        return APromise.ofWeakRefOrCancel(activityRef)
            .thenAwait { a ->
                APromise.ofCallback<A, OnActivityDestroyedListener<A>>({ promiseCallback ->
                    (object: OnActivityDestroyedListener<A>(a) {
                        override fun onDestroyed(activity: A) {
                            //release callback now as cancel() takes time
                            activity.application?.unregisterActivityLifecycleCallbacks(this)
                            promiseCallback.onSuccess(activity)
                        }

                    }).also { a.application?.registerActivityLifecycleCallbacks(it) }

                    //unregister (on errors or cancel mainly)
                }) {
                    activityRef.get()?.application?.unregisterActivityLifecycleCallbacks(it)
                }
            }
    }





    fun isAlive(view: View, requireAttachedToWindow: Boolean): Boolean {
        return view.context != null && ( !requireAttachedToWindow || ViewCompat.isAttachedToWindow(view))
    }
}}














private abstract class OnActivityDestroyedListener<A: Activity>(context: A)
    : Application.ActivityLifecycleCallbacks {

    val activityClass = context.javaClass

    final override fun onActivityPaused(activity: Activity?) {}
    final override fun onActivityResumed(activity: Activity?) {}
    final override fun onActivityStarted(activity: Activity?) {}
    final override fun onActivitySaveInstanceState(activity: Activity?, outState: Bundle?) {}
    final override fun onActivityStopped(activity: Activity?) {}
    final override fun onActivityCreated(activity: Activity?, savedInstanceState: Bundle?) {}
    final override fun onActivityDestroyed(activity: Activity?) {
        if (activity != null && activity.javaClass == activityClass) {
            (activity as? A)?.let(::onDestroyed)
        }
    }

    abstract fun onDestroyed(activity: A)
}