package spp.platform.util

import io.vertx.core.buffer.Buffer
import io.vertx.core.net.JksOptions
import okio.ByteString.Companion.decodeBase64
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileReader
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.*

class CertsToJksOptionsConverter(private val certificatePath: String, private val privateKeyPath: String) {

    private val factory: CertificateFactory by lazy { CertificateFactory() }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("JKS", "SUN").apply {
            load(null, keyStorePassword.toCharArray())
            val privateKey = loadPrivateKeyFile()
            val cert = createCertificate()
            setKeyEntry(privateKeyPath, privateKey, keyStorePassword.toCharArray(), cert)
        }
    }

    private val keyStorePassword = UUID.randomUUID().toString()

    fun createJksOptions(): JksOptions {
        return keyStore.toJksOptions(keyStorePassword)
    }

    private fun createCertificate(): Array<Certificate> {
        val result = ArrayList<Certificate>()
        val r = BufferedReader(FileReader(certificatePath))
        var s: String? = r.readLine()
        if (s == null || !s.contains("BEGIN CERTIFICATE")) {
            r.close()
            throw IllegalArgumentException("No CERTIFICATE found")
        }
        var b = StringBuilder()
        while (s != null) {
            if (s.contains("END CERTIFICATE")) {
                val hexString = b.toString()
                val bytes = hexString.decodeBase64()!!.toByteArray()
                val cert = generateCertificateFromDER(bytes)
                result.add(cert)
                b = StringBuilder()
            } else {
                if (!s.startsWith("----")) {
                    b.append(s)
                }
            }
            s = r.readLine()
        }
        r.close()
        return result.toTypedArray()
    }

    private fun generateCertificateFromDER(certBytes: ByteArray): Certificate {
        return factory.engineGenerateCertificate(ByteArrayInputStream(certBytes))
    }

    private fun loadPrivateKeyFile(): PrivateKey {
        val pemParser = PEMParser(FileReader(privateKeyPath))
        val keyObject = pemParser.readObject()
        val converter = JcaPEMKeyConverter()
        return when (keyObject) {
            is PEMKeyPair -> converter.getKeyPair(keyObject).private
            is PrivateKeyInfo -> converter.getPrivateKey(keyObject)
            else -> throw IllegalStateException("Unsupported key object: ${keyObject.javaClass}")
        }
    }
}

fun KeyStore.toJksOptions(keyStorePassword: String): JksOptions {
    val buffer = ByteArrayOutputStream().use { os ->
        store(os, keyStorePassword.toCharArray())
        Buffer.buffer(os.toByteArray())
    }
    val jksOptions = JksOptions()
    jksOptions.password = keyStorePassword
    jksOptions.value = buffer
    return jksOptions
}
