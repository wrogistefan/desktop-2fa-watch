package org.desktop2fa.transfer

/** Isolated Watch PoC binding. No full-key test method; no vault access. */
object NativeCore {
    init { System.loadLibrary("d2fa_transfer") }
    external fun create(role: Int, sid: ByteArray, wid: ByteArray, code: ByteArray): Long
    external fun message(handle: Long): ByteArray?
    external fun finish(handle: Long, peer: ByteArray): Int
    external fun tlsStart(handle: Long): Int
    external fun tlsStep(handle: Long): Int
    external fun feed(handle: Long, data: ByteArray): Int
    external fun drain(handle: Long, capacity: Int): ByteArray?
    external fun write(handle: Long, data: ByteArray): Int
    external fun read(handle: Long, capacity: Int): ByteArray?
    external fun disconnect(handle: Long): Int
    external fun close(handle: Long)
}
