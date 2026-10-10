package com.localtv.viewer.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReadAheadInputStreamTest {
    @Test fun tinyConsumerReadsDoNotProduceTinyRemoteReadsOrRunPastRange() {
        val executor = Executors.newSingleThreadExecutor()
        val original = ByteArray(7000) { (it % 251).toByte() }
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        try {
            ReadAheadInputStream(1234, 3000, executor, 1024) { target, position, count ->
                calls.add(position to count); original.copyInto(target, 0, position.toInt(), position.toInt() + count); count
            }.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val block = ByteArray(13)
                while (true) { val n = stream.read(block, 0, block.size); if (n < 0) break; output.write(block, 0, n) }
                assertArrayEquals(original.copyOfRange(1234, 4234), output.toByteArray())
                assertEquals(-1, stream.read())
            }
            assertEquals(listOf(1234L to 1024, 2258L to 1024, 3282L to 952), calls)
        } finally { executor.shutdownNow() }
    }
    @Test fun partialServerReadsStillProduceCompleteSequence() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ReadAheadInputStream(200, 1000, executor, 128) { target, position, count ->
                val n = minOf(count, 47); repeat(n) { target[it] = ((position + it) % 251).toByte() }; n
            }.use { stream -> assertArrayEquals(ByteArray(1000) { ((200 + it) % 251).toByte() }, stream.readBytes()) }
        } finally { executor.shutdownNow() }
    }
    @Test fun emptyRangeDoesNotTouchRemoteFile() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ReadAheadInputStream(900, 0, executor) { _, _, _ -> fail("EOF must not issue a speculative read"); 0 }.use {
                assertEquals(0, it.read(ByteArray(1), 0, 0)); assertEquals(-1, it.read())
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun closeInterruptsBlockedPrefetch() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val interrupted = CountDownLatch(1)
        try {
            val stream = ReadAheadInputStream(0, 1000, executor) { _, _, _ ->
                entered.countDown()
                try { CountDownLatch(1).await(); 0 } catch (_: InterruptedException) { interrupted.countDown(); throw java.io.IOException("Cancelled") }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); stream.close()
            assertTrue("Closing a stream must cancel prefetch", interrupted.await(2, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
