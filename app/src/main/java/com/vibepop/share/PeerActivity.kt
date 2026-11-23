package com.vibepop.share

import android.content.Intent
import android.net.Uri
import android.net.nsd.*
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vibepop.R
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

/** Discovery is scoped to this screen; trust survives leaving, listening does not. */
class PeerActivity : AppCompatActivity() {
    private val nsd by lazy { getSystemService(NsdManager::class.java)!! }
    private var hub: PeerHub? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = mutableMapOf<String,NsdServiceInfo>()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var selected: PeerHub.Peer? = null
    private var attachment: Uri? = null
    private var incomingText: String? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val p = selected
        if (p != null && uri != null) send(p, uri, null)
    }
    private val type = "_pkbt-v1._tcp."
    private fun name(id: String) = "Chrome_PKbluetooth-${id.take(16)}"
    private fun button(label: String, action: () -> Unit) {
        list.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * resources.displayMetrics.density).toInt(); setPadding(p,p,p,p) }
        setContentView(ScrollView(this).apply { addView(list) })
        status = TextView(this).apply { setTextIsSelectable(true) }
        if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            attachment = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.takeIf { it.scheme == "content" && it.authority != "$packageName.inbox" }
            incomingText = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(256_000)
        }
        lifecycleScope.launch {
            // Keep ownership even if this Activity is destroyed while the Keystore is working.
            val result = withContext(NonCancellable + Dispatchers.IO) { runCatching { PeerHub(applicationContext) } }
            if (isDestroyed || !isActive) { result.getOrNull()?.close(); return@launch }
            hub = result.getOrNull()
            val h = hub
            if (h == null) { list.addView(status); status.setText(R.string.peer_error); return@launch }
            discover(h)
            status.text = withContext(Dispatchers.IO) {
                runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                    .joinToString("\n") { "${it.hostAddress}:${h.port}" } }.getOrDefault("")
            }
            redraw()
        }
    }
    private fun discover(h: PeerHub) {
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }.also { runCatching { nsd.registerService(NsdServiceInfo().apply { serviceName = name(h.id); serviceType = type; port = h.port }, NsdManager.PROTOCOL_DNS_SD, it) } }
        discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = runOnUiThread {
                if (!isDestroyed && info.serviceName != name(h.id)) { found[info.serviceName] = info; redraw() }
            }
            override fun onServiceLost(info: NsdServiceInfo) = runOnUiThread { found.remove(info.serviceName); if (!isDestroyed) redraw() }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }.also { runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
    }
    private fun redraw() {
        val h = hub ?: return
        list.removeAllViews()
        list.addView(TextView(this).apply { setText(R.string.peer_hint) }); list.addView(status)
        button(getString(R.string.peer_invite)) {
            AlertDialog.Builder(this).setTitle(R.string.peer_invite).setMessage("${status.text}\n\n${h.invite()}")
                .setPositiveButton(android.R.string.ok) { _,_ -> redraw() }.show()
        }
        button(getString(R.string.peer_manual)) {
            entry(getString(R.string.peer_address), "") { address ->
                val port = address.substringAfterLast(':').toIntOrNull()
                if (port == null || port !in 1..65535) notice(R.string.peer_error)
                else pair(address.substringBeforeLast(':'), port)
            }
        }
        button(getString(R.string.lan_refresh)) { redraw() }
        val peers = h.peers()
        peers.forEach { p -> button("${p.name} · ${p.id.take(8)}") {
            AlertDialog.Builder(this).setTitle(p.name).setItems(arrayOf(getString(R.string.peer_text),getString(R.string.share_file),getString(R.string.peer_forget))) { _,i ->
                when (i) {
                    0 -> entry(getString(R.string.peer_text),incomingText.orEmpty()) { send(p,null,it) }
                    1 -> attachment?.let { send(p,it,null) } ?: run { selected = p; picker.launch(arrayOf("*/*")) }
                    else -> { h.forget(p); redraw() }
                }
            }.show()
        } }
        found.filterKeys { key -> peers.none { name(it.id) == key } }.forEach { (n,info) ->
            button(n) { resolve(info) { host, port -> pair(host,port) } }
        }
    }
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, next: (String,Int) -> Unit) {
        runCatching { nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, e: Int) = runOnUiThread { notice(R.string.peer_error) }
            override fun onServiceResolved(info: NsdServiceInfo) = runOnUiThread {
                if (!isDestroyed) info.host?.hostAddress?.let { next(it,info.port) }
            }
        }) }.onFailure { notice(R.string.peer_error) }
    }
    private fun pair(host: String, port: Int) = entry(getString(R.string.peer_code),"") { code ->
        work { it.pair(host,port,code) }
    }
    private fun send(p: PeerHub.Peer, uri: Uri?, text: String?) {
        val go: (String,Int) -> Unit = { host, port -> work { h ->
            if (uri == null) text.orEmpty().byteInputStream().use { h.send(p,host,port,"message.txt",it) }
            else {
                val filename = contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
                requireNotNull(contentResolver.openInputStream(uri)).use { h.send(p,host,port,filename,it) }
            }
        } }
        found[name(p.id)]?.let { resolve(it,go) } ?: go(p.host,p.port)
    }
    private fun work(block: (PeerHub) -> Unit) {
        val h = hub ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { block(h) }.isSuccess }
            notice(if (ok) R.string.peer_done else R.string.peer_error); redraw()
        }
    }
    private fun entry(title: String, initial: String, action: (String) -> Unit) {
        val field = EditText(this).apply { setText(initial) }
        AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(android.R.string.ok) { _,_ -> field.text.toString().takeIf(String::isNotBlank)?.let(action) }.show()
    }
    private fun notice(id: Int) = Toast.makeText(this,id,Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration?.let { runCatching { nsd.unregisterService(it) } }
        hub?.close()
        super.onDestroy()
    }
}
