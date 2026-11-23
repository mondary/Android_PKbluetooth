package com.vibepop.qa

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.vibepop.share.*
import java.io.File
import java.net.Socket
import java.net.URL
import java.util.UUID

/** adb shell am instrument -w design.mondary.pkbluetooth.test/com.vibepop.qa.IntegrationRunner */
class IntegrationRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = runCatching { exchange() }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", result.exceptionOrNull()?.stackTraceToString() ?: "PASS: reciprocal TLS transfers, invitation/revocation, browser upload, session revocation.")
        })
    }
    private fun exchange() {
        val ctx = targetContext
        val marker = UUID.randomUUID().toString()
        val root = File(ctx.filesDir,"pk-inbox")
        val binary = ByteArray(100_003) { (it * 17).toByte() }
        val message = "$marker Bonjour 🌱\nDeuxième ligne"
        val aName = "qa-peer-a"; val bName = "qa-peer-b"
        for (name in listOf(aName,bName)) ctx.getSharedPreferences(name,0).edit().clear().commit()
        try {
            PeerHub(ctx,aName).use { a -> PeerHub(ctx,bName).use { b ->
                val code = b.invite()
                a.pair("127.0.0.1", b.port, code)
                val p = a.peers().single(); val q = b.peers().single()
                check(p.id == b.id && q.id == a.id)
                check(runCatching { a.pair("127.0.0.1",b.port,code) }.isFailure)
                a.send(p,p.host,p.port,"$marker.bin",binary.inputStream())
                check(File(root,"$marker.bin").readBytes().contentEquals(binary))
                b.send(q,q.host,q.port,"$marker.txt",message.byteInputStream())
                check(File(root,"$marker.txt").readText() == message)
                b.forget(q)
                check(runCatching { a.send(p,p.host,p.port,"$marker-revoked",binary.inputStream()) }.isFailure)
                check(!File(root,"$marker-revoked").exists())
            } }
            WebShare.start(ctx)
            val advertised = URL(WebShare.addresses.value.first())
            val base = advertised.path
            fun http(path: String, bytes: ByteArray? = null): String = Socket("127.0.0.1",advertised.port).use { s ->
                s.soTimeout = 8000
                val out = s.outputStream
                out.write("${if (bytes == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${bytes?.size ?: 0}\r\n\r\n".toByteArray())
                bytes?.let(out::write)
                s.inputStream.readBytes().decodeToString()
            }
            WebShare.offeredText = message
            check(http(base).contains(message))
            check(http("$base/upload?name=$marker.web",binary).startsWith("HTTP/1.1 200"))
            check(File(root,"$marker.web").readBytes().contentEquals(binary))
            check(http("$base/text",message.toByteArray()).startsWith("HTTP/1.1 200"))
            val textFile = root.listFiles().orEmpty().single { it.name.startsWith("message") && it.readText() == message }
            textFile.delete()
            WebShare.stop(); WebShare.start(ctx)
            check(http(base).startsWith("HTTP/1.1 404"))
        } finally {
            WebShare.stop()
            root.listFiles().orEmpty().filter { it.name.startsWith(marker) }.forEach(File::delete)
        }
    }
}
