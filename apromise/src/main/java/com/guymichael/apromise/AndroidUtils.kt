package com.guymichael.apromise

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View

object AndroidUtils {

    /**
     * For cases when 'cont' can be also [ContextWrapper] and not an Activity.
     * @param context
     * @return
     */
    fun getActivity(context: Context): Activity? {
        return when (context) {
            is Activity -> context
            is ContextWrapper -> context.baseContext?.let(::getActivity)
            else -> null
        }
    }

    fun getActivity(view: View): Activity? {
        return view.context?.let(::getActivity)
    }

}