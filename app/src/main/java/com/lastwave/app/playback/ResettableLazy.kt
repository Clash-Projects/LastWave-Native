package com.lastwave.app.playback

/** A lazy resource whose owner can discard it after release and create it on the next access. */
internal class ResettableLazy<T : Any>(private val initializer: () -> T) : Lazy<T> {
    @Volatile private var instance: T? = null

    override val value: T
        get() = instance ?: synchronized(this) {
            instance ?: initializer().also { instance = it }
        }

    override fun isInitialized(): Boolean = instance != null

    /** The resource owner must finish releasing the old value before resetting it. */
    fun reset() = synchronized(this) {
        instance = null
    }
}
