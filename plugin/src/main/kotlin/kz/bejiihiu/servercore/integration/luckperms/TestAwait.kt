package kz.bejiihiu.servercore.integration.luckperms

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * test seam only: joins an async result on the calling thread. plugin code
 * must use the futures directly (folia region threads never block); this
 * exists so tests and offline tooling can await a result without wiring a
 * full scheduler.
 */
fun <T> CompletableFuture<T>.awaitCompleted(seconds: Long = 5L): T = get(seconds, TimeUnit.SECONDS)
