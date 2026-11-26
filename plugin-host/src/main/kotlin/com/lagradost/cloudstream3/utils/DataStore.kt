// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.CloudStreamApp

object DataStore {
    inline fun <reified T : Any> getKey(path: String): T? =
        CloudStreamApp.getKey(path, T::class.java)

    inline fun <reified T : Any> getKey(folder: String, path: String): T? =
        CloudStreamApp.getKey(folder, path, T::class.java)

    fun <T> setKey(path: String, value: T) = CloudStreamApp.setKey(path, value)
    fun <T> setKey(folder: String, path: String, value: T) = CloudStreamApp.setKey(folder, path, value)
    fun removeKey(path: String) = CloudStreamApp.removeKey(path)
    fun removeKey(folder: String, path: String) = CloudStreamApp.removeKey(folder, path)
}
