package com.example.dpulayerlab.vendor;

import android.os.IBinder;

/**
 * Versioned product integration contract. BSP teams should mirror this contract in a Soong
 * aidl_interface module with VINTF stability when the provider lives across system/vendor.
 */
interface IDpuLabVendorService {
    int getApiVersion();

    long getDpuUnderrunCount();
    float getDpuUtilizationPercent();
    float getMemoryBusUtilizationPercent();

    /** [DEVICE layers, CLIENT layers], or negative values when unavailable. */
    int[] getCompositionLayerCounts();

    boolean isSbwcControlSupported();
    boolean setCompressionMode(int mode);
    String getLastCompressionState();

    boolean isNpuLoadSupported();
    void setNpuLoad(float intensity, int shape);
    String getNpuStatus();
    void stopNpuLoad();

    // API v2. Keep new methods appended so API v1 transaction IDs remain stable.
    float getGpuUtilizationPercent();
    long getGpuFrequencyHz();
    long getDpuFrequencyHz();

    /*
     * API v3. These methods are a lease-based, fail-closed performance-policy session.
     *
     * The portable app currently requests only battery-saver suppression. Thermal protection,
     * DVFS/governor overrides, and frequency locking are deliberately outside this contract.
     *
     * The client owns sessionId and a monotonically increasing commandVersion. The provider must
     * serialize commands by (clientToken, sessionId), ignore a commandVersion older than the
     * newest version it has observed, and return commandVersion only after the requested state is
     * acknowledged. Consequently, a higher-version end remains authoritative even if an older
     * begin Binder transaction completes late on another provider thread.
     *
     * The provider must link clientToken to death and restore the original policy on Binder death,
     * lease expiry, or end. leaseDurationMs is 10 seconds in the v3 client; renewals are normally
     * issued every 2 seconds. A renewal must never reactivate an already expired/ended session.
     * End is idempotent and returns commandVersion only after the original policy is restored.
     *
     * Battery Saver is a system-wide policy, so per-Binder bookkeeping alone is insufficient.
     * Within each affected Android user, the provider must either admit exactly one active lease
     * or maintain one first-owner baseline plus an active-client refcount. It must restore that
     * baseline only after the final lease ends/dies/expires, and must atomically reject stale
     * commandVersion values before committing a global policy mutation.
     */
    long beginPerformanceSession(
        IBinder clientToken,
        long sessionId,
        long commandVersion,
        int requestedControls,
        long leaseDurationMs
    );
    long renewPerformanceSession(
        IBinder clientToken,
        long sessionId,
        long commandVersion,
        long leaseDurationMs
    );

    /**
     * Returns 1 only when the session is active at least through minimumAppliedVersion, 0 when the
     * provider has confirmed it restored/expired the session, and a negative value when unknown.
     */
    int getPerformanceSessionState(
        IBinder clientToken,
        long sessionId,
        long minimumAppliedVersion
    );
    long endPerformanceSession(
        IBinder clientToken,
        long sessionId,
        long commandVersion
    );
}
