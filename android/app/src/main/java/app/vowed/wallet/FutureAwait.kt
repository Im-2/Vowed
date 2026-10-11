package app.vowed.wallet

import com.solana.mobilewalletadapter.common.util.NotifyOnCompleteFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Waits for a Mobile Wallet Adapter library future without blocking a thread.
 *
 * A future the LIBRARY cancels (the wallet app died or closed the session, so the scenario shuts down and cancels every request in flight) makes `get()` throw a
 * `java.util.concurrent.CancellationException`, which is the same class as a coroutine's own cancellation. Passed on as it is, it silently ended the wallet
 * visit and left the screen on "Waiting for your wallet" for ever (seen on the emulator when the wallet app was killed). It is turned into an
 * [IOException] that says the wallet closed the session; only a cancellation of the caller itself (Cancel, Try again, a time-out) stays a cancellation.
 */
suspend fun <T> awaitFuture(f: NotifyOnCompleteFuture<T>): T = suspendCancellableCoroutine { cont ->
    f.notifyOnComplete { done ->
        if (!cont.isActive) return@notifyOnComplete
        try {
            cont.resume(done.get())
        } catch (e: java.util.concurrent.CancellationException) {
            cont.resumeWithException(IOException("session closed: the wallet closed the connection before it answered (the request was cancelled)", e))
        } catch (e: Throwable) {
            cont.resumeWithException(e)
        }
    }
    cont.invokeOnCancellation { runCatching { f.cancel(true) } }
}
