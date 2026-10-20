package com.quatrang.remotesony.proto

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Bộ mã hoá/giải mã protobuf tối giản (chỉ đủ cho giao thức Android TV Remote v2),
 * để không phải dùng plugin protobuf.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun tag(field: Int, wireType: Int) = rawVarint(((field shl 3) or wireType).toLong())

    fun int(field: Int, value: Int): ProtoWriter {
        tag(field, 0); rawVarint(value.toLong()); return this
    }

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2); rawVarint(value.size.toLong()); out.write(value); return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun varintBytes(value: Long): ByteArray {
            val o = ByteArrayOutputStream()
            var v = value
            while (true) {
                if ((v and 0x7FL.inv()) == 0L) {
                    o.write(v.toInt()); break
                }
                o.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            return o.toByteArray()
        }
    }
}

class ProtoMsg private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int) = fields.containsKey(field)
    fun long(field: Int, def: Long = 0L): Long = (fields[field]?.firstOrNull() as? Long) ?: def
    fun int(field: Int, def: Int = 0): Int = long(field, def.toLong()).toInt()
    fun bool(field: Int): Boolean = long(field) != 0L
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    fun msg(field: Int): ProtoMsg? = bytes(field)?.let { runCatching { parse(it) }.getOrNull() }

    override fun toString(): String = fields.keys.sorted().joinToString(prefix = "{", postfix = "}")

    companion object {
        fun parse(data: ByteArray): ProtoMsg {
            val map = HashMap<Int, MutableList<Any>>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw EOFException("varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IllegalStateException("varint quá dài")
                }
            }
            while (pos < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> map.getOrPut(field) { mutableListOf() }.add(readVarint())
                    1 -> pos += 8
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > data.size) throw EOFException("len")
                        map.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    5 -> pos += 4
                    else -> throw IllegalStateException("wire type lạ")
                }
            }
            return ProtoMsg(map)
        }
    }
}

/** Mỗi gói tin = độ dài (varint) + nội dung protobuf. */
object Framing {
    fun write(out: OutputStream, payload: ByteArray) {
        out.write(ProtoWriter.varintBytes(payload.size.toLong()))
        out.write(payload)
        out.flush()
    }

    fun read(input: InputStream): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("TV đã đóng kết nối")
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IllegalStateException("độ dài gói sai")
        }
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) throw EOFException("TV đã đóng kết nối")
            read += n
        }
        return buf
    }
}
