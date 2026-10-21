package ua.zaika.carremote.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import org.lsposed.hiddenapibypass.HiddenApiBypass
import ua.zaika.carremote.car.CameraSide
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/** Own overlay using the vendor AVM interface described in the local reference.
 * Main owns views; a serial worker owns the camera. Old buffers are hidden on frame loss. */
class SideCameraOverlay(private val context: Context) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var label: TextView? = null
    private val textures = mutableMapOf<CameraSide, TextureView>()
    private val surfaces = mutableMapOf<CameraSide, Surface>()
    private val frames = mutableMapOf<CameraSide, Long>()
    private var desired: CameraSide? = null
    private var openedAt = 0L
    private var retryAfter = 0L
    private var generation = 0
    private var ready = false
    private var opening = false
    private var closed = false
    private var requestedAt = 0L
    // Worker thread only.
    private var camera: Any? = null
    private var bound = emptyMap<Int, Surface>()

    fun request(side: CameraSide?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        requestedAt = SystemClock.elapsedRealtime()
        val previous = desired
        desired = side
        if (side == null) { if (root != null) dismiss(); return }
        if (previous != side && root != null) dismiss()
        if (SystemClock.elapsedRealtime() < retryAfter) return
        if (!Settings.canDrawOverlays(context) ||
            context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "camera/overlay permission missing")
            retryAfter = SystemClock.elapsedRealtime() + 10_000
            return
        }
        if (root == null) attach()
        update()
    }

    private fun attach() {
        val panel = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root = panel
        // Bind only the visible side. An unconsumed hidden preview can fill the vendor's
        // buffer queue and stall its sibling. A side change reopens the one required stream.
        for (side in listOf(checkNotNull(desired))) {
            val texture = TextureView(context).apply { alpha = 0f; isOpaque = true }
            textures[side] = texture
            panel.addView(texture, FrameLayout.LayoutParams(-1, -1))
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    surfaces[side] = Surface(st)
                    openWhenReady()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    val surface = surfaces.remove(side)
                    if (root != null) dismiss()
                    // Camera unbind/close is queued before these releases.
                    worker.execute { surface?.release(); st.release() }
                    return false
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    frames[side] = SystemClock.elapsedRealtime()
                    update()
                }
            }
        }
        label = TextView(context).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xAA000000.toInt())
            textSize = 15f; setPadding(12, 5, 12, 5)
        }.also { panel.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)) }
        val width = (context.resources.displayMetrics.widthPixels * 0.30f).toInt().coerceAtLeast(280)
        val params = WindowManager.LayoutParams(
            width, width * 9 / 16 + 30, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // DiLink clamps NOT_TOUCHABLE overlays to alpha=0.8: camera frames would blend
            // with navigation underneath. A touchable panel keeps the video opaque.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = -width - 32; y = 90 }
        windowParams = params
        runCatching { wm.addView(panel, params) }.onFailure {
            Log.w(TAG, "cannot attach camera overlay", it); dismiss()
            retryAfter = SystemClock.elapsedRealtime() + 10_000
        }
    }

    private fun openWhenReady() {
        if (opening || ready || surfaces.size != 1 || root == null) return
        opening = true
        openedAt = SystemClock.elapsedRealtime()
        val ticket = generation
        val bindings = surfaces.mapKeys { it.key.preview }
        handler.removeCallbacks(checkFrames)
        handler.postDelayed(checkFrames, 250)
        worker.execute {
            val ok = runCatching { openVendor(bindings) }.onFailure {
                Log.w(TAG, "AVM preview unavailable", it); closeVendor()
            }.getOrDefault(false)
            handler.post {
                if (ticket != generation || closed) return@post
                opening = false
                ready = ok
                if (!ok) { dismiss(); retryAfter = SystemClock.elapsedRealtime() + 10_000 }
                else update()
            }
        }
    }

    private val checkFrames = object : Runnable {
        override fun run() {
            if (root == null || desired == null) return
            val now = SystemClock.elapsedRealtime()
            if (now - requestedAt > 1_000) { dismiss(); return }
            val last = frames[desired] ?: openedAt
            if (now - last > if (ready && frames.containsKey(desired)) 1_500 else 4_000) {
                Log.w(TAG, "camera stream stalled; hiding retained frame")
                dismiss(); retryAfter = now + 5_000
                return
            }
            update()
            handler.postDelayed(this, 250)
        }
    }

    private fun update() {
        val side = desired
        val fresh = side != null && frames[side]?.let { SystemClock.elapsedRealtime() - it < 750 } == true
        textures.forEach { (key, view) -> view.alpha = if (key == side) 1f else 0f }
        val panel = root
        val params = windowParams
        if (panel != null && params != null) {
            val x = if (ready && fresh) context.resources.displayMetrics.widthPixels - params.width - 16
                else -params.width - 32
            if (params.x != x) {
                params.x = x
                runCatching { wm.updateViewLayout(panel, params) }.onFailure {
                    Log.w(TAG, "cannot position preview", it); dismiss()
                }
            }
        }
        label?.text = if (side == CameraSide.LEFT) "◀ Ліва камера" else "Права камера ▶"
    }

    private fun dismiss() {
        generation++
        ready = false; opening = false
        handler.removeCallbacks(checkFrames)
        val oldSurfaces = surfaces.values.toList()
        surfaces.clear(); textures.clear(); frames.clear()
        val oldRoot = root
        root = null; label = null; windowParams = null
        oldRoot?.alpha = 0f
        // Vendor close must precede SurfaceTexture destruction callbacks on the worker.
        worker.execute { closeVendor(); oldSurfaces.forEach { it.release() } }
        oldRoot?.let { runCatching { wm.removeViewImmediate(it) } }
    }

    private fun openVendor(bindings: Map<Int, Surface>): Boolean {
        closeVendor()
        HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/")
        val info = Class.forName("android.hardware.BmmCameraInfo")
        val id = listOf("pano_h", "pano_l", "apa", "byd_apa").asSequence().map {
            (info.getMethod("getCameraId", String::class.java).invoke(null, it) as Number).toInt()
        }.firstOrNull { it >= 0 } ?: error("No AVM camera on this head unit")
        val type = Class.forName("android.hardware.AVMCamera")
        camera = type.getMethod("open", Int::class.javaPrimitiveType).invoke(null, id)
        if (camera == null) {
            camera = type.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
                .newInstance(id)
            check(type.getDeclaredMethod("open").apply { isAccessible = true }.invoke(camera) == true)
        }
        val callback = Class.forName("android.hardware.AVMCamera\$IEventCallback")
        val listener = Proxy.newProxyInstance(callback.classLoader, arrayOf(callback)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "CarRemoteCamera"
                else -> null
            }
        }
        type.getMethod("setEventCallback", callback).invoke(camera, listener)
        bound = bindings
        for ((index, surface) in bindings) {
            for (method in listOf("addPreviewSurface", "setPreviewSurface")) {
                check(type.getMethod(method, Surface::class.java, Int::class.javaPrimitiveType)
                    .invoke(camera, surface, index) != false) { "$method rejected preview $index" }
            }
        }
        check(type.getMethod("startPreview").invoke(camera) != false) { "AVM start rejected" }
        Log.i(TAG, "AVM opened id=$id previews=${bindings.keys}")
        return true
    }

    private fun closeVendor() {
        val cam = camera ?: return
        for ((index, surface) in bound) runCatching {
            cam.javaClass.getMethod("rmPreviewSurface", Surface::class.java, Int::class.javaPrimitiveType)
                .invoke(cam, surface, index)
        }.onFailure { Log.w(TAG, "unbind failed", it) }
        for (method in listOf("stopPreview", "close")) runCatching {
            cam.javaClass.getMethod(method).invoke(cam)
        }.onFailure { Log.w(TAG, "$method failed", it) }
        camera = null; bound = emptyMap()
        Log.i(TAG, "AVM closed")
    }

    override fun close() {
        if (closed) return
        desired = null; dismiss(); closed = true
        handler.post { worker.shutdown() }
    }

    private companion object { const val TAG = "SideCamera" }
}
