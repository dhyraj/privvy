package com.privvy.app.hook

/**
 * Binder IPC proxy layer (spec §5.1).
 *
 * Rootless limitation: guests share Privvy's Linux UID and Binder calling
 * identity. These proxies cannot spoof the kernel caller; they mediate the
 * client-side framework interfaces that are reachable through reflection:
 *
 *  - PackageManagerProxy    → IPackageManager      (guest package/component/signature identity)
 *  - ActivityManagerProxy   → IActivityManager     (process/task visibility, deny host process control)
 *  - NotificationProxy      → INotificationManager (lock suppression, host-package rewrite)
 *
 * Activity launch routing is handled by PrivvyInstrumentation and
 * GuestComponentRouter rather than an IActivityTaskManager proxy. MediaStore
 * redirection is handled by VirtualContentResolver. Direct sensitive framework
 * APIs that do not pass through these client-side interfaces remain unmediated.
 */
object HookLayer {
    const val DESCRIPTION = "Binder IPC proxy layer — see package docs"
}
