package com.vinz.cloudphone.server

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context
import java.io.File
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/** Screen capture via MediaProjection (no adb, no root). */
class CaptureService : Service() {
    companion object {
        @Volatile var reader: ImageReader? = null
        var w = 0
        var h = 0
        var sx = 1f
        var sy = 1f
        @Volatile var last: ByteArray? = null

        @Synchronized
        fun jpeg(): ByteArray? {
            val img = (try { reader?.acquireLatestImage() } catch (e: Exception) { null }) ?: return last
            val p = img.planes[0]
            val full = Bitmap.createBitmap(p.rowStride / p.pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer); full.setHasAlpha(false)
            img.close()
            val bm = Bitmap.createBitmap(full, 0, 0, w, h)
            val o = ByteArrayOutputStream()
            bm.compress(Bitmap.CompressFormat.JPEG, 45, o)
            full.recycle(); bm.recycle()
            last = o.toByteArray()
            return last
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() { Tunnel.stop(); reader?.close(); reader = null; super.onDestroy() }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("cp", "Cloud Phone", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "cp")
        } else Notification.Builder(this)
        val n = b.setContentTitle("Cloud Phone aktif").setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startFg()
        val data = i?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val rc = i.getIntExtra("rc", -1)
        val tok = i.getStringExtra("tok") ?: ""
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cp:srv").acquire()

        val mp = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(rc, data)
        mp.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val sc = minOf(1f, 540f / dm.widthPixels)
        w = (dm.widthPixels * sc).toInt() and 1.inv()
        h = (dm.heightPixels * sc).toInt() and 1.inv()
        sx = dm.widthPixels / w.toFloat()
        sy = dm.heightPixels / h.toFloat()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        mp.createVirtualDisplay("cp", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        Http.start(tok)
        Tunnel.start(this)
        return START_NOT_STICKY
    }
}

/** Touch/key injection via Accessibility (no adb, no root). */
class InputService : AccessibilityService() {
    companion object { @Volatile var inst: InputService? = null }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(i: Intent?): Boolean { inst = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, d: Long) {
        val p = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, d.coerceAtLeast(1L))).build()
        dispatchGesture(g, null, null)
    }

    fun key(k: Int) {
        when (k) {
            4 -> performGlobalAction(GLOBAL_ACTION_BACK)
            3 -> performGlobalAction(GLOBAL_ACTION_HOME)
            187 -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    fun type(s: String) {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val cur = n.text?.toString() ?: ""
        val b = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + s)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }
}

/** Tiny HTTP server, same protocol as the adb version: GET /frame, POST /input. */
object Http {
    @Volatile private var running = false
    @Volatile private var token = ""

    fun start(tok: String, port: Int = 8080) {
        token = tok
        if (running) return
        running = true
        Thread {
            try {
                val ss = ServerSocket(port)
                while (true) { val s = ss.accept(); Thread { handle(s) }.start() }
            } catch (e: Exception) { running = false }
        }.start()
    }

