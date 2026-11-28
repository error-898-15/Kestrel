@file:Suppress("unused")

package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import rx.Observable
import rx.Subscriber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) {
                continuation.resumeWithException(e)
            }
        }
    })
    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }
}

suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code)
    }
    return response
}

fun Call.asObservable(): Observable<Response> = Observable.create { subscriber: Subscriber<in Response> ->
    val call = clone()
    try {
        val response = call.execute()
        if (!subscriber.isUnsubscribed) {
            subscriber.onNext(response)
            subscriber.onCompleted()
        } else {
            response.close()
        }
    } catch (t: Throwable) {
        if (!subscriber.isUnsubscribed) {
            subscriber.onError(t)
        }
    }
}

fun Call.asObservableSuccess(): Observable<Response> = asObservable().doOnNext { response ->
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code)
    }
}
