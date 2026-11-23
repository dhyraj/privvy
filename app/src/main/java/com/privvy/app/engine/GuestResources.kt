package com.privvy.app.engine

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources

object GuestResources {
    fun build(context: Context, apkPaths: List<String>): Resources {
        val am = AssetManager::class.java.getDeclaredConstructor().newInstance() as AssetManager
        val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        addAssetPath.isAccessible = true
        // Framework resources first, so android:* attributes/layouts resolve.
        listOf("/system/framework/framework-res.apk").forEach { path ->
            try { addAssetPath.invoke(am, path) } catch (_: Throwable) { }
        }
        for (path in apkPaths) {
            try { addAssetPath.invoke(am, path) } catch (_: Throwable) { }
        }
        return Resources(am, context.resources.displayMetrics, context.resources.configuration)
    }

    fun attach(activity: Activity, resources: Resources, themeResId: Int) {
        setField(activity, "mResources", resources)
        val theme = resources.newTheme()
        if (themeResId != 0) {
            try { theme.applyStyle(themeResId, true) } catch (_: Throwable) { }
        }
        // Leave the activity/window theme alone so the stub's host theme applies.
        setField(activity, "mTheme", theme)
        if (themeResId != 0) setField(activity, "mThemeResource", themeResId)
        val base = activity.baseContext
        if (base != null) setField(base, "mResources", resources)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
    }
}
