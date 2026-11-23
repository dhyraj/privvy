package com.privvy.app.hook

/**
 * Marker for the Binder IPC proxy layer (spec §5.1).
 *
 * Each proxy in this package wraps a client-side Binder interface obtained via
 * ServiceManager / Context services and rewrites guest package/component
 * targets into Privvy stub components before forwarding to system_server:
 *
 *  - ActivityManagerProxy   → IActivityTaskManager  (startActivity routing, stub mapping)
 *  - PackageManagerProxy    → IPackageManager       (query/getPackageInfo + FR-04 signature spoofing)
 *  - NotificationProxy      → INotificationManager  (FR-09 masking/suppression)
 *  - ContentProviderProxy   → IContentProvider      (FR-06 MediaStore scoped-storage redirection)
 *  - AudioServiceProxy      → IAudioService
 *
 * Milestone 1 starts with ActivityManagerProxy (needed for guest activity
 * launching). The rest land in Milestone 2.
 */
object HookLayer {
    const val DESCRIPTION = "Binder IPC proxy layer — see package docs"
}
