package com.aether.airbridgetouch

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

internal object TransferProtocol {
    const val PORT = 8988
    const val MAGIC = 0x41425431
    const val MAX_FILE_BYTES = 512L * 1024L * 1024L
    const val MAX_NAME_CHARS = 240
    const val BUFFER_SIZE = 64 * 1024
    data class Header(val id: String, val name: String, val size: Long, val mime: String)
    fun safeName(raw: String): String {
        require(raw.isNotBlank() && raw.length <= MAX_NAME_CHARS) { "Invalid filename" }
        require(raw != "." && raw != ".." && !raw.contains('/') && !raw.contains('\\') && raw.none { it.code < 32 || it.code == 127 }) { "Unsafe filename" }
        return raw
    }
    fun validateSize(size: Long) {
        require(size >= 0L) { "Negative file size" }
        require(size <= MAX_FILE_BYTES) { "File exceeds the 512 MiB transfer limit" }
    }
    fun writeHeader(out: DataOutputStream, name: String, size: Long, mime: String): Header {
        validateSize(size)
        val safe = safeName(name)
        val id = UUID.randomUUID().toString()
        out.writeInt(MAGIC); out.writeUTF(id); out.writeUTF(safe); out.writeLong(size); out.writeUTF(mime.take(120)); out.flush()
        return Header(id, safe, size, mime)
    }
    fun readHeader(input: DataInputStream): Header {
        require(input.readInt() == MAGIC) { "Unrecognized transfer protocol" }
        val id = input.readUTF(); require(id.length in 1..64) { "Invalid transfer identifier" }
        val name = safeName(input.readUTF()); val size = input.readLong(); validateSize(size)
        return Header(id, name, size, input.readUTF().take(120))
    }
    fun copyAndDigest(input: InputStream, output: OutputStream, size: Long, progress: (Long) -> Unit): ByteArray {
        validateSize(size)
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(BUFFER_SIZE)
        var remaining = size; var done = 0L
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw java.io.EOFException("Connection ended before all bytes arrived")
            if (count == 0) continue
            output.write(buffer, 0, count); digest.update(buffer, 0, count); remaining -= count; done += count; progress(done)
        }
        output.flush(); return digest.digest()
    }
}
