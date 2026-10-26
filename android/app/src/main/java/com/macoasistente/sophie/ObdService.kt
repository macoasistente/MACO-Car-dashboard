package com.macoasistente.sophie

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/**
 * Owns the Bluetooth link to the OBD2 adapter and keeps polling the car, so data keeps
 * flowing while the phone is locked or Android Auto is showing the car screen.
 */
@SuppressLint("MissingPermission")
class ObdService : Service() {

    companion object {
        const val ACTION_CONNECT = "connect" // extra "address"
        const val ACTION_DISCONNECT = "disconnect"
        const val ACTION_SCAN_CODES = "scan"
        const val ACTION_CLEAR_CODES = "clear"
        const val ACTION_START = "start" // reconnect to the saved adapter if there is one
        private const val CHANNEL = "link"
        const val ALERT_CHANNEL = "alerts"
        private const val NOTIF_ID = 1
        private val VLINK_SERVICE = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun send(ctx: Context, action: String, address: String? = null) {
            val i = Intent(ctx, ObdService::class.java).setAction(action)
            if (address != null) i.putExtra("address", address)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                Hub.log("Couldn't start the Bluetooth service: ${e.message}")
            }
        }

        fun savedAddress(ctx: Context): String? =
            ctx.getSharedPreferences("sophie", Context.MODE_PRIVATE).getString("address", null)

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Car alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var device: BluetoothDevice? = null
    private var userStopped = false
    private var session: Job? = null

    // GATT callbacks complete these
    private var connected: CompletableDeferred<Boolean>? = null
    private var discovered: CompletableDeferred<Boolean>? = null
    private var descWritten: CompletableDeferred<Boolean>? = null
    private var charWritten: CompletableDeferred<Boolean>? = null

