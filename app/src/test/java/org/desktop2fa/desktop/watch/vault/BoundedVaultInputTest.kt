package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

class BoundedVaultInputTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun exactLimitAndOneByteBelowAreReadCompletelyAndClosed() {
        for (size in listOf(Vault.MAX_VAULT_SIZE - 1, Vault.MAX_VAULT_SIZE)) {
            val input = TrackingInput(size)
            val bytes = readVaultBytes(input)
            assertEquals(size, bytes.size)
            assertEquals(0x5a.toByte(), bytes.last())
            assertEquals(size, input.consumed)
            assertTrue(input.closed)
        }
    }

    @Test
    fun oversizedOrGrowingInputStopsAfterOneSentinelDespiteStaleSizeHint() {
        for (size in listOf(Vault.MAX_VAULT_SIZE + 1, Vault.MAX_VAULT_SIZE * 2)) {
            val input = TrackingInput(size, sizeHint = 1)
            assertEquals(1, input.available())
            assertThrows(UnsupportedFormatException::class.java) { readVaultBytes(input) }
            assertEquals(Vault.MAX_VAULT_SIZE + 1, input.consumed)
            assertTrue(input.closed)
        }
        val incremental = TrackingInput(100, chunkSize = 2, sizeHint = 0)
        assertThrows(UnsupportedFormatException::class.java) { readVaultBytes(incremental, maxBytes = 8) }
        assertEquals(9, incremental.consumed)
        assertTrue(incremental.closed)
    }

    @Test
    fun readExceptionCannotReturnPartialDataAndStillClosesTheStream() {
        val input = TrackingInput(100, chunkSize = 2, failAfter = 4)
        assertThrows(IOException::class.java) { readVaultBytes(input, maxBytes = 8) }
        assertEquals(4, input.consumed)
        assertTrue(input.closed)
    }

    @Test
    fun emptyShortAndZeroProgressBulkReadsAreHandledWithoutSpinning() {
        for (size in listOf(0, 1, 3)) {
            val input = TrackingInput(size, zeroFirstBulkRead = true)
            assertEquals(size, readVaultBytes(input, maxBytes = 3).size)
            assertTrue(input.closed)
        }
        val overEmptyLimit = TrackingInput(1)
        assertThrows(UnsupportedFormatException::class.java) { readVaultBytes(overEmptyLimit, maxBytes = 0) }
        assertEquals(1, overEmptyLimit.consumed)
        assertTrue(overEmptyLimit.closed)
    }

    @Test
    fun invalidInternalLimitCannotOverflowTheSentinelOrLeakTheStream() {
        for (limit in listOf(-1, Int.MAX_VALUE)) {
            val input = TrackingInput(1)
            assertThrows(IllegalArgumentException::class.java) { readVaultBytes(input, limit) }
            assertTrue(input.closed)
            assertEquals(0, input.consumed)
        }
    }

    @Test
    fun oversizedAndTruncatedVaultFilesRemainUntouchedAfterLoadFailure() {
        val oversized = folder.newFile("synthetic-oversized.d2fa")
        RandomAccessFile(oversized, "rw").use { it.setLength(Vault.MAX_VAULT_SIZE + 1L) }
        assertThrows(UnsupportedFormatException::class.java) { Vault.load(oversized, "synthetic password") }
        assertEquals(Vault.MAX_VAULT_SIZE + 1L, oversized.length())
        val truncated = folder.newFile("synthetic-truncated.d2fa").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val before = truncated.readBytes()
        assertThrows(UnsupportedFormatException::class.java) { Vault.load(truncated, "synthetic password") }
        assertArrayEquals(before, truncated.readBytes())
    }

    @Test
    fun loaderBoundsARealFileThatGrowsAfterItsMetadataCheck() {
        val file = folder.newFile("synthetic-growing.d2fa").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var consumed = 0
        var closed = false
        assertThrows(UnsupportedFormatException::class.java) {
            Vault.load(file, "synthetic password") { opened ->
                // This callback runs only after the initial File.length() check.
                assertEquals(3L, opened.length())
                RandomAccessFile(opened, "rw").use { it.setLength(Vault.MAX_VAULT_SIZE * 2L) }
                val actual = opened.inputStream()
                object : InputStream() {
                    override fun read(): Int = actual.read().also { if (it >= 0) consumed += 1 }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        actual.read(buffer, offset, length).also { if (it > 0) consumed += it }
                    override fun close() { closed = true; actual.close() }
                }
            }
        }
        assertTrue(consumed <= Vault.MAX_VAULT_SIZE + 1)
        assertTrue(closed)
        assertEquals(Vault.MAX_VAULT_SIZE * 2L, file.length())
        file.inputStream().use { assertArrayEquals(byteArrayOf(1, 2, 3), byteArrayOf(it.read().toByte(), it.read().toByte(), it.read().toByte())) }
    }

    @Test
    fun loaderReportsReadFailureWithoutReturningAPartialVault() {
        val file = folder.newFile("synthetic-read-error.d2fa").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val original = file.readBytes()
        val input = TrackingInput(100, chunkSize = 2, failAfter = 4)
        assertThrows(VaultIOException::class.java) { Vault.load(file, "synthetic password") { input } }
        assertTrue(input.closed)
        assertArrayEquals(original, file.readBytes())
    }

    private class TrackingInput(
        private val size: Int,
        private val chunkSize: Int = DEFAULT_BUFFER_SIZE,
        private val sizeHint: Int = 0,
        private val failAfter: Int? = null,
        private var zeroFirstBulkRead: Boolean = false,
    ) : InputStream() {
        var consumed = 0
            private set
        var closed = false
            private set

        override fun available() = sizeHint

        override fun read(): Int {
            if (failAfter != null && consumed >= failAfter) throw IOException("synthetic read failure")
            if (consumed >= size) return -1
            consumed += 1
            return 0x5a
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (zeroFirstBulkRead) { zeroFirstBulkRead = false; return 0 }
            if (failAfter != null && consumed >= failAfter) throw IOException("synthetic read failure")
            if (consumed >= size) return -1
            val count = minOf(length, chunkSize, size - consumed, (failAfter ?: size) - consumed)
            buffer.fill(0x5a.toByte(), offset, offset + count)
            consumed += count
            return count
        }

        override fun close() { closed = true }
    }
}
