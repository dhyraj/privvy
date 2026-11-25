package com.privvy.app.hook

object GuestBinderPolicy {

    private val deniedProcessControl = setOf(
        "killBackgroundProcesses",
        "forceStopPackage",
        "restartPackage",
        "crashApplication",
        "profileControl",
        "setProcessForeground",
        "handleAppCrash",
    )

    fun isDeniedProcessControl(method: String): Boolean = method in deniedProcessControl

    fun isVisibleProcess(
        processName: String,
        currentProcess: String,
        spaceId: Int,
        hostPackage: String,
    ): Boolean =
        processName == currentProcess ||
            processName == hostPackage ||
            processName.endsWith(":v$spaceId")

    fun rewritePackage(value: String?, guestPackages: Set<String>, hostPackage: String): String? =
        if (value != null && value in guestPackages) hostPackage else value

    fun suppressNotification(locked: Boolean): Boolean = locked
}