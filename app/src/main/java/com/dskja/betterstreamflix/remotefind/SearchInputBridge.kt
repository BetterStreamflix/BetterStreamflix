package com.dskja.betterstreamflix.remotefind

import java.lang.ref.WeakReference

/**
 * Injects Remote Find keyboard events into the active TV Search surface.
 */
object SearchInputBridge {
    interface Target {
        fun remoteSetQuery(text: String)
        fun remoteAppendQuery(text: String)
        fun remoteBackspace()
        fun remoteSubmit()
        fun remoteEnsureSearchVisible()
    }

    @Volatile
    private var targetRef: WeakReference<Target>? = null

    fun attach(target: Target) {
        targetRef = WeakReference(target)
    }

    fun detach(target: Target) {
        if (targetRef?.get() === target) {
            targetRef = null
        }
    }

    fun setQuery(text: String) {
        ensureSearchVisible()
        // Fragment may attach a frame later after navigation.
        val apply = Runnable {
            targetRef?.get()?.remoteSetQuery(text)
        }
        targetRef?.get()?.let { apply.run() }
            ?: android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(apply, 350)
    }

    fun appendQuery(text: String) {
        ensureSearchVisible()
        val apply = Runnable {
            targetRef?.get()?.remoteAppendQuery(text)
        }
        targetRef?.get()?.let { apply.run() }
            ?: android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(apply, 350)
    }

    fun backspace() {
        ensureSearchVisible()
        targetRef?.get()?.remoteBackspace()
            ?: android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                targetRef?.get()?.remoteBackspace()
            }, 350)
    }

    fun submit() {
        ensureSearchVisible()
        val apply = Runnable {
            targetRef?.get()?.remoteSubmit()
        }
        targetRef?.get()?.let { apply.run() }
            ?: android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(apply, 450)
    }

    fun ensureSearchVisible() {
        RemoteFindNavBridge.openSearch()
        targetRef?.get()?.remoteEnsureSearchVisible()
    }
}
