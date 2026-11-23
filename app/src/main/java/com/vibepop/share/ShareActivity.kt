package com.vibepop.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.vibepop.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ShareActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private lateinit var address: TextView
    private lateinit var startStop: Button
    private lateinit var choose: Button
    private lateinit var offered: LinearLayout
    private lateinit var inbox: LinearLayout
    private lateinit var link: Button
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        lifecycleScope.launch { withContext(Dispatchers.IO) { WebShare.offer(applicationContext, uris) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.lan_share)
        val pad = (16 * resources.displayMetrics.density).toInt()
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad,pad,pad,pad) }
        setContentView(ScrollView(this).apply { addView(body) })
        text(R.string.lan_share, 24f)
        button(body, getString(R.string.peer_title)) { startActivity(Intent(this, PeerActivity::class.java)) }
        text(R.string.lan_hint, 15f)
        startStop = button(body, "") {
            startStop.isEnabled = false
            lifecycleScope.launch {
                if (WebShare.running.value) WebShare.stop()
                else withContext(Dispatchers.IO) { WebShare.start(applicationContext) }
                if (!WebShare.running.value && WebShare.addresses.value.isEmpty()) address.text = getString(R.string.lan_off)
                startStop.isEnabled = true
                refresh()
            }
        }
        address = TextView(this).apply { setTextIsSelectable(true); body.addView(this) }
        link = button(body, getString(R.string.lan_link)) {
            val url = WebShare.addresses.value.firstOrNull() ?: return@button
            share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url))
        }
        choose = button(body, getString(R.string.lan_choose)) { picker.launch(arrayOf("*/*")) }
        button(body, getString(R.string.peer_text)) {
            if (!WebShare.running.value) { Toast.makeText(this,R.string.lan_off,Toast.LENGTH_SHORT).show(); return@button }
            val field = EditText(this).apply { setText(WebShare.offeredText) }
            AlertDialog.Builder(this).setTitle(R.string.peer_text).setView(field)
                .setPositiveButton(android.R.string.ok) { _,_ -> WebShare.offeredText = field.text.toString().take(256_000) }
                .setNegativeButton(android.R.string.cancel,null).show()
        }
        offered = column()
        text(R.string.lan_inbox, 20f)
        button(body, getString(R.string.lan_refresh)) { refreshInbox() }
        inbox = column()
        lifecycleScope.launch { WebShare.offered.collect { files ->
            offered.removeAllViews()
            files.forEach { file -> button(offered, getString(R.string.lan_withdraw, file.name)) { WebShare.withdraw(file.id) } }
        } }
        refresh()
    }

    override fun onResume() { super.onResume(); refreshInbox() }
    override fun onDestroy() {
        if (isFinishing) WebShare.stop()
        super.onDestroy()
    }
    private fun refresh() {
        val on = WebShare.running.value
        startStop.setText(if (on) R.string.lan_stop else R.string.lan_start)
        address.text = if (on) WebShare.addresses.value.joinToString("\n").ifEmpty { getString(R.string.lan_no_address) }
            else getString(R.string.lan_off)
        link.isEnabled = on && WebShare.addresses.value.isNotEmpty()
        choose.isEnabled = on
    }
    private fun refreshInbox(): kotlinx.coroutines.Job = lifecycleScope.launch {
        val files = withContext(Dispatchers.IO) {
            File(filesDir,"pk-inbox").listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedByDescending { it.lastModified() }.orEmpty()
        }
        inbox.removeAllViews()
        if (files.isEmpty()) inbox.addView(TextView(this@ShareActivity).apply { setText(R.string.lan_empty) })
        files.forEach { file ->
            button(inbox, file.name) {
                AlertDialog.Builder(this@ShareActivity).setTitle(file.name)
                    .setItems(arrayOf(getString(R.string.share_file),getString(R.string.lan_delete))) { _, i ->
                        if (i == 0) {
                            val uri = FileProvider.getUriForFile(this@ShareActivity,"$packageName.inbox", file)
                            share(Intent(Intent.ACTION_SEND).setType(contentResolver.getType(uri) ?: "application/octet-stream")
                                .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .apply { clipData = ClipData.newRawUri(file.name, uri) })
                        } else { file.delete(); refreshInbox() }
                    }.show()
            }
        }
    }
    private fun share(send: Intent) = startActivity(Intent.createChooser(send, null))
    private fun column() = LinearLayout(this).also { it.orientation = LinearLayout.VERTICAL; body.addView(it) }
    private fun text(res: Int, sp: Float) = TextView(this).apply { setText(res); textSize = sp; body.addView(this) }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit) =
        Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() }; parent.addView(this) }
}
