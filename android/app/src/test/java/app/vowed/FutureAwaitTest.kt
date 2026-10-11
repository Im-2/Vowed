package app.vowed

import app.vowed.wallet.WalletErrors
import app.vowed.wallet.awaitFuture
import com.solana.mobilewalletadapter.common.util.NotifyOnCompleteFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** A wallet that dies must end the visit with a clear failure, never leave the screen waiting for ever. */
class FutureAwaitTest {
    private class FakeFuture<T> : NotifyOnCompleteFuture<T> {
        private var cb: NotifyOnCompleteFuture.OnCompleteCallback<in NotifyOnCompleteFuture<T>>? = null
        var result: T? = null
        var failure: Throwable? = null
        var cancelled = false
        var cancelCalls = 0
        override fun notifyOnComplete(c: NotifyOnCompleteFuture.OnCompleteCallback<in NotifyOnCompleteFuture<T>>) { cb = c }
        fun complete() { cb?.onComplete(this) }
        override fun cancel(b: Boolean): Boolean { cancelCalls++; return true }
        override fun isCancelled() = cancelled
        override fun isDone() = true
        override fun get(): T {
            if (cancelled) throw java.util.concurrent.CancellationException()
            failure?.let { throw ExecutionException(it) }
            return result!!
        }
        override fun get(t: Long, u: TimeUnit): T = get()
    }

    @Test fun aResultIsReturned() = runBlocking {
        val f = FakeFuture<String>().also { it.result = "ok" }
        val job = launch { assertEquals("ok", awaitFuture(f)) }
        delay(20); f.complete(); job.join()
    }

    @Test fun aFutureTheLibraryCancelsBecomesAWalletClosedFailureNotASilentCancellation() {
        val f = FakeFuture<String>().also { it.cancelled = true }
        var thrown: Throwable? = null
        runBlocking {
            val job = launch { try { awaitFuture(f) } catch (e: Throwable) { thrown = e } }
            delay(20); f.complete(); job.join()
        }
        assertTrue("an IOException, not a CancellationException: $thrown", thrown is IOException && thrown !is CancellationException)
        assertTrue(WalletErrors.isSessionClosed(thrown!!.message))
        assertEquals(WalletErrors.Kind.SessionClosed, WalletErrors.classify(WalletErrors.flatten(thrown), null))
    }

    @Test fun aWalletErrorKeepsItsOwnException() {
        val f = FakeFuture<String>().also { it.failure = IllegalStateException("-1/authorization request failed") }
        var thrown: Throwable? = null
        runBlocking {
            val job = launch { try { awaitFuture(f) } catch (e: Throwable) { thrown = e } }
            delay(20); f.complete(); job.join()
        }
        assertTrue(thrown is ExecutionException)
    }

    @Test fun cancellingTheCallerCancelsTheLibraryFuture() = runBlocking {
        val f = FakeFuture<String>()
        val job = launch { awaitFuture(f) }
        delay(20); job.cancel(); job.join()
        assertEquals(1, f.cancelCalls)
    }
}
