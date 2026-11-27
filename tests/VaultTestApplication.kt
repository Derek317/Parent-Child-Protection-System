package app.family.igguard

import android.app.Application
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/** Test-only adapter for Robolectric, which has no Android Keystore service.
 * AES-GCM, HMAC and EC still execute real JCA algorithms; this does not emulate hardware isolation. */
class VaultTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A leftover animation makes Robolectric's looper draw frames for ever in a later test's setup(); the app skips its animations when they are off.
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        TestKeyStore.failReads = false
        TestKeyStore.failEcGeneration = false
        TestKeyStore.failEcImport = false
        Security.removeProvider("AndroidKeyStore")
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "Robolectric storage test provider") {
            init {
                put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
                put("KeyGenerator.AES", AesTestKeys::class.java.name)
                put("KeyGenerator.HmacSHA256", HmacTestKeys::class.java.name)
                put("KeyPairGenerator.EC", EcTestKeys::class.java.name)
                put("Signature.SHA256withECDSA", EcTestSignature::class.java.name)
            }
        })
        ParentUiAuthFixture.autoApprove = true
        registerActivityLifecycleCallbacks(ParentUiAuthFixture)
        // Existing regression scenarios describe configured, explicitly consenting installations.
        // Fresh-install consent tests clear this separate receipt before exercising production gates.
        AppConsent.accept(this, AppConsent.acknowledgments)
    }
}

