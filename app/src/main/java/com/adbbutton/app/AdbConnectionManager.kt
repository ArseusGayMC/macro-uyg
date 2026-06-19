package com.adbbutton.app

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private var mPrivateKey: PrivateKey?
    private var mCertificate: Certificate?

    init {
        api = Build.VERSION.SDK_INT
        mPrivateKey = readPrivateKeyFromFile(context)
        mCertificate = readCertificateFromFile(context)
        if (mPrivateKey == null) {
            val keySize = 2048
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(keySize, SecureRandom.getInstance("SHA1PRNG"))
            val generateKeyPair = keyPairGenerator.generateKeyPair()
            val publicKey = generateKeyPair.public
            mPrivateKey = generateKeyPair.private
            val subject = "CN=AdbTapButton"
            val algorithmName = "SHA512withRSA"
            val expiryDate = System.currentTimeMillis() + 86400000L * 3650
            val certificateExtensions = CertificateExtensions()
            certificateExtensions.set(
                "SubjectKeyIdentifier",
                SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier)
            )
            val x500Name = X500Name(subject)
            val notBefore = Date()
            val notAfter = Date(expiryDate)
            certificateExtensions.set(
                "PrivateKeyUsage",
                PrivateKeyUsageExtension(notBefore, notAfter)
            )
            val x509CertInfo = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
                set("subject", CertificateSubjectName(x500Name))
                set("key", CertificateX509Key(publicKey))
                set("validity", CertificateValidity(notBefore, notAfter))
                set("issuer", CertificateIssuerName(x500Name))
                set("extensions", certificateExtensions)
            }
            val x509CertImpl = X509CertImpl(x509CertInfo).apply {
                sign(mPrivateKey, algorithmName)
            }
            mCertificate = x509CertImpl
            writePrivateKeyToFile(context, mPrivateKey!!)
            writeCertificateToFile(context, mCertificate!!)
        }
    }

    override fun getPrivateKey(): PrivateKey = mPrivateKey!!
    override fun getCertificate(): Certificate = mCertificate!!
    override fun getDeviceName(): String = "AdbTapButton"

    private fun readPrivateKeyFromFile(context: Context): PrivateKey? {
        return try {
            val file = File(context.filesDir, "adb_key.pem")
            if (!file.exists()) return null
            val bytes = file.readBytes()
            val spec = PKCS8EncodedKeySpec(bytes)
            KeyFactory.getInstance("RSA").generatePrivate(spec)
        } catch (e: Exception) {
            null
        }
    }

    private fun readCertificateFromFile(context: Context): Certificate? {
        return try {
            val file = File(context.filesDir, "adb_cert.pem")
            if (!file.exists()) return null
            FileInputStream(file).use { fis ->
                CertificateFactory.getInstance("X.509").generateCertificate(fis)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writePrivateKeyToFile(context: Context, privateKey: PrivateKey) {
        try {
            val file = File(context.filesDir, "adb_key.pem")
            file.writeBytes(privateKey.encoded)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeCertificateToFile(context: Context, certificate: Certificate) {
        try {
            val file = File(context.filesDir, "adb_cert.pem")
            FileOutputStream(file).use { fos ->
                fos.write(certificate.encoded)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AbsAdbConnectionManager? = null

        fun getInstance(context: Context): AbsAdbConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdbConnectionManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        fun resetInstance() {
            INSTANCE?.disconnect()
            INSTANCE = null
        }
    }
}
