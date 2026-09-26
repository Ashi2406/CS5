package com.trtcast

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class CastService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var server: MjpegServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForeground(7, notification())

        val code = intent?.getIntExtra(
            "resultCode",
            Activity.RESULT_CANCELED
        ) ?: return START_NOT_STICKY

        val data = intent.getParcelableExtra<Intent>("data")
            ?: return START_NOT_STICKY

        val mgr = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        projection = mgr.getMediaProjection(code, data)

        startCapture()

        return START_NOT_STICKY
    }

    private fun startCapture() {

        val metrics = resources.displayMetrics

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        reader?.setOnImageAvailableListener(
            { imageReader ->

                val image = imageReader.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

                try {
                    val plane = image.planes[0]

                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride

                    val rowPadding =
                        rowStride - pixelStride * width

                    val bitmapWidth =
                        width + rowPadding / pixelStride

                    val bitmap = Bitmap.createBitmap(
                        bitmapWidth,
                        height,
                        Bitmap.Config.ARGB_8888
                    )

                    val buffer = plane.buffer
                    bitmap.copyPixelsFromBuffer(buffer)

                    val cropped = if (bitmapWidth != width) {
                        Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            width,
                            height
                        )
                    } else {
                        bitmap
                    }

                    val output = ByteArrayOutputStream()

                    cropped.compress(
                        Bitmap.CompressFormat.JPEG,
                        60,
                        output
                    )

                    val jpeg = output.toByteArray()

                    server?.publish(jpeg)

                    if (cropped !== bitmap) {
                        cropped.recycle()
                    }

                    bitmap.recycle()

                } catch (_: Exception) {
                } finally {
                    image.close()
                }

            },
            Handler(Looper.getMainLooper())
        )

        server = MjpegServer(8080)
        server?.start()

        projection?.createVirtualDisplay(
            "TRT-Cast",
            width,
            height,
            density,
            0,
            reader!!.surface,
            null,
            null
        )
    }

    override fun onDestroy() {

        try {
            reader?.close()
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        try {
            server?.stopServer()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val manager =
                getSystemService(
                    NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(
                NotificationChannel(
                    "cast",
                    "TRT Cast",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun notification(): Notification {

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            Notification.Builder(this, "cast")
                .setContentTitle("TRT Cast is active")
                .setContentText("Screen casting is running")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .build()

        } else {

            Notification.Builder(this)
                .setContentTitle("TRT Cast is active")
                .setContentText("Screen casting is running")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .build()
        }
    }
}


class MjpegServer(
    private val port: Int
) : Thread("TRT-MJPEG") {

    @Volatile
    private var running = true

    private var serverSocket: ServerSocket? = null

    private val frameLock = Object()

    private var latestFrame: ByteArray? = null
    private var frameNumber = 0L

    fun publish(jpeg: ByteArray) {

        synchronized(frameLock) {

            latestFrame = jpeg
            frameNumber++

            frameLock.notifyAll()
        }
    }

    override fun run() {

        try {

            serverSocket = ServerSocket(port)

            while (running) {

                val socket = serverSocket!!.accept()

                thread(
                    start = true,
                    name = "TRT-MJPEG-Client"
                ) {
                    handleClient(socket)
                }
            }

        } catch (_: Exception) {
        }
    }

    private fun handleClient(socket: Socket) {

        try {

            socket.soTimeout = 15000

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val buffer = ByteArray(4096)

            input.read(buffer)

            val header =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "Pragma: no-cache\r\n" +
                "\r\n"

            output.write(header.toByteArray())
            output.flush()

            var sentFrame = -1L

            while (running && !socket.isClosed) {

                val frame: ByteArray

                synchronized(frameLock) {

                    while (
                        running &&
                        frameNumber == sentFrame
                    ) {
                        frameLock.wait(2000)
                    }

                    if (!running) break

                    frame = latestFrame?.copyOf()
                        ?: continue

                    sentFrame = frameNumber
                }

                val partHeader =
                    "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.size}\r\n" +
                    "\r\n"

                output.write(partHeader.toByteArray())
                output.write(frame)
                output.write("\r\n".toByteArray())
                output.flush()
            }

        } catch (_: Exception) {

        } finally {

            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    fun stopServer() {

        running = false

        synchronized(frameLock) {
            frameLock.notifyAll()
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }
}
