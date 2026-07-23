package com.example.dpulayerlab.vendor;

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
}
