package com.trtcast

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val REQUEST_CAPTURE = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status); address = findViewById(R.id.address)
        start = findViewById(R.id.start); stop = findViewById(R.id.stop)
        start.setOnClickListener {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }
        stop.setOnClickListener {
            stopService(Intent(this, CastService::class.java))
            status.text = "Stopped"; address.text = ""; start.isEnabled = true; stop.isEnabled = false
        }
    }

    @Deprecated("Activity result API kept minimal for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CastService::class.java).apply {
                putExtra("resultCode", resultCode); putExtra("data", data)
            }
            ContextCompat.startForegroundService(this, i)
            status.text = "Casting started"
            address.text = "Find this phone's Wi‑Fi IP and open: http://PHONE_IP:8080"
            start.isEnabled = false; stop.isEnabled = true
        }
    }
}
