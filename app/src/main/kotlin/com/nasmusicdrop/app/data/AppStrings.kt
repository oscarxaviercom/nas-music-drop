package com.nasmusicdrop.app.data

import android.content.Context
import androidx.annotation.StringRes

object AppStrings {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(@StringRes resId: Int, vararg formatArgs: Any): String {
        val ctx = appContext ?: return ""
        return if (formatArgs.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *formatArgs)
    }
}
