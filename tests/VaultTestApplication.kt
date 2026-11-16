package app.family.igguard

import android.app.Application
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/** Test-only adapter for Robolectric, which has no Android Keystore service.
 * AES-GCM and HMAC still execute real JCA algorithms; this does not emulate hardware isolation. */
class VaultTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TestKeyStore.failReads = false
        Security.removeProvider("AndroidKeyStore")
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "Robolectric storage test provider") {
            init {
                put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
                put("KeyGenerator.AES", AesTestKeys::class.java.name)
                put("KeyGenerator.HmacSHA256", HmacTestKeys::class.java.name)
            }
        })
        // Existing regression scenarios describe configured, explicitly consenting installations.
        // Fresh-install consent tests clear this separate receipt before exercising production gates.
        AppConsent.accept(this, AppConsent.acknowledgments)
    }
}

class TestKeyStore : KeyStoreSpi() {
    companion object {
        val keys = ConcurrentHashMap<String, SecretKey>()
        var failReads = false
    }
    override fun engineLoad(stream: InputStream?, password: CharArray?) { check(!failReads) { "Synthetic unavailable Keystore" } }
    override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
    override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineAliases(): java.util.Enumeration<String> = Collections.enumeration(keys.keys)
    override fun engineSize(): Int = keys.size
    override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date(0) else null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
}

open class TestKeys(private val algorithm: String) : KeyGeneratorSpi() {
    private var alias = ""
    private var bits = 256
    override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException()
    override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException()
    override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
        // Reflection permits different Robolectric SDK classloaders to share the test provider.
        alias = params.javaClass.getMethod("getKeystoreAlias").invoke(params) as String
        bits = params.javaClass.getMethod("getKeySize").invoke(params) as Int
    }
    override fun engineGenerateKey(): SecretKey = KeyGenerator.getInstance(algorithm, "SunJCE").apply { init(bits) }
        .generateKey().also { TestKeyStore.keys[alias] = it }
}
class AesTestKeys : TestKeys("AES")
class HmacTestKeys : TestKeys("HmacSHA256")
