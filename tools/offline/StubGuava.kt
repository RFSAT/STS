package com.google.common.util.concurrent

class ListenableFuture<T> {
    fun addListener(r: Runnable, e: java.util.concurrent.Executor) {}
    fun get(): T = @Suppress("UNCHECKED_CAST") (Any() as T)
}
