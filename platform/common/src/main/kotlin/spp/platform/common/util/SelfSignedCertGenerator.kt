/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.platform.common.util

import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.CertIOException
import org.bouncycastle.cert.X509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.DigestCalculator
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.*

object SelfSignedCertGenerator {

    /**
     * Generates a self signed certificate using the BouncyCastle lib.
     *
     * @param keyPair used for signing the certificate with PrivateKey
     * @param hashAlgorithm Hash function
     * @param cn Common Name to be used in the subject dn
     * @param days validity period in days of the certificate
     *
     * @return self-signed X509Certificate
     *
     * @throws OperatorCreationException on creating a key id
     * @throws CertIOException on building JcaContentSignerBuilder
     * @throws CertificateException on getting certificate from provider
     */
    fun generate(keyPair: KeyPair, hashAlgorithm: String, cn: String, days: Int): X509Certificate {
        val now = Instant.now()
        val notBefore = Date.from(now)
        val notAfter = Date.from(now.plus(Duration.ofDays(days.toLong())))
        val contentSigner = JcaContentSignerBuilder(hashAlgorithm).build(keyPair.private)
        val x500Name = X500Name("CN=$cn")
        val certificateBuilder = JcaX509v3CertificateBuilder(
            x500Name,
            BigInteger.valueOf(now.toEpochMilli()),
            notBefore,
            notAfter,
            x500Name,
            keyPair.public
        )
            .addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyId(keyPair.public))
            .addExtension(Extension.authorityKeyIdentifier, false, createAuthorityKeyId(keyPair.public))
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        return JcaX509CertificateConverter().getCertificate(certificateBuilder.build(contentSigner))
    }

    /**
     * Creates the hash value of the public key.
     *
     * @param publicKey of the certificate
     *
     * @return SubjectKeyIdentifier hash
     *
     * @throws OperatorCreationException
     */
    private fun createSubjectKeyId(publicKey: PublicKey): SubjectKeyIdentifier {
        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        val digCalc: DigestCalculator =
            BcDigestCalculatorProvider().get(AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1))
        return X509ExtensionUtils(digCalc).createSubjectKeyIdentifier(publicKeyInfo)
    }

    /**
     * Creates the hash value of the authority public key.
     *
     * @param publicKey of the authority certificate
     *
     * @return AuthorityKeyIdentifier hash
     *
     * @throws OperatorCreationException
     */
    private fun createAuthorityKeyId(publicKey: PublicKey): AuthorityKeyIdentifier {
        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        val digCalc: DigestCalculator =
            BcDigestCalculatorProvider().get(AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1))
        return X509ExtensionUtils(digCalc).createAuthorityKeyIdentifier(publicKeyInfo)
    }

    fun generateKeyPair(keySize: Int): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        val random = SecureRandom.getInstance("SHA1PRNG", "SUN")
        keyGen.initialize(keySize, random)
        return keyGen.generateKeyPair()
    }
}
