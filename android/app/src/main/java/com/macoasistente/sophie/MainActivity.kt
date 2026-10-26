package com.macoasistente.sophie

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Phone screen: the same dashboard as the website, with Bluetooth handled by [ObdService]. */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pageReady = false

    private val perms: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { afterPermissions() }
    private val askBtOn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { afterPermissions() }
    private var pendingPick = false

    // <input type="file"> (car photo, restore a backup)
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    // "Save a backup"
    private var pendingSave: String? = null
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = pendingSave
        pendingSave = null
        if (uri == null || t == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(t.toByteArray()) }
            Toast.makeText(this, "Backup saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't save the backup", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(FlowPreview::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObdService.ensureChannels(this)
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = true
        }
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.setBackgroundColor(0xFF000000.toInt())
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                // maps links etc. open in their own apps
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pushSnap(Hub.state.value)
                Hub.park.value?.let { js("park", JSONObject().put("type", "park").put("p", JSONObject(it))) }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, true)
            }

            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    pickFile.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "SophieNative")
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })

        lifecycleScope.launch {
            Hub.state.sample(300).collect { s ->
                pushSnap(s)
                if (s.mode == "live") window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycleScope.launch { Hub.logs.collect { js("log", JSONObject().put("type", "log").put("text", it)) } }
        if (Hub.park.value == null) Hub.park.value = getSharedPreferences("sophie", MODE_PRIVATE).getString("park", null)
        lifecycleScope.launch { Hub.park.collect { p -> if (p != null) js("park", JSONObject().put("type", "park").put("p", JSONObject(p))) } }
        lifecycleScope.launch { Hub.cluster.collect { js("cluster", JSONObject().put("type", "cluster").put("resp", it)) } }

        if (hasPerms()) afterPermissions() else askPerms.launch(perms)
        ContextCompat.registerReceiver(this, downloadDone, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        checkForUpdate()
    }

    private var apkUrl = "https://github.com/macoasistente/MACO-Car-dashboard/releases/download/android-latest/Sophie.apk"
    private var updateId = -1L
    private var wantUpdate = false

    override fun onResume() {
        super.onResume()
        // back from the "install unknown apps" setting
        if (wantUpdate && packageManager.canRequestPackageInstalls()) { wantUpdate = false; downloadUpdate() }
    }

    /** Downloads the new version inside the app, then hands it to Android's installer. */
    private fun downloadUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow MyDash to install updates, then come back", Toast.LENGTH_LONG).show()
            wantUpdate = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }
        try {
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "MyDash-update.apk").delete() }
            val dm = getSystemService(DownloadManager::class.java)
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("MyDash update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "MyDash-update.apk")
            updateId = dm.enqueue(req)
            Toast.makeText(this, "Downloading the update…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start the download", Toast.LENGTH_LONG).show()
        }
    }

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != updateId) return
            val dm = getSystemService(DownloadManager::class.java)
            val uri = dm.getUriForDownloadedFile(id)
            if (uri == null) { Toast.makeText(ctx, "The update didn't download. Try again later.", Toast.LENGTH_LONG).show(); return }
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                Toast.makeText(ctx, "Open the MyDash update from your notifications to install it", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadDone) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Asks GitHub whether a newer build of the app exists and offers to download it. */
    private fun checkForUpdate() = lifecycleScope.launch(Dispatchers.IO) {
        try {
            val c = URL("https://api.github.com/repos/macoasistente/MACO-Car-dashboard/releases/tags/android-latest").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val build = Regex("build (\\d+)").find(j.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: return@launch
            j.optJSONArray("assets")?.let { assets ->
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").startsWith("MyDash-")) apkUrl = asset.optString("browser_download_url", apkUrl)
                }
            }
            val prefs = getSharedPreferences("sophie", MODE_PRIVATE)
            if (build <= BuildConfig.VERSION_CODE || prefs.getInt("skipBuild", 0) == build) return@launch
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update available")
                    .setMessage("MyDash 1.$build is ready. Tap Update, then Install. Your data stays.")
                    .setPositiveButton("Update") { _, _ -> downloadUpdate() }
                    .setNegativeButton("Later") { _, _ -> prefs.edit().putInt("skipBuild", build).apply() }
                    .show()
            }
        } catch (_: Exception) {
        }
    }

    private fun pushSnap(s: Hub.Snap) {
        if (!pageReady) return
        web.evaluateJavascript("window.__sophieNative&&window.__sophieNative({type:'snap',snap:${Hub.snapJson(s)}})", null)
    }

    private fun js(@Suppress("UNUSED_PARAMETER") kind: String, o: JSONObject) {
        if (!pageReady) return
        web.evaluateJavascript("window.__sophieNative&&window.__sophieNative($o)", null)
    }

    private fun hasPerms() = perms.filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun btAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    private fun afterPermissions() {
        if (!hasPerms()) { Hub.log("Bluetooth permission is needed to talk to the car"); return }
        val ad = btAdapter()
        if (ad == null) { Hub.log("This phone has no Bluetooth"); return }
        if (!ad.isEnabled) {
            try { askBtOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            return
        }
        if (pendingPick) { pendingPick = false; pickAdapter(); return }
        // keep the service running so it reconnects whenever the car starts
        ObdService.send(this, ObdService.ACTION_START)
    }

    /** Lists nearby Bluetooth LE devices; OBD adapters first. Remembered after the first pick. */
    private fun pickAdapter() {
        val scanner = btAdapter()?.bluetoothLeScanner ?: run { Hub.log("Bluetooth is off"); return }
        val found = linkedMapOf<String, String>()
        val labels = ArrayAdapter<String>(this, android.R.layout.select_dialog_item)
        val obdLike = Regex("vlink|obd|icar|elm|vgate|v-link", RegexOption.IGNORE_CASE)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                val addr = result.device.address
                if (found.containsKey(addr)) return
                found[addr] = name
                runOnUiThread {
                    labels.clear()
                    found.entries.sortedByDescending { obdLike.containsMatchIn(it.value) }
                        .forEach { labels.add(it.value + "  ·  " + it.key.takeLast(5)) }
                }
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose your OBD2 adapter")
            .setAdapter(labels) { _, which ->
                val label = labels.getItem(which) ?: return@setAdapter
                val addr = found.entries.firstOrNull { label.endsWith(it.key.takeLast(5)) && label.startsWith(it.value) }?.key
                if (addr != null) ObdService.send(this, ObdService.ACTION_CONNECT, addr)
            }
            .setNegativeButton("Cancel") { _, _ -> Hub.update { it.copy(mode = "idle") } }
            .setOnDismissListener { try { scanner.stopScan(cb) } catch (_: Exception) {} }
            .create()
        dialog.show()
    }

    private inner class Bridge {
        @JavascriptInterface fun isNative() = true

        @JavascriptInterface fun connect() = runOnUiThread {
            val saved = ObdService.savedAddress(this@MainActivity)
            if (!hasPerms()) { pendingPick = saved == null; askPerms.launch(perms); return@runOnUiThread }
            if (saved != null) ObdService.send(this@MainActivity, ObdService.ACTION_CONNECT, saved) else pickAdapter()
        }

        /** Forget the saved adapter and show the list again. */
        @JavascriptInterface fun chooseAdapter() = runOnUiThread {
            getSharedPreferences("sophie", MODE_PRIVATE).edit().remove("address").apply()
            if (hasPerms()) pickAdapter() else { pendingPick = true; askPerms.launch(perms) }
        }

        @JavascriptInterface fun disconnect() = ObdService.send(this@MainActivity, ObdService.ACTION_DISCONNECT)
        @JavascriptInterface fun scanCodes() = ObdService.send(this@MainActivity, ObdService.ACTION_SCAN_CODES)
        @JavascriptInterface fun clearCodes() = ObdService.send(this@MainActivity, ObdService.ACTION_CLEAR_CODES)

        @JavascriptInterface fun setInfo(json: String) { Hub.parseInfo(json)?.let { Hub.info.value = it } }

        @JavascriptInterface fun setAutoPark(on: Boolean) {
            getSharedPreferences("sophie", MODE_PRIVATE).edit().putBoolean("autoPark", on).apply()
        }

        @JavascriptInterface fun setPhoto(dataUrl: String) {
            try {
                val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
                Hub.photo.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {}
        }

        @JavascriptInterface fun saveFile(name: String, text: String) = runOnUiThread {
            pendingSave = text
            createDoc.launch(name)
        }

        @JavascriptInterface fun notify(title: String, body: String, tag: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val n = NotificationCompat.Builder(this@MainActivity, ObdService.ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this@MainActivity).notify(tag.hashCode(), n)
        }

        @JavascriptInterface fun notifyAllowed(): Boolean = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        @JavascriptInterface fun requestNotify() = runOnUiThread { askPerms.launch(perms) }
    }
}
