package com.privvy.app.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.LayoutInflater

/**
 * Context adjacency for a guest Activity.
 *
 * The host's base Context names `com.privvy.app` and loads classes through the
 * host PathClassLoader. Guest APKs (especially AppCompat-based ones) bundle
 * their own library classes, so inflation must resolve through the guest
 * ClassLoader and guest Resources. This wrapper delegates all services to the
 * host base context except the identities that must be guest-scoped.
 */
class GuestContext(
    base: Context,
    private val guestResources: Resources,
    private val guestPackageName: String,
    private val guestClassLoader: ClassLoader,
) : ContextWrapper(base) {

    private val guestInflater: LayoutInflater by lazy {
        LayoutInflater.from(base).cloneInContext(this)
    }

    override fun getResources(): Resources = guestResources

    override fun getAssets(): AssetManager = guestResources.assets

    override fun getClassLoader(): ClassLoader = guestClassLoader

    override fun getPackageName(): String = guestPackageName

    override fun getOpPackageName(): String = guestPackageName

    override fun getTheme(): Resources.Theme = guestResources.newTheme()

    override fun getSystemService(name: String): Any? =
        if (name == Context.LAYOUT_INFLATER_SERVICE) guestInflater
        else super.getSystemService(name)
}
