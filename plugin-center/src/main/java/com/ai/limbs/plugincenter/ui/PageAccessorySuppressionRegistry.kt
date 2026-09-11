package com.ai.limbs.plugincenter.ui

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PageAccessorySuppressionKey(
    val ownerPluginId: String,
    val screenId: String
)

internal object PageAccessorySuppressionRegistry {
    private val lock = Any()
    private val leaseCounts = mutableMapOf<PageAccessorySuppressionKey, Int>()
    private val mutableSuppressedPages = MutableStateFlow<Set<PageAccessorySuppressionKey>>(emptySet())

    val suppressedPages: StateFlow<Set<PageAccessorySuppressionKey>> =
        mutableSuppressedPages.asStateFlow()

    fun acquire(ownerPluginId: String, screenId: String): AutoCloseable {
        val key = PageAccessorySuppressionKey(ownerPluginId.trim(), screenId.trim())
        require(key.ownerPluginId.isNotEmpty() && key.screenId.isNotEmpty())
        synchronized(lock) {
            leaseCounts[key] = (leaseCounts[key] ?: 0) + 1
            mutableSuppressedPages.value = leaseCounts.keys.toSet()
        }
        var closed = false
        return AutoCloseable {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    val next = (leaseCounts[key] ?: 1) - 1
                    if (next <= 0) leaseCounts.remove(key) else leaseCounts[key] = next
                    mutableSuppressedPages.value = leaseCounts.keys.toSet()
                }
            }
        }
    }
}

internal class PageAccessorySuppressionLeaseView(
    context: Context,
    private val ownerPluginId: String,
    private val screenId: String
) : View(context) {
    private var lease: AutoCloseable? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (lease == null) lease = PageAccessorySuppressionRegistry.acquire(ownerPluginId, screenId)
    }

    override fun onDetachedFromWindow() {
        lease?.close()
        lease = null
        super.onDetachedFromWindow()
    }
}
