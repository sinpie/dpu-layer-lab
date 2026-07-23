package com.example.dpulayerlab.vendor

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.IBinder
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.PixelRoute
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class VendorSnapshot(
    val apiVersion: Int,
    val underrunCount: Long?,
    val dpuUtilization: Float?,
    val busUtilization: Float?,
    val deviceLayers: Int?,
    val clientLayers: Int?,
    val compressionState: String,
    val npuStatus: String,
)

class VendorBridge private constructor(private val context: Context) : AutoCloseable {
    @Volatile
    private var service: IDpuLabVendorService? = null
    @Volatile
    private var npuSupported: Boolean? = null
    @Volatile
    private var sbwcSupported: Boolean? = null
    private val binding = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DpuLab-VendorBridge")
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed.get()) {
                runCatching { context.unbindService(this) }
                return
            }
            service = IDpuLabVendorService.Stub.asInterface(binder)
            npuSupported = null
            sbwcSupported = null
            binding.set(false)
            refreshCapabilities()
            runCatching {
                binder.linkToDeath({
                    service = null
                    if (!closed.get()) connect()
                }, 0)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            npuSupported = null
            sbwcSupported = null
            binding.set(false)
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            npuSupported = null
            sbwcSupported = null
            binding.set(false)
            if (!closed.get()) connect()
        }

        override fun onNullBinding(name: ComponentName) {
            service = null
            npuSupported = null
            sbwcSupported = null
            binding.set(false)
        }
    }

    fun connect() {
        if (closed.get()) return
        if (service != null || !binding.compareAndSet(false, true)) return
        runCatching {
            val query = Intent(ACTION_VENDOR_SERVICE)
            val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(
                    query,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(query, PackageManager.MATCH_SYSTEM_ONLY)
            }
            val eligible = matches.mapNotNull { it.serviceInfo }
                .filter { info ->
                    info.exported &&
                        info.permission == VENDOR_TELEMETRY_PERMISSION
                }
            // Binding to an arbitrary implementation makes telemetry and load control
            // nondeterministic. Product integration must expose exactly one trusted broker.
            val info = eligible.singleOrNull()
                ?.takeIf { hasSignatureVendorPermission() }
                ?: run {
                binding.set(false)
                return
            }
            val explicit = query.setComponent(ComponentName(info.packageName, info.name))
            if (!context.bindService(explicit, connection, Context.BIND_AUTO_CREATE)) {
                binding.set(false)
            }
        }.onFailure {
            binding.set(false)
        }
    }

    fun isConnected(): Boolean = service != null

    fun supportsNpu(): Boolean = npuSupported == true

    fun supportsSbwc(): Boolean = sbwcSupported == true

    fun setNpuLoad(intensity: Float, shape: LoadShape) {
        if (closed.get()) return
        runCatching {
            executor.execute {
                runCatching {
                    val remote = service
                    if (remote != null) {
                        val safeIntensity =
                            intensity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
                        if (safeIntensity <= 0f) {
                            remote.stopNpuLoad()
                        } else {
                            remote.setNpuLoad(safeIntensity, shape.wireValue())
                        }
                    }
                }
            }
        }
    }

    fun setCompressionRoute(route: PixelRoute): Boolean {
        val mode = when (route) {
            PixelRoute.SBWC_AUTO -> COMPRESSION_AUTO
            PixelRoute.SBWC_REQUIRED -> COMPRESSION_SBWC_REQUIRED
            else -> COMPRESSION_LINEAR
        }
        return callRemote(false, CONTROL_TIMEOUT_MS) { it.setCompressionMode(mode) }
    }

    fun snapshot(): VendorSnapshot? {
        return callRemote<VendorSnapshot?>(null, SNAPSHOT_TIMEOUT_MS) { remote ->
            val counts: IntArray = remote.compositionLayerCounts ?: intArrayOf()
            VendorSnapshot(
                apiVersion = remote.apiVersion,
                underrunCount = remote.dpuUnderrunCount.takeIf { it >= 0 },
                dpuUtilization = remote.dpuUtilizationPercent.validPercent(),
                busUtilization = remote.memoryBusUtilizationPercent.validPercent(),
                deviceLayers = counts.getOrNull(0)?.takeIf { it >= 0 },
                clientLayers = counts.getOrNull(1)?.takeIf { it >= 0 },
                compressionState = remote.lastCompressionState.orEmpty(),
                npuStatus = remote.npuStatus.orEmpty(),
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        callRemote(false, CONTROL_TIMEOUT_MS, allowClosed = true) {
            it.stopNpuLoad()
            true
        }
        if (service != null || binding.get()) runCatching { context.unbindService(connection) }
        service = null
        binding.set(false)
        executor.shutdownNow()
        synchronized(Companion) {
            if (instance === this) instance = null
        }
    }

    @Suppress("DEPRECATION")
    private fun hasSignatureVendorPermission(): Boolean = runCatching {
        val permission =
            context.packageManager.getPermissionInfo(VENDOR_TELEMETRY_PERMISSION, 0)
        permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE ==
            PermissionInfo.PROTECTION_SIGNATURE
    }.getOrDefault(false)

    private fun refreshCapabilities() {
        runCatching {
            executor.execute {
                if (closed.get()) return@execute
                val remote = service
                if (remote != null) {
                    npuSupported = runCatching { remote.isNpuLoadSupported }.getOrDefault(false)
                    sbwcSupported = runCatching { remote.isSbwcControlSupported }.getOrDefault(false)
                }
            }
        }
    }

    private fun <T> callRemote(
        fallback: T,
        timeoutMs: Long,
        allowClosed: Boolean = false,
        block: (IDpuLabVendorService) -> T,
    ): T {
        if (closed.get() && !allowClosed) return fallback
        val remote = service ?: return fallback
        val future = runCatching { executor.submit<T> { block(remote) } }.getOrNull()
            ?: return fallback
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            fallback
        } catch (_: Exception) {
            future.cancel(true)
            fallback
        }
    }

    companion object {
        const val ACTION_VENDOR_SERVICE = "com.example.dpulayerlab.VENDOR_TELEMETRY"
        const val VENDOR_TELEMETRY_PERMISSION =
            "com.example.dpulayerlab.permission.ACCESS_VENDOR_TELEMETRY"
        const val COMPRESSION_LINEAR = 0
        const val COMPRESSION_AUTO = 1
        const val COMPRESSION_SBWC_REQUIRED = 2
        const val CONTROL_TIMEOUT_MS = 500L
        const val SNAPSHOT_TIMEOUT_MS = 700L

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: VendorBridge? = null

        fun get(context: Context): VendorBridge = instance ?: synchronized(this) {
            instance ?: VendorBridge(context.applicationContext).also {
                instance = it
                it.connect()
            }
        }

        private fun Float.validPercent(): Float? =
            takeIf { it.isFinite() && it in 0f..100f }

        // AIDL values are a product ABI; do not couple them to Kotlin enum ordering.
        private fun LoadShape.wireValue(): Int = when (this) {
            LoadShape.STEADY -> 0
            LoadShape.PULSE -> 1
            LoadShape.RAMP -> 2
            LoadShape.SAW -> 3
        }
    }
}