    // ELM327 command/response
    private val cmdLock = Mutex()
    private val buf = StringBuilder()
    private var waiter: CompletableDeferred<String>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        startInForeground("Getting ready")
        scope.launch {
            Hub.state.map { Triple(it.mode, it.waiting, it.adapter) }.distinctUntilChanged().collectLatest { (mode, waiting, _) ->
                val text = when {
                    mode == "live" -> "Connected to ${Hub.info.value.name}"
                    mode == "connecting" -> "Connecting…"
                    waiting -> "Waiting for ${Hub.info.value.name} to start"
                    else -> "Not connected"
                }
                // update the text only; starting the foreground service again from the background isn't allowed
                try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text)) } catch (_: Exception) {}
            }
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startInForeground(text: String) {
        val n = notification(text)
        val bt = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val loc = if (Build.VERSION.SDK_INT >= 29 && hasLocation()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            // location lets the parking spot be saved with the phone locked
            ServiceCompat.startForeground(this, NOTIF_ID, n, bt or loc)
        } catch (e: Exception) {
            try { ServiceCompat.startForeground(this, NOTIF_ID, n, bt) } catch (e2: Exception) { Hub.log("Foreground start failed: ${e2.message}") }
        }
    }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Saves where the car is when the adapter goes quiet (engine off). */
    private fun saveParking() {
        if (!getSharedPreferences("sophie", MODE_PRIVATE).getBoolean("autoPark", true) || !hasLocation()) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val store = { l: Location? ->
            val p = JSONObject().put("t", System.currentTimeMillis())
            if (l != null) p.put("lat", l.latitude).put("lng", l.longitude).put("acc", l.accuracy.toDouble())
            getSharedPreferences("sophie", MODE_PRIVATE).edit().putString("park", p.toString()).apply()
            Hub.park.value = p.toString()
            Hub.log(if (l != null) "Parking spot saved" else "Parking: no GPS fix")
        }
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { l -> store(l ?: lm.getLastKnownLocation(provider)) }
            } else {
                store(lm.getLastKnownLocation(provider))
            }
        } catch (e: Exception) {
            Hub.log("Parking: location unavailable")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra("address")?.let { connectTo(it) }
            ACTION_DISCONNECT -> disconnect()
            ACTION_SCAN_CODES -> scope.launch { if (Hub.state.value.mode == "live") scanCodes() }
            ACTION_CLEAR_CODES -> scope.launch { if (Hub.state.value.mode == "live") clearCodes() }
            else -> if (gatt == null) savedAddress(this)?.let { connectTo(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        session?.cancel()
        try { gatt?.close() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- connection

    private fun connectTo(address: String) {
        val bm = getSystemService(BluetoothManager::class.java)
        val ad = bm?.adapter
        if (ad == null || !ad.isEnabled) { Hub.log("Bluetooth is off"); return }
        if (Hub.state.value.mode == "live" && device?.address == address) return
        userStopped = false
        getSharedPreferences("sophie", MODE_PRIVATE).edit().putString("address", address).apply()
        closeGatt()
        device = ad.getRemoteDevice(address)
        session = scope.launch { runSession(fresh = true) }
    }

    private fun disconnect() {
        userStopped = true
        session?.cancel()
        closeGatt()
        Hub.update { it.copy(mode = "idle", waiting = false) }
        Hub.log("Disconnected")
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; rx = null; tx = null
    }

    /** Connect, set up the adapter and poll until the link drops. Then wait for it to come back. */
    private suspend fun runSession(fresh: Boolean) {
        var first = fresh
        while (currentCoroutineContext().isActive && !userStopped) {
            val dev = device ?: return
            Hub.update { it.copy(mode = if (first) "connecting" else it.mode, waiting = !first) }
            val ok = try {
                openLink(dev, autoConnect = !first)
                initAdapter()
                true
            } catch (e: Exception) {
                Hub.log((if (first) "Connect failed: " else "Reconnect failed: ") + e.message)
                false
            }
            if (ok) {
                Hub.update { it.copy(mode = "live", waiting = false, distKm = 0.0, vals = emptyMap()) }
                Hub.log("Connected")
                try { pollLoop() } catch (e: Exception) { Hub.log("Link lost: ${e.message}") }
                if (userStopped) return
                Hub.log("Adapter disconnected, waiting for it to come back")
                saveParking()
            }
            closeGatt()
            Hub.update { it.copy(mode = "idle", waiting = true) }
            first = false
            delay(3000)
        }
    }

    private suspend fun openLink(dev: BluetoothDevice, autoConnect: Boolean) {
        linkDown = false
        connected = CompletableDeferred()
        Hub.log(if (autoConnect) "Waiting for ${dev.name ?: "adapter"}…" else "Connecting to ${dev.name ?: dev.address}")
        gatt = dev.connectGatt(this, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        // autoConnect waits until the adapter is in range (car turned on): no timeout then
        val up = if (autoConnect) connected!!.await() else withTimeout(15000) { connected!!.await() }
        if (!up) throw Exception("adapter didn't answer")
        val g = gatt ?: throw Exception("closed")
        discovered = CompletableDeferred()
        g.discoverServices()
        withTimeout(10000) { discovered!!.await() }

        var r: BluetoothGattCharacteristic? = null
        var t: BluetoothGattCharacteristic? = null
        val services = g.services.sortedByDescending { it.uuid == VLINK_SERVICE }
        for (s in services) {
            var cr: BluetoothGattCharacteristic? = null
            var ct: BluetoothGattCharacteristic? = null
            for (c in s.characteristics) {
                val p = c.properties
                if (cr == null && p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) cr = c
                if (ct == null && p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) ct = c
            }
            if (cr != null && ct != null) { r = cr; t = ct; Hub.log("Using service ${s.uuid}"); break }
        }
        if (r == null || t == null) throw Exception("this device doesn't look like an OBD2 adapter")
        rx = r; tx = t
        g.setCharacteristicNotification(r, true)
        r.getDescriptor(CCCD)?.let { d ->
            val v = if (r.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            descWritten = CompletableDeferred()
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v)
            else @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
            withTimeoutOrNull(3000) { descWritten!!.await() }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) connected?.complete(true)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected?.complete(false)
                waiter?.completeExceptionally(Exception("disconnected"))
                linkDown = true
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { discovered?.complete(status == BluetoothGatt.GATT_SUCCESS) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { descWritten?.complete(true) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { charWritten?.complete(true) }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = onData(value)

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { onData(it) }
        }
    }

    @Volatile private var linkDown = false

    private fun onData(bytes: ByteArray) {
        synchronized(buf) {
            buf.append(String(bytes, Charsets.ISO_8859_1))
            val i = buf.indexOf(">")
            if (i >= 0) {
                val out = buf.substring(0, i)
                buf.delete(0, i + 1)
                waiter?.complete(out)
            }
        }
    }

    // ---------------------------------------------------------------- ELM327

    private suspend fun send(cmd: String, timeout: Long = 3500): String = cmdLock.withLock {
        if (linkDown) throw Exception("disconnected")
        val g = gatt ?: throw Exception("not connected")
        val c = tx ?: throw Exception("not connected")
        val w = CompletableDeferred<String>()
        synchronized(buf) { buf.setLength(0); waiter = w }
        val data = (cmd + "\r").toByteArray(Charsets.ISO_8859_1)
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var i = 0
        while (i < data.size) {
            val chunk = data.copyOfRange(i, minOf(i + 20, data.size))
            i += 20
            charWritten = CompletableDeferred()
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, chunk, type)
            else @Suppress("DEPRECATION") run { c.writeType = type; c.value = chunk; g.writeCharacteristic(c) }
            withTimeoutOrNull(1000) { charWritten!!.await() }
        }
        val raw = try {
            withTimeout(timeout) { w.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw Exception("timeout $cmd")
        } finally {
            waiter = null
        }
        raw.replace('\r', '\n').split('\n').map { it.trim() }
            .filter { it.isNotEmpty() && it != cmd && !it.startsWith("SEARCHING", ignoreCase = true) }
            .joinToString("\n")
    }

    private suspend fun trySend(cmd: String, timeout: Long = 3500): String =
        try { send(cmd, timeout) } catch (e: Exception) { if (linkDown) throw e; "" }

    private suspend fun initAdapter() {
        val ver = trySend("ATZ", 6000)
        Hub.log("Adapter: " + (ver.split('\n').lastOrNull()?.ifBlank { null } ?: "ELM327"))
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST64")) trySend(c)
        // Kia Soul: ISO 15765 CAN 11-bit 500k (protocol 6); fall back to automatic
        trySend("ATSP6")
        val ok = Regex("41\\s?00")
        var r = trySend("0100", 8000)
        if (!ok.containsMatchIn(r)) { Hub.log("Protocol 6 failed, trying auto"); trySend("ATSP0"); r = trySend("0100", 12000) }
        if (!ok.containsMatchIn(r)) throw Exception("the adapter answered but the car didn't. Is the engine on?")
        val proto = trySend("ATDPN").removePrefix("A")
        Hub.log("Protocol $proto")

        val sup = mutableSetOf<String>()
        var base = 0
        var resp = r
        while (true) {
            val b = Obd.hexBytes(resp, "41", "%02X".format(base))
            if (b == null || b.size < 4) break
            val mask = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
            for (k in 0 until 32) if (mask and (1L shl (31 - k)) != 0L) sup.add("%02X".format(base + k + 1))
            if (mask and 1L == 0L || base >= 0xA0) break
            base += 0x20
            resp = trySend("01" + "%02X".format(base))
        }
        Hub.log("Car reports ${sup.size} data points")
        val volt = trySend("ATRV")
        var vin = ""
        val v = Obd.joinFrames(trySend("0902", 5000))
        val vi = v.indexOf("4902")
        if (vi >= 0) {
            val sb = StringBuilder()
            var j = vi + 6
            while (j + 1 < v.length) {
                val ch = v.substring(j, j + 2).toIntOrNull(16) ?: 0
                if (ch in 48..90) sb.append(ch.toChar())
                j += 2
            }
            vin = sb.toString().takeLast(17)
        }
        @Suppress("MissingPermission")
        val name = device?.name ?: "OBD2 adapter"
        Hub.update { it.copy(supported = sup, protocol = proto, adapter = name, volt = volt, vin = vin) }
    }

    private suspend fun readPid(p: Pid) {
        val b = Obd.hexBytes(send("01" + p.pid), "41", p.pid) ?: return
        if (b.isEmpty()) return
        val x = p.f(b[0], b.getOrElse(1) { 0 }, b.getOrElse(2) { 0 }, b.getOrElse(3) { 0 })
        Hub.update { it.copy(vals = it.vals + (p.id to x)) }
    }

    private suspend fun readMil() {
        val b = Obd.hexBytes(send("0101"), "41", "01") ?: return
        if (b.isEmpty()) return
        Hub.update { it.copy(codes = it.codes.copy(milOn = b[0] and 0x80 != 0, milCount = b[0] and 0x7f)) }
    }

    private suspend fun pollLoop() {
        val sup = Hub.state.value.supported
        val use = Obd.PIDS.filter { sup == null || it.pid in sup }
        val fast = use.filter { it.fast }
        val slow = use.filter { !it.fast }
        var si = 0
        var cycle = 0
        var last = System.nanoTime()
        scanCodes()
        readCluster()
        while (currentCoroutineContext().isActive && !userStopped && !linkDown) {
            try {
                for (p in fast) readPid(p)
                repeat(minOf(2, slow.size)) { readPid(slow[si++ % slow.size]) }
                if (cycle++ % 40 == 0) readMil()
                if (cycle % 1500 == 0) readCluster()
                val now = System.nanoTime()
                val dtH = (now - last) / 3.6e12
                last = now
                val spd = Hub.state.value.vals["speed"] ?: 0.0
                if (dtH in 0.0..0.01) Hub.update { it.copy(distKm = it.distKm + spd * dtH) }
            } catch (e: Exception) {
                if (linkDown) throw e
                Hub.log("Read error: ${e.message}")
                delay(400)
            }
        }
        if (linkDown) throw Exception("disconnected")
    }

    private suspend fun scanCodes() {
        Hub.update { it.copy(scanning = true) }
        try {
            readMil()
            val p = Hub.state.value.protocol
            val stored = Obd.parseDTCs(send("03", 6000), "43", p)
            val pending = Obd.parseDTCs(send("07", 6000), "47", p)
            val perm = Obd.parseDTCs(trySend("0A", 6000), "4A", p)
            Hub.update { it.copy(codes = it.codes.copy(stored = stored, pending = pending, permanent = perm, scanned = true)) }
            Hub.log("Codes: ${stored.joinToString(",").ifEmpty { "none" }} | pending ${pending.joinToString(",").ifEmpty { "none" }}")
        } catch (e: Exception) {
            if (linkDown) throw e
            Hub.log("Scan failed: ${e.message}")
        } finally {
            Hub.update { it.copy(scanning = false) }
        }
    }

    private suspend fun clearCodes() {
        try { send("04", 6000); Hub.log("Codes cleared") } catch (e: Exception) { Hub.log("Clear failed: ${e.message}") }
        scanCodes()
    }

    /** Kia instrument cluster odometer. The page decides whether the answer is trustworthy. */
    private suspend fun readCluster() {
        try {
            send("ATSH7C6")
            val r = send("22B002", 4000)
            send("ATSH7DF")
            Hub.cluster.tryEmit(r)
        } catch (e: Exception) {
            if (linkDown) throw e
            Hub.log("Cluster odometer not available")
            trySend("ATSH7DF")
        }
    }
}
