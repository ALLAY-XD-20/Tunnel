package com.pawan.tunnel

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object TunnelExecutors {
    private val counter = AtomicInteger()
    private val factory = ThreadFactory { r ->
        Thread(r, "tunnel-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    val pool: ExecutorService = Executors.newFixedThreadPool(8, factory)
    val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2, factory)
}
