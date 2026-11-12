package org.desktop2fa.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import kotlin.system.exitProcess

/** Private test-only pipe RPC; NOT the transfer wire protocol or an app entry point. */
fun main() {
    val handles = mutableSetOf<Long>()
    val input = DataInputStream(System.`in`)
    val output = DataOutputStream(System.out)
    var failed = false
    try {
        while (true) {
            val length = try { input.readInt() } catch (_: EOFException) { break }
            require(length in 1..16416)
            val packet = ByteArray(length)
            input.readFully(packet)
            val result = ByteArrayOutputStream()
            val response = DataOutputStream(result)
            try {
                val request = DataInputStream(ByteArrayInputStream(packet))
                fun bytes(size: Int): ByteArray = ByteArray(size).also { request.readFully(it) }
                fun array(value: ByteArray?) {
                    response.writeInt(value?.size ?: -1)
                    if (value != null) { response.write(value); value.fill(0) }
                }
                when (request.readUnsignedByte()) {
                    1 -> {
                        val role = request.readInt()
                        val sid = bytes(16); val wid = bytes(16); val code = bytes(8)
                        val handle = try { NativeCore.create(role, sid, wid, code) }
                            finally { sid.fill(0); wid.fill(0); code.fill(0) }
                        if (handle != 0L) handles.add(handle)
                        response.writeLong(handle)
                    }
                    2 -> array(NativeCore.message(request.readLong()))
                    3 -> {
                        val handle = request.readLong()
                        val peer = bytes(32)
                        response.writeInt(try { NativeCore.finish(handle, peer) } finally { peer.fill(0) })
                    }
                    4 -> response.writeInt(NativeCore.tlsStart(request.readLong()))
                    5 -> response.writeInt(NativeCore.tlsStep(request.readLong()))
                    6, 8 -> error("Reserved test opcode")
                    7 -> array(NativeCore.drain(request.readLong(), request.readInt()))
                    9 -> array(NativeCore.read(request.readLong(), request.readInt()))
                    10 -> response.writeInt(NativeCore.disconnect(request.readLong()))
                    11 -> {
                        val handle = request.readLong()
                        NativeCore.close(handle); handles.remove(handle)
                    }
                    12 -> array(NativeCore.testKey(request.readLong()))
                    16, 18 -> {
                        // Test opcodes separate encrypted input and application plaintext.
                        val operation = packet[0].toInt()
                        val handle = request.readLong()
                        val data = request.readBytes()
                        require(data.size in 1..16384)
                        response.writeInt(try {
                            if (operation == 16) NativeCore.feed(handle, data) else NativeCore.write(handle, data)
                        } finally { data.fill(0) })
                    }
                    else -> error("Unknown test opcode")
                }
                require(request.available() == 0)
                val encoded = result.toByteArray()
                try { output.writeInt(encoded.size); output.write(encoded); output.flush() }
                finally { encoded.fill(0) }
            } finally { packet.fill(0) }
        }
    } catch (_: Throwable) {
        // No exception text, packets, key bytes or codes in test diagnostics.
        System.err.println("JNI PoC peer failed (details redacted)")
        failed = true
    } finally { handles.forEach(NativeCore::close) }
    if (failed) exitProcess(1)
}
