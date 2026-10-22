package com.betterstreamflix.app.remotefind

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * Injects Remote Find keyboard events into the active TV Search surface.
 * A generation counter cancels delayed applies after [detach].
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
    private val generation = AtomicInteger(0)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun attach(target: Target) {
        generation.incrementAndGet()
        targetRef = WeakReference(target)
    }

    fun detach(target: Target) {
        if (targetRef?.get() === target) {
            generation.incrementAndGet()
            targetRef = null
        }
    }

    fun setQuery(text: String) {
        ensureSearchVisible()
        val gen = generation.get()
        val apply = Runnable {
            if (gen != generation.get()) return@Runnable
            targetRef?.get()?.remoteSetQuery(text)
        }
        targetRef?.get()?.let { apply.run() }
            ?: mainHandler.postDelayed(apply, 350)
    }

    fun appendQuery(text: String) {
        ensureSearchVisible()
        val gen = generation.get()
        val apply = Runnable {
            if (gen != generation.get()) return@Runnable
            targetRef?.get()?.remoteAppendQuery(text)
        }
        targetRef?.get()?.let { apply.run() }
            ?: mainHandler.postDelayed(apply, 350)
    }

    fun backspace() {
        ensureSearchVisible()
        val gen = generation.get()
        val apply = Runnable {
            if (gen != generation.get()) return@Runnable
            targetRef?.get()?.remoteBackspace()
        }
        targetRef?.get()?.let { apply.run() }
            ?: mainHandler.postDelayed(apply, 350)
    }

    fun submit() {
        ensureSearchVisible()
        val gen = generation.get()
        val apply = Runnable {
            if (gen != generation.get()) return@Runnable
            targetRef?.get()?.remoteSubmit()
        }
        targetRef?.get()?.let { apply.run() }
            ?: mainHandler.postDelayed(apply, 450)
    }

    fun ensureSearchVisible() {
        RemoteFindNavBridge.openSearch()
        targetRef?.get()?.remoteEnsureSearchVisible()
    }
}
