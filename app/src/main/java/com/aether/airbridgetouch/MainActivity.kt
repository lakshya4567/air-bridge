package com.aether.airbridgetouch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var status: TextView
    private lateinit var peersBox: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var transferButton: Button
    private val peers = mutableListOf<WifiP2pDevice>()
    private var groupOwner = false
    private var ownerAddress = "192.168.49.1"
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private var activeSocket: Socket? = null
    private val stopped = AtomicBoolean(false)
    private var chosenUri: Uri? = null
    private var chosenName = "file"
    private var chosenSize = 0L
    private var chosenMime = "application/octet-stream"
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) discoverPeers() else say("Nearby Wi-Fi permission is required to discover devices.")
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareFile(uri)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    runOnUiThread { status.text = if (enabled) "Wi-Fi Direct is enabled. Discover nearby devices." else "Turn on Wi-Fi in system settings first." }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> if (hasPermission()) manager.requestPeers(channel) { updatePeers(it) }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> manager.requestConnectionInfo(channel) { onConnectionInfo(it) }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> Unit
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, Looper.getMainLooper(), null)
        buildUi()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        requestNeededPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24); setBackgroundColor(0xFF101114.toInt()) }
        fun label(text: String, size: Float = 16f): TextView = TextView(this).apply { this.text = text; textSize = size; setTextColor(0xFFF1F3F5.toInt()); setPadding(0, 8, 0, 8) }
        root.addView(label("AirBridgeTouch", 28f))
        root.addView(label("Direct phone-to-phone transfer · no cloud", 14f))
        status = label("Checking Wi-Fi Direct…")
        root.addView(status)
        val scan = Button(this).apply { text = "Find nearby devices"; setOnClickListener { requestNeededPermissions() } }
        root.addView(scan)
        root.addView(label("Nearby devices", 20f))
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peersBox)
        transferButton = Button(this).apply { text = "Choose file to send"; isEnabled = false; setOnClickListener { filePicker.launch(arrayOf("*/*")) } }
        root.addView(transferButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(label("Keep both phones awake and accept Android's connection prompt. Only accept files you trust.", 13f))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT <= 32 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray()) else discoverPeers()
    }
    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT >= 33 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun discoverPeers() {
        status.text = "Searching for nearby Wi-Fi Direct devices…"
        try { manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { runOnUiThread { status.text = "Discovery started. Keep this screen open." } }
            override fun onFailure(reason: Int) { runOnUiThread { status.text = "Discovery failed (code $reason). Check Wi-Fi and permission." } }
        }) } catch (e: SecurityException) { status.text = "Permission missing: ${e.message}" }
    }
    private fun updatePeers(list: WifiP2pDeviceList) {
        peers.clear(); peers.addAll(list.deviceList)
        runOnUiThread {
            peersBox.removeAllViews()
            if (peers.isEmpty()) peersBox.addView(TextView(this).apply { text = "No devices found yet. Tap Find nearby devices on both phones."; setTextColor(0xFFCDD2D8.toInt()) })
            peers.forEach { device -> peersBox.addView(Button(this).apply {
                text = "${device.deviceName.ifBlank { "Android device" }} · ${device.statusLabel()}"
                setOnClickListener { connectTo(device) }
            }) }
        }
    }
    private fun WifiP2pDevice.statusLabel(): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Nearby"
    }
    private fun connectTo(device: WifiP2pDevice) {
        status.text = "Connecting to ${device.deviceName}…"
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { runOnUiThread { status.text = "Connection request sent. Accept the system prompt on both phones." } }
            override fun onFailure(reason: Int) { runOnUiThread { status.text = "Could not connect (code $reason). Try again." } }
        })
    }
    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        groupOwner = info.isGroupOwner
        ownerAddress = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
        runOnUiThread {
            status.text = if (groupOwner) "Connected. This phone is group owner; it can receive files. To send, reconnect with the other phone as owner." else "Connected to group owner. You can send a file to that phone."
            transferButton.isEnabled = !groupOwner
        }
        if (groupOwner) startReceiverServer()
    }
    private fun prepareFile(uri: Uri) {
        try {
            val nameAndSize = queryFile(uri)
            chosenName = TransferProtocol.safeName(nameAndSize.first)
            chosenSize = nameAndSize.second
            TransferProtocol.validateSize(chosenSize)
            chosenMime = contentResolver.getType(uri) ?: "application/octet-stream"
            chosenUri = uri
            status.text = "Ready to send: $chosenName (${chosenSize} bytes). Tap Choose file to send again to select another."
            sendFile(uri)
        } catch (e: Exception) { say("Cannot use this file: ${e.message}") }
    }
    private fun queryFile(uri: Uri): Pair<String, Long> {
        var name = "shared-file"; var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (n >= 0) name = cursor.getString(n) ?: name
                val s = cursor.getColumnIndex(OpenableColumns.SIZE); if (s >= 0) size = cursor.getLong(s)
            }
        }
        require(size >= 0) { "The provider did not report the file size" }
        return name to size
    }
    private fun sendFile(uri: Uri) {
        val name = chosenName; val size = chosenSize; val mime = chosenMime
        Thread {
            try {
                val socket = Socket(); activeSocket = socket
                socket.connect(InetSocketAddress(ownerAddress, TransferProtocol.PORT), 12000); socket.soTimeout = 0
                val out = DataOutputStream(socket.getOutputStream())
                TransferProtocol.writeHeader(out, name, size, mime)
                val response = DataInputStream(socket.getInputStream()).readUTF()
                if (response != "ACCEPT") throw IllegalStateException("Receiver declined the transfer")
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not read selected file")
                val digest = input.use { TransferProtocol.copyAndDigest(it, socket.getOutputStream(), size) { done ->
                    runOnUiThread { progress.progress = if (size == 0L) 100 else ((done * 100) / size).toInt() }
                } }
                out.writeUTF(digest.joinToString("") { "%02x".format(it) }); out.flush()
                val result = DataInputStream(socket.getInputStream()).readUTF()
                runOnUiThread { status.text = if (result == "OK") "Transfer complete: $name" else "Transfer verification failed."; if (result == "OK") progress.progress = 100 }
                socket.close(); activeSocket = null
            } catch (e: Exception) { runOnUiThread { status.text = "Send failed: ${e.message ?: e.javaClass.simpleName}. Check connection and try again." } }
        }.start()
    }
    private fun startReceiverServer() {
        if (serverThread?.isAlive == true) return
        serverThread = Thread {
            try {
                val listener = ServerSocket(TransferProtocol.PORT); server = listener
                while (!stopped.get()) {
                    val socket = listener.accept(); activeSocket = socket
                    Thread { receiveOne(socket) }.start()
                }
            } catch (e: Exception) { if (!stopped.get()) runOnUiThread { status.text = "Receiver stopped: ${e.message}" } }
        }.apply { name = "AirBridge-receiver"; start() }
    }
    private fun receiveOne(socket: Socket) {
        var temp: File? = null
        try {
            socket.soTimeout = 30000
            val input = DataInputStream(socket.getInputStream())
            val header = TransferProtocol.readHeader(input)
            val decision = CountDownLatch(1); val accept = AtomicBoolean(false)
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Incoming file")
                    .setMessage("${header.name}\n${header.size} bytes\nType: ${header.mime}\n\nAccept this file?")
                    .setNegativeButton("Decline") { _, _ -> accept.set(false); decision.countDown() }
                    .setPositiveButton("Accept") { _, _ -> accept.set(true); decision.countDown() }
                    .setOnCancelListener { accept.set(false); decision.countDown() }.show()
            }
            if (!decision.await(90, TimeUnit.SECONDS) || !accept.get()) {
                DataOutputStream(socket.getOutputStream()).writeUTF("DECLINE"); socket.close(); return
            }
            DataOutputStream(socket.getOutputStream()).writeUTF("ACCEPT")
            val dir = File(cacheDir, "incoming").apply { mkdirs() }
            temp = File.createTempFile("airbridge-", ".part", dir)
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(TransferProtocol.BUFFER_SIZE); var remaining = header.size; var done = 0L
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw java.io.EOFException("Sender disconnected before file completed")
                    if (count == 0) continue
                    output.write(buffer, 0, count); digest.update(buffer, 0, count); remaining -= count; done += count
                    runOnUiThread { progress.progress = if (header.size == 0L) 100 else ((done * 100) / header.size).toInt() }
                }
            }
            val senderDigest = DataInputStream(socket.getInputStream()).readUTF()
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
            if (!MessageDigest.isEqual(senderDigest.toByteArray(), actualDigest.toByteArray())) throw IllegalStateException("SHA-256 mismatch")
            val saved = saveToDownloads(temp, header.name, header.mime)
            DataOutputStream(socket.getOutputStream()).writeUTF("OK")
            runOnUiThread { status.text = "Received and verified: ${header.name}"; say("Saved to Downloads/AirBridgeTouch/${header.name}") }
            temp = null
        } catch (e: Exception) {
            try { DataOutputStream(socket.getOutputStream()).writeUTF("ERROR") } catch (_: Exception) { }
            runOnUiThread { status.text = "Receive failed: ${e.message ?: e.javaClass.simpleName}" }
        } finally { try { temp?.delete() } catch (_: Exception) { }; try { socket.close() } catch (_: Exception) { }; activeSocket = null }
    }
    private fun saveToDownloads(file: File, name: String, mime: String): Uri? {
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirBridgeTouch")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Could not create Downloads entry")
        try { contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("Could not write output") }
        catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
        return uri
    }
    private fun say(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        stopped.set(true); try { unregisterReceiver(receiver) } catch (_: Exception) { }
        try { activeSocket?.close() } catch (_: Exception) { }; try { server?.close() } catch (_: Exception) { }
        super.onDestroy()
    }
}
