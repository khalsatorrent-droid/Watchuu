package com.watchuu.musicbox.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bridge for calling a suspend provider function from the settings UI's worker thread. */
object Async {
    fun <T> runBlocking(timeoutMs: Long = 45000, block: suspend () -> T): T {
        var outcome: Result<T>? = null
        val latch = CountDownLatch(1)
        block.startCoroutine(
            Continuation(EmptyCoroutineContext) { result ->
                outcome = result
                latch.countDown()
            }
        )
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return outcome?.getOrThrow() ?: throw IllegalStateException("provider call timed out")
    }
}