class TestKeyStore : KeyStoreSpi() {
    companion object {
        val keys = ConcurrentHashMap<String, SecretKey>()
        val privateKeys = ConcurrentHashMap<String, PrivateKey>()
        val certificates = ConcurrentHashMap<String, Certificate>()
        var failReads = false
        var failEcGeneration = false
        var failEcImport = false
        var failEcSigning = false
        var ecAuthenticationRequired = false
        /** Whether each EC generation asked for StrongBox; [failStrongBox] makes such a request fail as a phone without it does. */
        val strongBoxRequests = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        var failStrongBox = false
        @Volatile var onLoad: () -> Unit = {}
    }
    override fun engineLoad(stream: InputStream?, password: CharArray?) { check(!failReads) { "Synthetic unavailable Keystore" }; onLoad() }
    override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
    override fun engineGetKey(alias: String, password: CharArray?): Key? = privateKeys[alias] ?: keys[alias]
    override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias) || privateKeys.containsKey(alias)
    override fun engineDeleteEntry(alias: String) { keys.remove(alias); privateKeys.remove(alias); certificates.remove(alias) }
    override fun engineAliases(): java.util.Enumeration<String> = Collections.enumeration(keys.keys + privateKeys.keys)
    override fun engineSize(): Int = keys.size + privateKeys.size
    override fun engineIsKeyEntry(alias: String): Boolean = engineContainsAlias(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCreationDate(alias: String): Date? = if (engineContainsAlias(alias)) Date(0) else null
    override fun engineGetCertificate(alias: String): Certificate? = certificates[alias]
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = certificates[alias]?.let { arrayOf(it) }
    override fun engineGetCertificateAlias(cert: Certificate?): String? = certificates.entries.firstOrNull { it.value == cert }?.key
    override fun engineSetEntry(alias: String, entry: KeyStore.Entry, protection: KeyStore.ProtectionParameter?) {
        check(!failEcImport) { "Synthetic EC import failure" }
        require(entry is KeyStore.PrivateKeyEntry)
        ecAuthenticationRequired = protection!!.javaClass.getMethod("isUserAuthenticationRequired").invoke(protection) as Boolean
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(entry.certificate.encoded.inputStream()) as X509Certificate
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        val challenge = "test-provider-import-proof".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA", "SunEC").apply { initSign(entry.privateKey); update(challenge) }.sign()
        require(Signature.getInstance("SHA256withECDSA", "SunEC").run { initVerify(certificate.publicKey); update(challenge); verify(signature) })
        privateKeys[alias] = TestEcPrivateKey(entry.privateKey)
        certificates[alias] = certificate
    }
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

class TestEcPrivateKey(val delegate: PrivateKey) : PrivateKey {
    override fun getAlgorithm() = "EC"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

class EcTestKeys : KeyPairGeneratorSpi() {
    private var alias = ""
    private var strongBox = false
    override fun initialize(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException()
    override fun initialize(params: AlgorithmParameterSpec, random: SecureRandom?) {
        alias = params.javaClass.getMethod("getKeystoreAlias").invoke(params) as String
        // isStrongBoxBacked exists from API 28 only.
        strongBox = try { params.javaClass.getMethod("isStrongBoxBacked").invoke(params) as Boolean } catch (_: NoSuchMethodException) { false }
        TestKeyStore.ecAuthenticationRequired = params.javaClass.getMethod("isUserAuthenticationRequired").invoke(params) as Boolean
        val curve = params.javaClass.getMethod("getAlgorithmParameterSpec").invoke(params) as ECGenParameterSpec
        require(curve.name == "secp256r1")
    }
    override fun generateKeyPair(): KeyPair {
        check(!TestKeyStore.failEcGeneration) { "Synthetic EC generation failure" }
        TestKeyStore.strongBoxRequests.add(strongBox)
        if (strongBox && TestKeyStore.failStrongBox) throw ProviderException("Synthetic StrongBox unavailable")
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        TestKeyStore.privateKeys[alias] = TestEcPrivateKey(pair.private)
        // Generation only needs a public-key certificate; import separately demands a real, signed X.509 certificate.
        TestKeyStore.certificates[alias] = object : Certificate("X.509") {
            override fun getPublicKey(): PublicKey = pair.public
            override fun getEncoded(): ByteArray = throw UnsupportedOperationException()
            override fun verify(key: PublicKey?) = throw UnsupportedOperationException()
            override fun verify(key: PublicKey?, provider: String?) = throw UnsupportedOperationException()
            override fun toString() = "Test generated EC public key"
        }
        return KeyPair(pair.public, TestKeyStore.privateKeys.getValue(alias))
    }
}

class EcTestSignature : SignatureSpi() {
    private val delegate = Signature.getInstance("SHA256withECDSA", "SunEC")
    override fun engineInitSign(key: PrivateKey) {
        if (key is TestEcPrivateKey && TestKeyStore.failEcSigning) throw InvalidKeyException("Synthetic EC signing failure")
        delegate.initSign((key as? TestEcPrivateKey)?.delegate ?: key)
    }
    override fun engineInitVerify(key: PublicKey) = delegate.initVerify(key)
    override fun engineUpdate(value: Byte) = delegate.update(value)
    override fun engineUpdate(bytes: ByteArray, offset: Int, length: Int) = delegate.update(bytes, offset, length)
    override fun engineSign(): ByteArray = delegate.sign()
    override fun engineVerify(signature: ByteArray): Boolean = delegate.verify(signature)
    override fun engineSetParameter(name: String?, value: Any?) = throw UnsupportedOperationException()
    override fun engineGetParameter(name: String?): Any = throw UnsupportedOperationException()
}

/** Existing UI regressions assume a trusted parent has completed native device authentication.
 * New access-control tests disable this fixture and exercise the real locked/cancelled paths.
 * Reflection is test-only; production has no test flag or alternate authentication route. */
object ParentUiAuthFixture : Application.ActivityLifecycleCallbacks {
    var autoApprove = true
    private fun approve(activity: android.app.Activity) {
        if(!autoApprove || activity !is MainActivity || !GuardEngine.isParentMode(activity))return
        val ready=MainActivity::class.java.getDeclaredField("storageReady").apply{isAccessible=true}.getBoolean(activity)
        if(!ready)return
        MainActivity::class.java.getDeclaredField("parentAuthSession").apply{isAccessible=true}.set(activity,GuardEngine.captureRelaySession(activity))
        MainActivity::class.java.getDeclaredMethod("completeParentAuthentication",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(activity,true)
    }
    override fun onActivityCreated(activity:android.app.Activity,state:android.os.Bundle?){approve(activity)}
    override fun onActivityResumed(activity:android.app.Activity){approve(activity)}
    override fun onActivityStarted(activity:android.app.Activity){}
    override fun onActivityPaused(activity:android.app.Activity){}
    override fun onActivityStopped(activity:android.app.Activity){}
    override fun onActivitySaveInstanceState(activity:android.app.Activity,state:android.os.Bundle){}
    override fun onActivityDestroyed(activity:android.app.Activity){}
}
