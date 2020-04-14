package com.guymichael.apromise

import android.view.View
import java.lang.ref.WeakReference

fun <V : View> V.takeIfAlive(requireAttachedToWindow: Boolean): V? {
    return this.takeIf { ViewUtils.isAlive(it, requireAttachedToWindow) }
}

fun <V : View> WeakReference<V>.isAlive(requireAttachedToWindow: Boolean): Boolean {
    return get()?.let { ViewUtils.isAlive(it, requireAttachedToWindow) } ?: false
}

fun <V : View> WeakReference<V>.getIfAlive(requireAttachedToWindow: Boolean): V? {
    return this.get()?.takeIf { ViewUtils.isAlive(it, requireAttachedToWindow) }
}

fun <V : View> WeakReference<V>.takeIfAlive(requireAttachedToWindow: Boolean): WeakReference<V>? {
    return this.takeIf { it.isAlive(requireAttachedToWindow) }
}
