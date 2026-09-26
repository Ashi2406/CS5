package com.trtcast

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import java.io.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class CastService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var server: MjpegServer? = null

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7, notification())
        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { server?.publish(img) } finally { img.close() }
        }, Handler(Looper.getMainLooper()))
        projection!!.createVirtualDisplay("TRT-Cast", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        server = MjpegServer(8080)
        server!!.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() { server?.stop(); reader?.close(); projection?.stop(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("cast", "TRT Cast", NotificationManager.IMPORTANCE_LOW))
        }
    }
    private fun notification(): Notification = Notification.Builder(this, "cast")
        .setContentTitle("TRT Cast is active").setContentText("Screen casting is running").setSmallIcon(android.R.drawable.ic_menu_share).build()

    class MjpegServer(private val port: Int): Thread("TRT-MJPEG") {
        private var running = true
        private val clients = CopyOnWriteArrayList<OutputStream>()
        private var latest: ByteArray? = null
        private val lock = Object()
        fun stop() { running = false; interrupt(); clients.forEach { try { it.close() } catch(_:Exception){} } }
        fun publish(image: android.media.Image) {
            val p = image.planes[0]; val buf = p.buffer; val bytes = ByteArray(buf.remaining()); buf.get(bytes)
            // Raw RGBA -> JPEG conversion is intentionally left to a Bitmap worker in the next build.
            // This first build establishes capture + HTTP transport without third-party dependencies.
            synchronized(lock) { latest = bytes; lock.notifyAll() }
        }
        override fun run() {
            try {
                val ss = ServerSocket(port)
                while (running) { val s = ss.accept(); thread { handle(s) } }
                ss.close()
            } catch (_: Exception) {}
        }
        private fun handle(s: Socket) {
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                val out = s.getOutputStream()
                // Placeholder response so the endpoint is discoverable during the first test build.
                val body = "TRT Cast receiver endpoint is active."
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                out.flush(); s.close()
            } catch (_: Exception) {}
        }
    }
}
