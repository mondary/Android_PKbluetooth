package com.vibepop.share

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.SystemClock
import org.json.JSONArray
import java.io.*
import java.net.*
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.*
import javax.net.ssl.*

/** PK-to-PK protocol. Both ends authenticate their installation key on every connection. */
internal class PeerHub(ctx: Context, private val alias: String = "pk-peer-v1") : Closeable {
    data class Peer(val id: String, val name: String, val host: String, val port: Int)
    private val prefs = ctx.getSharedPreferences(alias, Context.MODE_PRIVATE)
    private val inbox = File(ctx.filesDir, "pk-inbox").apply { mkdirs() }
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val random = SecureRandom()
    init {
        if (!store.containsAlias(alias)) KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                // Conscrypt supplies the TLS transcript digest to NONEwithECDSA.
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512).build())
            generateKeyPair()
        }
    }
    private val cert = store.getCertificate(alias) as X509Certificate
    val id = fingerprint(cert)
    private val keys = object : X509ExtendedKeyManager() {
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, s: Socket?) = alias
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = if (type == "EC") arrayOf(alias) else null
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, s: Socket?) = alias.takeIf { type == "EC" }
        override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = chooseServerAlias(type,issuers,null)
        override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun getCertificateChain(a: String?) = arrayOf(cert)
        override fun getPrivateKey(a: String?) = store.getKey(alias, null) as PrivateKey
    }
    private fun tls(expected: String?) = SSLContext.getInstance("TLS").apply {
        init(arrayOf(keys), arrayOf(object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) {
                // Pairing is open only while an invitation is active. PUT also checks the full pin.
                if (chain.isEmpty() || (peer(fingerprint(chain[0])) == null && !inviting())) throw CertificateException()
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) {
                if (expected == null || chain.isEmpty() || !fingerprint(chain[0]).startsWith(expected)) throw CertificateException()
            }
        }), random)
    }
    private val listener = tls(null).serverSocketFactory.createServerSocket(0) as SSLServerSocket
    val port get() = listener.localPort
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue())
    @Volatile private var closed = false
    private var pin = ""
    private var until = 0L
    private var tries = 0
    @Synchronized fun invite(): String {
        pin = random.nextInt(1_000_000).toString().padStart(6, '0')
        until = SystemClock.elapsedRealtime() + 120_000
        tries = 5
        return "${id.take(12)}-$pin"
    }
    @Synchronized private fun inviting() = !closed && tries > 0 && SystemClock.elapsedRealtime() < until
    @Synchronized private fun consume(code: String): Boolean {
        if (!inviting()) return false
        tries--
        if (code != pin) return false
        tries = 0
        return true
    }
    fun peers() = prefs.all.keys.mapNotNull(::peer).sortedBy { it.name }
    private fun peer(pin: String) = prefs.getString(pin, null)?.let { JSONArray(it) }
        ?.let { Peer(pin, it.getString(0), it.getString(1), it.getInt(2)) }
    fun forget(peer: Peer) = prefs.edit().remove(peer.id).apply()
    private fun save(peer: Peer) {
        check(peer.id != id && peer.port in 1..65535)
        check(prefs.edit().putString(peer.id, JSONArray(listOf(peer.name.take(80), peer.host, peer.port)).toString()).commit())
    }
    init {
        listener.needClientAuth = true
        Thread({
            while (!closed) {
                val client = try { listener.accept() as SSLSocket } catch (_: IOException) { break }
                sockets.add(client)
                try { workers.execute {
                    try { client.use { serve(it) } } catch (e: Exception) {
                        android.util.Log.d("PKPeer","Transfer not completed",e)
                    } finally { sockets.remove(client) }
                } } catch (_: RejectedExecutionException) { sockets.remove(client); client.close() }
            }
        }, "pk-peer-listen").start()
    }
    private fun serve(s: SSLSocket) {
        s.soTimeout = 30_000
        s.startHandshake()
        val remote = fingerprint(s.session.peerCertificates.first() as X509Certificate)
        val input = DataInputStream(BufferedInputStream(s.inputStream))
        val out = DataOutputStream(s.outputStream)
        when (input.readUTF()) {
            "PAIR1" -> {
                if (!consume(input.readUTF())) { out.writeBoolean(false); return }
                save(Peer(remote, input.readUTF(), s.inetAddress.hostAddress!!, input.readInt()))
                out.writeBoolean(true); out.writeUTF(android.os.Build.MODEL)
            }
            "PUT1" -> {
                check(peer(remote) != null)
                val name = input.readUTF().substringAfterLast('/').substringAfterLast('\\')
                    .filter { it >= ' ' && it != '\u007f' }.trim().trimStart('.').take(96).ifBlank { "file" }
                val part = File.createTempFile(".peer-", null, inbox)
                try {
                    val buffer = ByteArray(32 * 1024)
                    part.outputStream().buffered().use { file ->
                        var total = 0L
                        while (true) {
                            val n = input.readInt()
                            if (n == 0) break
                            total += n
                            require(n in 1..buffer.size && total <= MAX && inbox.usableSpace > 16L * 1024 * 1024)
                            input.readFully(buffer, 0, n); file.write(buffer, 0, n)
                        }
                    }
                    synchronized(InboxLock) {
                        var to = File(inbox, name)
                        var i = 1
                        while (to.exists()) to = File(inbox, "${++i}-$name")
                        check(part.renameTo(to))
                    }
                    out.writeBoolean(true)
                } finally { part.delete() }
            }
        }
    }
    private fun <T> connect(host: String, port: Int, expected: String, block: (SSLSocket) -> T): T {
        require(!closed && expected.length in listOf(12,64) && port in 1..65535)
        return (tls(expected).socketFactory.createSocket() as SSLSocket).use {
            sockets.add(it)
            try {
                check(!closed)
                it.connect(InetSocketAddress(host, port), 6000); it.soTimeout = 30_000
                it.startHandshake(); block(it)
            } finally { sockets.remove(it) }
        }
    }
    fun pair(host: String, port: Int, invitation: String) {
        val code = invitation.trim().lowercase()
        require(code.matches(Regex("[0-9a-f]{12}-[0-9]{6}")))
        connect(host, port, code.take(12)) { s ->
            val out = DataOutputStream(s.outputStream)
            out.writeUTF("PAIR1"); out.writeUTF(code.takeLast(6)); out.writeUTF(android.os.Build.MODEL); out.writeInt(this.port)
            val input = DataInputStream(s.inputStream)
            check(input.readBoolean())
            save(Peer(fingerprint(s.session.peerCertificates.first() as X509Certificate), input.readUTF(), host, port))
        }
    }
    fun send(peer: Peer, host: String, port: Int, name: String, stream: InputStream) =
        connect(host, port, peer.id) { s ->
            val out = DataOutputStream(BufferedOutputStream(s.outputStream))
            out.writeUTF("PUT1"); out.writeUTF(name.take(128))
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                total += n; require(total <= MAX)
                if (n > 0) { out.writeInt(n); out.write(buffer, 0, n) }
            }
            out.writeInt(0); out.flush()
            check(DataInputStream(s.inputStream).readBoolean())
            save(peer.copy(host = host, port = port))
        }
    override fun close() {
        closed = true
        listener.close(); workers.shutdownNow()
        sockets.forEach { runCatching { it.close() } }
    }
    companion object {
        private const val MAX = 128L * 1024 * 1024
        private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256")
            .digest(cert.publicKey.encoded).joinToString("") { "%02x".format(java.util.Locale.ROOT,it) }
    }
}
internal object InboxLock
