# System / BSP 통합

## 권장 배치

```text
Product image
├─ /product/priv-app/DpuLayerLab/DpuLayerLab.apk
├─ /product/etc/permissions/privapp-permissions-com.example.dpulayerlab.xml
└─ vendor telemetry service
   ├─ DPU driver underrun/active-cycle counter
   ├─ DDR/interconnect PMU
   ├─ HWC composition snapshot
   ├─ gralloc/SBWC control + allocation verification
   └─ vendor NPU SDK workload
```

앱을 platform key로 서명하고 `priv-app`에 넣는 것만으로 vendor sysfs/debugfs가 열리지는 않습니다. vendor service에 최소 권한을 부여하고 앱에는 typed Binder API만 노출하는 구성이 권장됩니다.

## APK를 제품 이미지에 포함

1. Release APK를 만듭니다.

   ```powershell
   $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
   .\gradlew.bat assembleRelease
   ```

2. `system_integration/product/DpuLayerLab-release.apk` 위치에 복사합니다.
3. `system_integration/product/Android.bp`와 permission XML을 제품 트리에 포함합니다.
4. `PRODUCT_PACKAGES += DpuLayerLab DpuLayerLabPrivPermissions`를 product makefile에 추가합니다.

샘플 `Android.bp`는 Soong이 platform certificate로 다시 서명하는 구성입니다. 외부에서 이미 platform key로 서명한 APK를 쓴다면 `certificate: "PRESIGNED"`로 바꿉니다.

현재 APK가 요청하는 privileged permission은 SurfaceFlinger 진단 snapshot용 `DUMP`
하나이며 APK와 같은 partition의 allowlist에 선언해야 합니다. portable refresh 경로는
window의 preferred display mode API를 사용하므로 `DEVICE_POWER`를 요청하지 않습니다.
BSP별 privileged power/mode adapter를 실제로 추가할 때만 manifest와 allowlist 양쪽에
`DEVICE_POWER`를 함께 추가하세요. 부팅 시 `privapp-permissions` 위반 로그가 없는지
확인하세요.

## Vendor service 계약

앱은 다음 action을 가진 system service APK를 찾습니다. 이 서비스는 앱과 vendor
HAL/sysfs/SDK 사이의 **system-side broker 경계**입니다. 앱 프로세스에 vendor node
권한을 직접 주지 말고, broker가 signing identity와 입력 범위를 검증한 뒤 작은 typed
counter/control 값만 전달해야 합니다. 대형 frame/trace를 Binder data plane으로
전송하지 마세요.

```text
action:     com.example.dpulayerlab.VENDOR_TELEMETRY
permission: com.example.dpulayerlab.permission.ACCESS_VENDOR_TELEMETRY
AIDL:       com.example.dpulayerlab.vendor.IDpuLabVendorService
```

provider manifest 예:

```xml
<permission
    android:name="com.example.dpulayerlab.permission.ACCESS_VENDOR_TELEMETRY"
    android:protectionLevel="signature" />

<service
    android:name=".DpuLabVendorService"
    android:exported="true"
    android:permission="com.example.dpulayerlab.permission.ACCESS_VENDOR_TELEMETRY">
    <intent-filter>
        <action android:name="com.example.dpulayerlab.VENDOR_TELEMETRY" />
    </intent-filter>
</service>
```

클라이언트는 system image에 있고 위 signature permission을 선언한 provider가 정확히
하나일 때만 bind합니다. 여러 구현이 같은 action을 노출하면 임의 선택하지 않고 연결을
거부합니다. synchronous control/snapshot 호출에도 짧은 timeout을 적용하지만, 이미
kernel Binder에서 멈춘 provider call을 앱이 강제로 회수할 수는 없으므로 provider
메서드가 non-blocking이어야 한다는 계약은 그대로 유지됩니다.

서비스 메서드는 짧고 non-blocking이어야 합니다. PMU 누적값은 provider가 주기적으로
cache하고 Binder getter는 cache만 반환하는 편이 안전합니다. provider는 caller UID와
signature permission을 모두 확인하고, intensity/mode/기간을 자체 상한으로 다시
검증해야 합니다.

NPU load와 SBWC 강제처럼 device state를 바꾸는 요청에는 lease/watchdog가 필요합니다.
client process death, Binder disconnect, thermal/low-memory emergency 또는 lease timeout
시 provider가 NPU intensity를 0으로 만들고 compression mode를 안전한 default로
복구해야 합니다. 현재 단순 AIDL을 유지한다면 각 `setNpuLoad`를 provider가 정한 최대
기간의 bounded lease로 취급하세요. 제품용 계약에서는 `begin/renew/end` token과
`linkToDeath`를 추가해 긴 phase가 명시적으로 lease를 갱신하도록 하는 편이 안전합니다.

### 값 계약