    private fun send(s: Socket, code: Int, ct: String, b: ByteArray) {
        val o = s.getOutputStream()
        o.write("HTTP/1.1 $code X\r\nContent-Type: $ct\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(b); o.flush()
    }

    private fun handle(s: Socket) {
        try {
            s.use {
                val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val parts = (br.readLine() ?: return).split(" ")
                val target = parts[1]
                var len = 0
                while (true) {
                    val l = br.readLine() ?: break
                    if (l.isEmpty()) break
                    if (l.startsWith("content-length:", true)) len = l.substring(15).trim().toInt()
                }
                val buf = CharArray(len)
                var o = 0
                while (o < len) { val r = br.read(buf, o, len - o); if (r < 0) break; o += r }
                val body = String(String(buf).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                val t = Regex("token=([^&]+)").find(target)?.groupValues?.get(1)
                if (t != token) return send(s, 401, "text/plain", ByteArray(0))
                when (target.substringBefore('?')) {
                    "/frame" -> {
                        val j = CaptureService.jpeg()
                        if (j == null) send(s, 503, "text/plain", "no frame".toByteArray())
                        else send(s, 200, "image/jpeg", j)
                    }
                    "/input" -> { input(JSONObject(body)); send(s, 200, "text/plain", "ok".toByteArray()) }
                    else -> send(s, 200, "text/plain", "ok".toByteArray())
                }
            }
        } catch (e: Exception) {}
    }

    private fun input(j: JSONObject) {
        val svc = InputService.inst ?: return
        val sx = CaptureService.sx
        val sy = CaptureService.sy
        when (j.getString("t")) {
            "tap" -> { val x = j.getInt("x") * sx; val y = j.getInt("y") * sy; svc.swipe(x, y, x, y, 50) }
            "swipe" -> svc.swipe(j.getInt("x1") * sx, j.getInt("y1") * sy, j.getInt("x2") * sx, j.getInt("y2") * sy, j.optLong("d", 200))
            "key" -> svc.key(j.getInt("k"))
            "text" -> svc.type(j.getString("s"))
        }
    }
}

/** Menjalankan binary ngrok (libngrok.so dari APK) -> tunnel ke port 8080 dengan domain static. */
object Tunnel {
    @Volatile var log = "ngrok belum jalan"
    @Volatile private var on = false
    @Volatile private var proc: Process? = null

    private fun proxy(): Int {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (true) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        c.use {
                            val br = java.io.BufferedReader(java.io.InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
                            val parts = (br.readLine() ?: return@Thread).split(" ")
                            while (true) { val l = br.readLine() ?: break; if (l.isEmpty()) break }
                            val o = c.getOutputStream()
                            if (parts.size < 2 || parts[0] != "GET" || !parts[1].startsWith("http://")) {
                                o.write("HTTP/1.1 502 X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                return@Thread
                            }
                            val cn = java.net.URL(parts[1]).openConnection() as java.net.HttpURLConnection
                            cn.connectTimeout = 10000; cn.readTimeout = 10000
                            val body = cn.inputStream.use { it.readBytes() }
                            o.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            o.write(body); o.flush()
                        }
                    } catch (e: Exception) {}
                }.start()
            }
        }.start()
        return ss.localPort
    }

    fun start(ctx: Context) {
        if (on) return
        on = true
        val app = ctx.applicationContext
        val p = app.getSharedPreferences("cp", Context.MODE_PRIVATE)
        val auth = (p.getString("ngrok_token", "") ?: "").trim()
        val dom = (p.getString("ngrok_domain", "") ?: "").trim().removePrefix("https://").trim('/')
        val cfg = File(app.filesDir, "ngrok.yml")
        cfg.writeText("version: \"2\"\nauthtoken: $auth\nupdate_check: false\ndns_resolver_ips:\n  - 8.8.8.8\n  - 1.1.1.1\n")
        val bin = File(app.applicationInfo.nativeLibraryDir, "libngrok.so")
        val pport = proxy()
        log = "menjalankan ngrok (proxy :$pport)..."
        Thread {
            while (on) {
                try {
                    val pb = ProcessBuilder(bin.absolutePath, "http", "8080",
                        "--url=https://$dom", "--config=${cfg.absolutePath}", "--log=stdout")
                    pb.environment()["HOME"] = app.filesDir.absolutePath
                    pb.environment()["HTTP_PROXY"] = "http://127.0.0.1:$pport"
                    pb.environment()["http_proxy"] = "http://127.0.0.1:$pport"
                    pb.redirectErrorStream(true)
                    val pr = pb.start()
                    proc = pr
                    pr.inputStream.bufferedReader().forEachLine { log = (log + "\n" + it).takeLast(1500) }
                } catch (e: Exception) {
                    log = (log + "\nERR: " + e.message).takeLast(1500)
                }
                try { Thread.sleep(3000) } catch (_: Exception) {}
            }
        }.start()
    }

    fun stop() { on = false; proc?.destroy(); log = "ngrok berhenti" }
}
