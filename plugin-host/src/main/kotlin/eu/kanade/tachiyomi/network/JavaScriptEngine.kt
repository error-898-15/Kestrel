@file:Suppress("unused", "UNUSED_PARAMETER")

package eu.kanade.tachiyomi.network

import android.content.Context
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JavaScriptEngine(context: Context) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.Default) {
        val result: Any? = quickJs {
            evaluate<Any?>(script)
        }
        result as T
    }
}
