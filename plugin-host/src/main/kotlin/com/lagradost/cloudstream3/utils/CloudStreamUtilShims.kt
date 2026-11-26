// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.annotation.StringRes

class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()
    val size: Int get() = observers.size

    operator fun plusAssign(observer: (T) -> Unit) {
        synchronized(observers) {
            observers.add(observer)
        }
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        synchronized(observers) {
            observers.remove(observer)
        }
    }

    operator fun invoke(value: T) {
        val snapshot = synchronized(observers) { observers.toList() }
        for (observer in snapshot) {
            runCatching { observer(value) }
        }
    }
}

class EmptyEvent {
    private val observers = mutableSetOf<Runnable>()
    val size: Int get() = observers.size

    operator fun plusAssign(observer: Runnable) {
        synchronized(observers) {
            observers.add(observer)
        }
    }

    operator fun minusAssign(observer: Runnable) {
        synchronized(observers) {
            observers.remove(observer)
        }
    }

    operator fun invoke() {
        val snapshot = synchronized(observers) { observers.toList() }
        for (observer in snapshot) {
            runCatching { observer.run() }
        }
    }
}

sealed class UiText {
    companion object {
        const val TAG = "UiText"
    }

    data class DynamicString(val value: String) : UiText() {
        override fun toString(): String = value
    }

    class StringResource(
        @StringRes val resId: Int,
        val args: List<Any>
    ) : UiText() {
        override fun toString(): String = "resId=$resId args=$args"
        override fun equals(other: Any?): Boolean =
            other is StringResource && resId == other.resId && args == other.args
        override fun hashCode(): Int = 31 * resId + args.hashCode()
    }

    fun asStringNull(context: Context?): String? = runCatching {
        asString(context ?: return null)
    }.getOrNull()

    fun asString(context: Context): String = when (this) {
        is DynamicString -> value
        is StringResource -> {
            val str = context.getString(resId)
            if (args.isEmpty()) str else str.format(*args.map {
                when (it) {
                    is UiText -> it.asString(context)
                    else -> it
                }
            }.toTypedArray())
        }
    }
}

fun txt(value: String): UiText = UiText.DynamicString(value)

@JvmName("txtNull")
fun txt(value: String?): UiText? = value?.let { UiText.DynamicString(it) }

fun txt(@StringRes resId: Int, vararg args: Any): UiText =
    UiText.StringResource(resId, args.toList())
