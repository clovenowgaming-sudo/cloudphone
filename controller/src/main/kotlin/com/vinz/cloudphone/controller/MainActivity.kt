package com.vinz.cloudphone.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private var rv: RemoteView? = null

    override fun onCreate(b: Bundle?) { super.onCreate(b); login() }
    override fun onDestroy() { rv?.stop(); super.onDestroy() }

    private fun login() {
        rv?.stop(); rv = null
        val p = getSharedPreferences("cp", Context.MODE_PRIVATE)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val u = EditText(this).apply { hint = "URL server (https://nama.ngrok-free.app)"; setText(p.getString("u", "")) }
        val t = EditText(this).apply { hint = "Token dari Server APK"; setText(p.getString("t", "")) }
        val b = Button(this).apply { text = "Connect" }
        l.addView(TextView(this).apply { text = "Cloud Phone Control"; textSize = 26f })
        l.addView(u); l.addView(t); l.addView(b)
        b.setOnClickListener {
            var url = u.text.toString().trim().trimEnd('/')
            if (!url.startsWith("http")) url = "https://$url"
            val tok = t.text.toString().trim()
            p.edit().putString("u", url).putString("t", tok).apply()
            screen(url, tok)
        }
        setContentView(l)
    }

    private fun screen(url: String, tok: String) {
        val v = RemoteView(this, url, tok); rv = v
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        root.addView(v, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this)
        fun btn(txt: String, f: () -> Unit) {
            bar.addView(Button(this).apply { text = txt; setOnClickListener { f() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        btn("◀") { v.key(4) }
        btn("●") { v.key(3) }
        btn("■") { v.key(187) }
        btn("⌨") { askText(v) }
        btn("✕") { login() }
        root.addView(bar)
        setContentView(root)
    }

    private fun askText(v: RemoteView) {
        val e = EditText(this)
        AlertDialog.Builder(this).setTitle("Ketik ke HP").setView(e)
            .setPositiveButton("Kirim") { _, _ -> v.text(e.text.toString()) }
            .setNegativeButton("Batal", null).show()
    }
}

class RemoteView(c: Context, private val base: String, private val tok: String) : View(c) {
    @Volatile private var bmp: Bitmap? = null
    @Volatile private var alive = true
    private val io = Executors.newSingleThreadExecutor()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var dx = 0f
    private var dy = 0f
    private var dt = 0L

    init { Thread { loop() }.start() }

    fun stop() { alive = false; io.shutdownNow() }

    @Volatile private var status = "menghubungi server..."
    private val tp = Paint().apply { color = Color.WHITE; textSize = 34f }

    @Volatile private var fails = 0
    @Volatile private var lastErr = ""

    private fun loop() {
        while (alive) { if (fails >= 3) pollOnce() else wsOnce() }
    }

    private fun wsOnce() {
        val done = java.util.concurrent.CountDownLatch(1)
        var got = false
        val c = okhttp3.OkHttpClient.Builder()
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        val req = okhttp3.Request.Builder().url("$base/ws?token=$tok")
            .header("ngrok-skip-browser-warning", "1").build()
        val ws = c.newWebSocket(req, object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                val b = bytes.toByteArray()
                val bm = BitmapFactory.decodeByteArray(b, 0, b.size)
                if (bm != null) { bmp = bm; got = true; fails = 0; status = "mode: websocket"; postInvalidate() }
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                lastErr = "${t.javaClass.simpleName} ${t.message} ${response?.code ?: ""}"
                done.countDown()
            }
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null); done.countDown()
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { done.countDown() }
        })
        try { while (alive && !done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) { } } catch (_: Exception) {}
        ws.cancel()
        c.dispatcher.executorService.shutdown()
        c.connectionPool.evictAll()
        if (!got) { fails++; status = "ws: $lastErr"; postInvalidate() }
        try { Thread.sleep(800) } catch (_: Exception) {}
    }

    private fun pollOnce() {
        try {
            val cn = URL("$base/frame?token=$tok").openConnection() as HttpURLConnection
            cn.setRequestProperty("ngrok-skip-browser-warning", "1")
            cn.connectTimeout = 10000; cn.readTimeout = 15000
            val code = cn.responseCode
            if (code != 200) { status = "HTTP $code"; postInvalidate(); Thread.sleep(1500); return }
            val bytes = cn.inputStream.use { it.readBytes() }
            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bm != null) { bmp = bm; status = "mode: polling (ws gagal: $lastErr)"; postInvalidate() }
            Thread.sleep(100)
        } catch (e: Exception) {
            status = "polling error: ${e.javaClass.simpleName} ${e.message}"
            postInvalidate()
            try { Thread.sleep(1500) } catch (_: Exception) {}
        }
    }

    private fun send(j: JSONObject) {
        try {
            io.execute {
                try {
                    val cn = URL("$base/input?token=$tok").openConnection() as HttpURLConnection
                cn.setRequestProperty("ngrok-skip-browser-warning", "1")
                    cn.requestMethod = "POST"; cn.doOutput = true
                    cn.connectTimeout = 8000; cn.readTimeout = 8000
                    cn.outputStream.use { it.write(j.toString().toByteArray()) }
                    cn.responseCode
                    cn.disconnect()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun key(k: Int) = send(JSONObject().put("t", "key").put("k", k))
    fun text(s: String) = send(JSONObject().put("t", "text").put("s", s))

    private fun scale(b: Bitmap) = minOf(width / b.width.toFloat(), height / b.height.toFloat())

    override fun onDraw(c: Canvas) {
        status.chunked(32).forEachIndexed { i, l -> c.drawText(l, 16f, 60f + i * 44f, tp) }
        val b = bmp ?: return
        val s = scale(b)
        val ox = (width - b.width * s) / 2
        val oy = (height - b.height * s) / 2
        c.drawBitmap(b, Matrix().apply { postScale(s, s); postTranslate(ox, oy) }, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bmp ?: return true
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y; dt = e.eventTime }
            MotionEvent.ACTION_UP -> {
                val s = scale(b)
                val ox = (width - b.width * s) / 2
                val oy = (height - b.height * s) / 2
                fun mx(x: Float) = ((x - ox) / s).toInt().coerceIn(0, b.width - 1)
                fun my(y: Float) = ((y - oy) / s).toInt().coerceIn(0, b.height - 1)
                val d = (e.eventTime - dt).coerceIn(50L, 3000L)
                val moved = Math.hypot((e.x - dx).toDouble(), (e.y - dy).toDouble()) > 24
                if (!moved && d < 400) {
                    send(JSONObject().put("t", "tap").put("x", mx(e.x)).put("y", my(e.y)))
                } else {
                    send(JSONObject().put("t", "swipe")
                        .put("x1", mx(dx)).put("y1", my(dy))
                        .put("x2", mx(e.x)).put("y2", my(e.y)).put("d", d))
                }
            }
        }
        return true
    }
}
