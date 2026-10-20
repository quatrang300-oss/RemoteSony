package com.quatrang.remotesony.tv

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Chứng chỉ máy khách (tự ký) – TV ghi nhớ chứng chỉ này sau khi ghép nối. */
class CertStore(context: Context) {
    private val prefs = context.getSharedPreferences("client_cert", Context.MODE_PRIVATE)
    private var cached: Pair<PrivateKey, X509Certificate>? = null

    @Synchronized
    fun get(): Pair<PrivateKey, X509Certificate> {
        cached?.let { return it }
        val keyB64 = prefs.getString("key", null)
        val certB64 = prefs.getString("cert", null)
        val loaded = if (keyB64 != null && certB64 != null) runCatching {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(keyB64, Base64.NO_WRAP)))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.decode(certB64, Base64.NO_WRAP).inputStream()) as X509Certificate
            key to cert
        }.getOrNull() else null
        val result = loaded ?: generate()
        cached = result
        return result
    }

    private fun generate(): Pair<PrivateKey, X509Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=atvremote, O=RemoteSony")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 24L * 3600 * 1000),
            Date(now + 20L * 365 * 24 * 3600 * 1000),
            name,
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        prefs.edit()
            .putString("key", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .apply()
        return kp.private to cert
    }

    fun keyStore(): KeyStore {
        val (key, cert) = get()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("remote", key, PASS, arrayOf(cert))
        return ks
    }

    companion object {
        val PASS = "remotesony".toCharArray()
    }
}

object Tls {
    /** TV dùng chứng chỉ tự ký nên chấp nhận mọi chứng chỉ máy chủ (chỉ dùng trong mạng LAN). */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun connect(host: String, port: Int, certs: CertStore, readTimeoutMs: Int): SSLSocket {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(certs.keyStore(), CertStore.PASS)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom())
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), 5000)
        raw.soTimeout = 8000
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.useClientMode = true
        ssl.startHandshake()
        ssl.soTimeout = readTimeoutMs
        return ssl
    }
}
