@file:Suppress("unused")

package uy.kohesive.injekt

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import uy.kohesive.injekt.api.FullTypeReference
import uy.kohesive.injekt.api.InjektFactory
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.TypeReference
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

val Injekt: InjektScope = KestrelInjektScope

inline fun <reified T : Any> InjektFactory.get(key: Any = Unit): T {
    @Suppress("UNCHECKED_CAST")
    return getInstance(object : FullTypeReference<T>() {}.type) as T
}

inline fun <reified T : Any> injectLazy(key: Any = Unit): Lazy<T> = lazy {
    Injekt.get<T>(key)
}

object InjektRegistry {
    private val factories = ConcurrentHashMap<Type, () -> Any>()
    @Volatile
    private var appContext: Context? = null

    fun ensureInitialized(context: Context, okHttpClient: OkHttpClient) {
        val app = context.applicationContext
        appContext = app

        if (app is Application) {
            register(Application::class.java) { app }
        }
        register(Context::class.java) { app }
        register(OkHttpClient::class.java) { okHttpClient }

        val helper = NetworkHelper.create(app, okHttpClient)
        register(NetworkHelper::class.java) { helper }

        val jsonInstance = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
        register(Json::class.java) { jsonInstance }
    }

    fun <T : Any> register(type: Type, factory: () -> T) {
        factories[type] = factory
    }

    fun resolve(forType: Type): Any {
        factories[forType]?.let { return it() }
        if (forType is ParameterizedType) {
            factories[forType.rawType]?.let { return it() }
        }
        if (forType == Application::class.java || forType == Context::class.java) {
            appContext?.let { return it }
        }
        if (forType == NetworkHelper::class.java) {
            val ctx = appContext
            if (ctx != null) return NetworkHelper.create(ctx, null)
        }
        if (forType == Json::class.java) {
            return Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                isLenient = true
                coerceInputValues = true
            }
        }
        error("No Injekt binding registered for type $forType")
    }
}

private object KestrelInjektScope : InjektScope {
    override fun getInstance(forType: Type): Any = InjektRegistry.resolve(forType)
}
