package com.quatrang.remotesony.tv

import com.quatrang.remotesony.proto.Framing
import com.quatrang.remotesony.proto.ProtoMsg
import com.quatrang.remotesony.proto.ProtoWriter
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingException(message: String) : IOException(message)

/**
 * Ghép nối (cổng 6467, giao thức "polo"). Sau [start] TV hiện mã 6 ký tự,
 * người dùng nhập mã vào [finish].
 */
class Pairing(private val host: String, private val certs: CertStore) {
    private var socket: SSLSocket? = null

    private fun outer(block: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().int(1, 2).int(2, 200).apply(block).toByteArray()

    private fun send(sock: SSLSocket, data: ByteArray) = Framing.write(sock.outputStream, data)

    private fun receive(sock: SSLSocket): ProtoMsg {
        val msg = ProtoMsg.parse(Framing.read(sock.inputStream))
        val status = msg.int(2, 200)
        if (status != 200) {
            throw PairingException(
                when (status) {
                    402 -> "Mã không đúng"
                    401 -> "TV từ chối cấu hình ghép nối"
                    else -> "TV báo lỗi ghép nối ($status)"
                }
            )
        }
        return msg
    }

    /** Chặn luồng cho tới khi TV hiện mã ghép nối. */
    fun start(clientName: String) {
        close()
        val sock = Tls.connect(host, 6467, certs, 20000)
        socket = sock
        send(sock, outer {
            message(10) { string(1, "atvremote"); string(2, clientName) }
        })
        while (true) {
            val msg = receive(sock)
            when {
                msg.has(11) -> send(sock, outer { // pairing_request_ack -> options
                    message(20) {
                        message(1) { int(1, 3); int(2, 6) } // HEX, 6 ký tự
                        int(3, 1) // ROLE_TYPE_INPUT
                    }
                })
                msg.has(20) -> send(sock, outer { // options -> configuration
                    message(30) {
                        message(1) { int(1, 3); int(2, 6) }
                        int(2, 1)
                    }
                })
                msg.has(31) -> return // configuration_ack: TV đang hiện mã
                else -> throw PairingException("TV trả lời không đúng giao thức")
            }
        }
    }

    /** Trả về khi ghép nối thành công, ném lỗi nếu sai mã. */
    fun finish(rawCode: String) {
        val sock = socket ?: throw PairingException("Chưa bắt đầu ghép nối")
        val code = rawCode.trim().uppercase()
        if (code.length != 6 || !code.all { it in '0'..'9' || it in 'A'..'F' }) {
            throw PairingException("Mã gồm 6 ký tự (0-9, A-F)")
        }
        val clientKey = certs.get().second.publicKey as RSAPublicKey
        val serverCert = sock.session.peerCertificates.first() as X509Certificate
        val serverKey = serverCert.publicKey as RSAPublicKey

        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(unsigned(clientKey.modulus))
        sha.update(unsigned(clientKey.publicExponent))
        sha.update(unsigned(serverKey.modulus))
        sha.update(unsigned(serverKey.publicExponent))
        sha.update(hex(code.substring(2)))
        val hash = sha.digest()
        if ((hash[0].toInt() and 0xFF) != code.substring(0, 2).toInt(16)) {
            throw PairingException("Mã không đúng, kiểm tra lại mã trên TV")
        }
        send(sock, outer { message(40) { bytes(1, hash) } })
        val reply = receive(sock)
        if (!reply.has(41)) throw PairingException("TV không xác nhận ghép nối")
        close()
    }

    fun close() {
        val s = socket
        socket = null
        // Đóng TLS có thể ghi close_notify -> không làm trên luồng chính
        if (s != null) Thread { runCatching { s.close() } }.start()
    }

    companion object {
        /** Số dương dạng big-endian, bỏ byte 0 ở đầu. */
        internal fun unsigned(n: BigInteger): ByteArray {
            val b = n.toByteArray()
            var i = 0
            while (i < b.size - 1 && b[i].toInt() == 0) i++
            return b.copyOfRange(i, b.size)
        }

        internal fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
