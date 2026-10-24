package com.vinz.cloudphone.server

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var info: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 1000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cp", Context.MODE_PRIVATE)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val c = EditText(this).apply { hint = "Cloudflare tunnel token"; setText(p.getString("cf_token", "")) }
        val go = Button(this).apply { text = "START SERVER" }
        val stop = Button(this).apply { text = "STOP" }
        val acc = Button(this).apply { text = "Aktifkan Accessibility (kontrol sentuh)" }
        info = TextView(this).apply { setTextIsSelectable(true); textSize = 13f }
        l.addView(TextView(this).apply { text = "Cloud Phone Server"; textSize = 26f })
        l.addView(c); l.addView(go); l.addView(acc); l.addView(stop); l.addView(info)

        go.setOnClickListener {
            p.edit().putString("cf_token", c.text.toString().trim()).apply()
            val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(m.createScreenCaptureIntent(), 77)
        }
        acc.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        stop.setOnClickListener { stopService(Intent(this, CaptureService::class.java)) }
        setContentView(ScrollView(this).apply { addView(l) })
        h.post(tick)
    }

    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }

    private fun refresh() {
        val p = getSharedPreferences("cp", Context.MODE_PRIVATE)
        info.text = "Token server (isi di Control APK):\n${p.getString("srvtok", "(tekan START)")}\n\n" +
            "Accessibility: ${if (InputService.inst != null) "AKTIF" else "BELUM aktif"}\n\n" +
            CaptureService.stat + "\n\n" + CfTunnel.log
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK && data != null) {
            val p = getSharedPreferences("cp", Context.MODE_PRIVATE)
            val tok = p.getString("srvtok", null)
                ?: UUID.randomUUID().toString().replace("-", "").take(16).also { p.edit().putString("srvtok", it).apply() }
            val i = Intent(this, CaptureService::class.java)
                .putExtra("rc", resultCode).putExtra("data", data).putExtra("tok", tok)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }
}