- `getDpuUnderrunCount()`: boot 이후 monotonic 누적값, 미지원이면 `-1`
- `getDpuUtilizationPercent()`: 동일 sampling interval의 active/total cycle, 미지원이면 음수
- `getMemoryBusUtilizationPercent()`: DDR/interconnect 기준 busy %, 미지원이면 음수
- `getCompositionLayerCounts()`: 앱 display의 `[DEVICE, CLIENT]`, 미지원은 `[-1, -1]`
- `setCompressionMode(0)`: linear/default
- `setCompressionMode(1)`: SBWC auto
- `setCompressionMode(2)`: SBWC required; 실제로 강제·검증할 수 없으면 `false`
- `getLastCompressionState()`: allocator/mapper가 확인한 실제 상태
- `setNpuLoad()`: accelerator 이름/모델/throughput이 `getNpuStatus()`에 포함되어야 함

서비스가 SBWC required 요청을 받았지만 최종 buffer modifier/metadata를 확인하지 못했다면 성공을 반환하면 안 됩니다.

### NPU adapter classpath

portable APK는 AIDL broker가 없을 때
`com.vendor.dpulayerlab.NpuStressAdapter`를 reflection으로 찾을 수 있습니다. 계약은
`constructor(Context)`, `setIntensity(Float)`, `status(): String`, `close()`입니다.
클래스를 system image의 임의 JAR에 복사하는 것만으로는 앱 classloader에서 보이지
않습니다. APK에 adapter를 포함하거나, 제품에서 허용한 shared Java library와
`uses-library`/classpath 구성을 함께 제공해야 합니다. 장기적으로는 vendor SDK와
native dependency를 앱 classpath에 넣는 방식보다 위의 signature-protected AIDL
broker를 권장합니다.

reflection adapter도 `close()`와 intensity 0에서 accelerator job을 확실히 취소해야
합니다. 별도 프로세스/daemon을 사용하는 adapter는 app-side `close()`만 신뢰하지 말고
자체 lease watchdog을 가져야 합니다.

## Stable AIDL

앱 모듈에는 Gradle 빌드용 AIDL 사본이 있습니다. system/vendor 경계를 넘는 제품 서비스는 이 파일을 별도 Soong `aidl_interface` 모듈로 옮기고 다음을 적용하세요.

- `stability: "vintf"`
- Java client backend와 NDK/Rust provider backend 중 필요한 backend
- frozen API dump와 version/hash
- VINTF manifest/compatibility matrix
- service manager 등록 및 binder SELinux rule

제품 service가 단순히 platform-signed system APK라면 versioned app AIDL로도 연결할 수 있지만, vendor native HAL에는 VINTF-stable AIDL을 권장합니다.

## SELinux

정책은 실제 BSP type 이름에 맞춰 작성해야 합니다. 앱 domain 전체에 `/sys` 또는 `debugfs` read 권한을 주지 마세요.

권장 원칙:

1. counter node마다 전용 label을 부여합니다.
2. vendor telemetry service domain만 해당 label을 read/open/getattr 합니다.
3. 앱은 signature permission이 걸린 Binder 서비스만 호출합니다.
4. control node write는 SBWC/NPU test enable에 필요한 최소 파일만 허용합니다.
5. 서비스는 허용된 범위와 thermal limit를 자체 검증합니다.

Android 12 이상 production 빌드에서는 debugfs 의존을 피하고 sysfs 또는 typed HAL counter를 사용하는 편이 좋습니다.

## 직접 probe 경로

초기 bring-up에서만 앱의 fallback reader를 사용할 수 있습니다. 아래 위치 중 읽을 수 있는 첫 파일을 사용합니다.

- 앱 내부 `files/probe_paths.conf`
- `/data/local/tmp/dpulayerlab-probes.conf`
- `/vendor/etc/dpulayerlab/probe_paths.conf`

지원 key:

```ini
gpu_busy=/sys/...
gpu_frequency=/sys/...
bus_busy=/sys/...
dpu_busy=/sys/...
dpu_underrun=/sys/...
```

값의 포맷:

- `*_busy`: 단일 0~100 값 또는 `busy total` 두 정수
- `*_frequency`: Hz 또는 이미 MHz인 정수
- `dpu_underrun`: monotonic 누적 정수

예제는 `system_integration/vendor/probe_paths.conf.example`에 있습니다. 이 fallback은 파일 값을 그대로 읽을 뿐 SELinux를 우회하지 않습니다.

## 4K/8K 자산

APK에는 대형 영상을 넣지 않았습니다. 테스트 화면에서 SAF URI로 로컬 영상을 선택하면
현재 앱 세션의 read grant로 `MediaExtractor` track을 선택한 뒤 `MediaCodec` output을
primary Surface에 직접 보내 반복 decode합니다. URI를 영구 저장하지 않으므로
persistable grant도 보관하지 않습니다.

자산 manifest에는 최소한 다음을 별도로 관리하는 것이 좋습니다.

- container와 codec (H.264/HEVC/AV1)
- coded/display size, bit depth, chroma, fps
- HDR/static metadata
- file SHA-256
- 예상 decoder와 SBWC/linear 정책

앱의 capability 화면은 `VideoCapabilities.areSizeAndRateSupported()`와 최대 codec
instance 수를 표시하지만, capability 선언이 특정 stream profile/level, 동시 decoder
instance, output pixel format, SBWC allocation 또는 실제 sustained thermal 성능을
보장하지는 않습니다. 제품 test 자산마다 decoder 이름과 output/allocation metadata를
보고서에 연결하는 것이 좋습니다.
