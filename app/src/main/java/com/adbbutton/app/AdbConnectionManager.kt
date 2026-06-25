package com.adbbutton.app

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

class AdbConnectionManager private constructor(private val appContext: Context) : AbsAdbConnectionManager() {

    private var mPrivateKey: PrivateKey?
    private var mCertificate: Certificate?

    init {
        api = Build.VERSION.SDK_INT

        mPrivateKey = loadPrivateKey()
        mCertificate = loadCertificate()

        if (mPrivateKey == null || mCertificate == null) {
            Log.d(TAG, "Generating new RSA key pair + certificate via Bouncy Castle")

            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()

            val subject = X500Name("CN=AdbTapButton")
            val now = Date()
            val expiry = Date(now.time + 86400000L * 3650L)
            val serial = BigInteger.valueOf(System.currentTimeMillis())

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val certHolder = JcaX509v3CertificateBuilder(subject, serial, now, expiry, subject, kp.public)
                .build(signer)

            mPrivateKey = kp.private
            mCertificate = JcaX509CertificateConverter().getCertificate(certHolder)

            savePrivateKey(mPrivateKey!!)
            saveCertificate(mCertificate!!)
            Log.d(TAG, "Key pair generated and saved")
        } else {
            Log.d(TAG, "Key pair loaded from storage")
        }
    }

    override fun getPrivateKey(): PrivateKey = mPrivateKey!!
    override fun getCertificate(): Certificate = mCertificate!!
    override fun getDeviceName(): String = "AdbTapButton"

    private fun loadPrivateKey(): PrivateKey? = try {
        val f = File(appContext.filesDir, KEY_FILE)
        if (!f.exists()) null
        else KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(f.readBytes()))
    } catch (e: Exception) {
        Log.w(TAG, "loadPrivateKey failed: ${e.message}")
        null
    }

    private fun loadCertificate(): Certificate? = try {
        val f = File(appContext.filesDir, CERT_FILE)
        if (!f.exists()) null
        else FileInputStream(f).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
    } catch (e: Exception) {
        Log.w(TAG, "loadCertificate failed: ${e.message}")
        null
    }

    private fun savePrivateKey(key: PrivateKey) {
        try { File(appContext.filesDir, KEY_FILE).writeBytes(key.encoded) }
        catch (e: Exception) { Log.w(TAG, "savePrivateKey: ${e.message}") }
    }

    private fun saveCertificate(cert: Certificate) {
        try { FileOutputStream(File(appContext.filesDir, CERT_FILE)).use { it.write(cert.encoded) } }
        catch (e: Exception) { Log.w(TAG, "saveCertificate: ${e.message}") }
    }

    companion object {
        private const val TAG = "AdbConnectionManager"
        private const val KEY_FILE = "adb_key.der"
        private const val CERT_FILE = "adb_cert.der"

        @Volatile private var INSTANCE: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdbConnectionManager(context.applicationContext).also { INSTANCE = it }
            }

        fun resetInstance() {
            synchronized(this) {
                try { INSTANCE?.disconnect() } catch (_: Exception) {}
                INSTANCE = null
                clearSslCache()
            }
        }

        private fun clearSslCache() {
            try {
                val cls = Class.forName("io.github.muntashirakon.adb.SslUtils")
                val field = cls.getDeclaredField("sslContext")
                field.isAccessible = true
                field.set(null, null)
                Log.d(TAG, "SslUtils cache cleared")
            } catch (e: Exception) {
                Log.w(TAG, "clearSslCache: ${e.message}")
            }
        }
    }
}
