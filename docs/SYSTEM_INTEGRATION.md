# System / BSP 통합

사용자에게 보이는 launcher/Gradle project 이름은 **DPULayerTest**이고 release version은
`20260725_090252`(`versionCode 4`), debug version은 `20260725_090252-debug`입니다.
`yyyyMMdd_HHmmss`는 KST build 시각입니다. 앱 상단·실행 HUD·보고서에 같은 version이
표시됩니다. release tag는 `v20260725_090252`이고 canonical source remote는
`sinpie/dpu-layer-lab`입니다. 아래 `DpuLayerLab` directory/Soong module,
package `com.example.dpulayerlab`, automation action/component, vendor action/AIDL과
`dpu-layer-lab-` report prefix는 기존 제품 이미지·harness·consumer 호환성을 위한 stable
identifier입니다. 표시 이름에 맞춰 이 계약들을 일괄 rename하지 마세요.

## 권장 배치

```text
Product image
├─ /product/priv-app/DpuLayerLab/DpuLayerLab.apk
├─ /product/etc/permissions/privapp-permissions-com.example.dpulayerlab.xml
└─ vendor telemetry service
   ├─ DPU driver underrun/active-cycle counter
   ├─ GPU active-cycle + GPU/DPU clock counter
   ├─ DDR/interconnect PMU
   ├─ HWC composition snapshot
   ├─ gralloc/SBWC control + allocation verification
   ├─ vendor NPU SDK workload
   └─ API v3 Battery Saver lease + exact restore
```

앱을 platform key로 서명하고 `priv-app`에 넣는 것만으로 vendor sysfs/debugfs가 열리지는 않습니다. vendor service에 최소 권한을 부여하고 앱에는 typed Binder API만 노출하는 구성이 권장됩니다.

## APK를 제품 이미지에 포함

1. Release APK를 만듭니다.

   ```powershell
   $env:JAVA_HOME='<JDK_17_HOME>'
   .\gradlew.bat assembleRelease
   ```

2. `system_integration/product/DpuLayerLab-release.apk` 위치에 복사합니다.
3. `system_integration/product/Android.bp`와 permission XML을 제품 트리에 포함합니다.
4. `PRODUCT_PACKAGES += DpuLayerLab DpuLayerLabPrivPermissions`를 product makefile에 추가합니다.

샘플 `Android.bp`는 Soong이 platform certificate로 다시 서명하는 구성입니다. 외부에서 이미 platform key로 서명한 APK를 쓴다면 `certificate: "PRESIGNED"`로 바꿉니다.

GitHub의 `DPULayerTest-20260725_090252-debug.apk`는 Android debug key로 서명된
lab-only 산출물이며 debug manifest가 automation alias의 `CONTROL_TESTS` permission을
제거합니다. 제품 이미지에 넣지 마세요.
`DPULayerTest-20260725_090252-release-unsigned.apk`는 Soong 또는 secure signing
pipeline 입력용이며 그대로 설치하는 최종 제품 APK가 아닙니다. Platform key,
certificate, keystore, password/token은 저장소, GitHub Release, `dist/`에 두지 않고
제품 보안 환경에서만 사용합니다. Release에는 두 APK와 `SHA256SUMS.txt`만 배포합니다.

현재 APK가 요청하는 privileged permission은 SurfaceFlinger 진단 snapshot용 `DUMP`
하나이며 APK와 같은 partition의 allowlist에 선언해야 합니다. portable refresh 경로는
window의 preferred display mode API를 사용하므로 `DEVICE_POWER`를 요청하지 않습니다.
Battery Saver 같은 system-wide policy 변경 권한을 앱에 직접 추가하지 마세요.
Platform signing/`priv-app` 배치만으로 system-server private API, SELinux/DAC 또는
vendor node 접근이 생긴다고 가정할 수 없습니다. 필요한 제품 권한은 최소 권한의
system-side broker가 소유하고, 앱에는 아래 typed API v3 acknowledgment만 노출합니다.
부팅 시 `privapp-permissions` 위반 로그가 없는지 확인하세요.

### Test automation 권한

Release manifest는 stress control을 launcher와 분리한
`com.example.dpulayerlab.AutomationActivity` alias에
`com.example.dpulayerlab.permission.CONTROL_TESTS`
(`signature|privileged`)를 요구합니다. 제품 harness는 이 permission을
`uses-permission`으로 선언하고 제품 signing/privileged allowlist 정책을 충족한 뒤
다음 정확한 explicit component로 호출해야 합니다.

```text
com.example.dpulayerlab/com.example.dpulayerlab.AutomationActivity
```

Alias filter에는 의도적으로 `CATEGORY_DEFAULT`가 없으므로 implicit activity resolution에
의존하면 안 됩니다. Launcher `MainActivity`에 직접 보낸 START/STOP/SHOW는 authorization
경계가 아니므로 앱이 무시합니다. `src/debug/AndroidManifest.xml`만 ADB lab 자동화를
위해 alias permission을 제거하며, debug package/component는 다음과 같습니다.

```text
com.example.dpulayerlab.debug/com.example.dpulayerlab.AutomationActivity
```

표시 이름과 무관하게 automation action은 다음 문자열을 유지합니다.

```text
com.example.dpulayerlab.action.START
com.example.dpulayerlab.action.STOP
com.example.dpulayerlab.action.SHOW
```

Debug lab에서 fixed-topology 순간/점진 부하를 순서대로 2회 실행하고 중단하는 예:

```powershell
adb shell am start -n `
  com.example.dpulayerlab.debug/com.example.dpulayerlab.AutomationActivity `
  -a com.example.dpulayerlab.action.START `
  --es scenario_ids "instant-isolated-contention,continuous-crossload-ramp" `
  --ei repeat_count 2

adb shell am start -n `
  com.example.dpulayerlab.debug/com.example.dpulayerlab.AutomationActivity `
  -a com.example.dpulayerlab.action.STOP
```

Release 제품 검증에서는 debug override를 복제하지 마세요. Harness는 catalog scenario
ID, repeat 1~10, expanded plan 40회 제한을 그대로 사용해야 하며 custom phase나 safety
값을 Intent로 전달할 수 없습니다. Privileged 경로를 쓴다면 permission allowlist
항목은 DpuLayerLab 샘플 XML이 아니라 permission을 요청하는 harness package 쪽에
추가합니다. 앱은 START일 때만 extras를 unmarshal하므로 STOP/SHOW에는 불필요하거나
복잡한 extras를 붙이지 마세요. Malformed START payload와 무관하게 뒤의 explicit
STOP을 처리하는 fail-safe 계약을 유지합니다.

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

앱은 양의 NPU setpoint도 request enqueue만으로 적용됐다고 보지 않습니다. Latest
command ticket과 acknowledgment가 일치한 뒤에만 measured phase를 진행하고 실행 중
backend health를 확인합니다. Provider는 ticket이 가리키는 setpoint가 실제 accelerator에
적용됐는지 또는 명시적으로 거부됐는지를 bounded하게 반환해야 합니다. Timeout, 거부,
ticket 불일치나 health 상실은 앱에서 `NPU_WORKLOAD_APPLY_FAILED`로 fail-closed합니다.

앱 선제 thermal `SEVERE` derating은 plan 시작 시 immutable snapshot으로 고정하는
선택형이며 기본 OFF입니다. OFF에서는 앱 setpoint를 유지하고 Android/kernel thermal
mitigation을 방해하지 않습니다. ON일 때만 기존 generated/NPU load의 ordered zero
확인, 축소 workload의 ticket/acknowledgment, display 감속 acknowledgment 순서로
적용합니다. Provider가 zero 또는 reduced positive setpoint를 확인하지 못하면 앱은
일부 derating 상태로 계속하지 않고 `THERMAL_DERATE_FAILED`로 중단합니다. Thermal
`CRITICAL`, low-memory, local-worker failure와 power/display/SystemUI 격리 무결성
fail-safe는 이 옵션과 무관하게 항상 활성이고 즉시 안전값으로 복구합니다.

### 값 계약

- `getApiVersion()`: 기본 계약은 `1`, 아래 GPU/DPU clock getter를 구현하면 `2` 이상,
  performance-policy session 네 메서드까지 구현하면 `3` 이상
- `getDpuUnderrunCount()`: boot 이후 monotonic 누적값, 미지원이면 `-1`
- `getDpuUtilizationPercent()`: 동일 sampling interval의 active/total cycle, 미지원이면 음수
- `getMemoryBusUtilizationPercent()`: DDR/interconnect 기준 busy %, 미지원이면 음수
- `getCompositionLayerCounts()`: 앱 display의 `[DEVICE, CLIENT]`, 미지원은 `[-1, -1]`
- `setCompressionMode(0)`: linear/default
- `setCompressionMode(1)`: SBWC auto
- `setCompressionMode(2)`: SBWC required; 실제로 강제·검증할 수 없으면 `false`
- `getLastCompressionState()`: allocator/mapper가 확인한 실제 상태
- `setNpuLoad()`: accelerator 이름/모델/throughput이 `getNpuStatus()`에 포함되어야 함
- API v2 `getGpuUtilizationPercent()`: 동일 sampling interval의 GPU active/total cycle,
  미지원이면 음수
- API v2 `getGpuFrequencyHz()`, `getDpuFrequencyHz()`: read-only Hz counter, 미지원이면
  음수. 앱은 0~20 GHz만 허용하고 MHz로 표시
- API v3 `beginPerformanceSession()`, `renewPerformanceSession()`,
  `getPerformanceSessionState()`, `endPerformanceSession()`: 아래 Battery Saver
  bounded-lease 계약 전체를 구현한 경우에만 광고

API v2 메서드는 기존 AIDL 메서드 뒤에 append되어 API v1 transaction ID를 유지합니다.
클라이언트는 `getApiVersion() >= 2`를 확인한 경우에만 세 신규 transaction을
호출합니다. Provider가 v2를 광고하면 세 메서드가 짧고 non-blocking이어야 하며,
지원하지 않는 개별 값은 음수로 반환해야 합니다. 유효한 vendor GPU/DPU clock은
`HARDWARE_COUNTER`로 kernel fallback보다 우선합니다.

클라이언트는 optional v2 transaction을 v1/exact-counter lane과 분리된 bounded
no-backlog lane에서 호출하되, v1과 v2를 하나의 snapshot deadline과 service session에
결속합니다. 개별 v2 getter의 예외·timeout 또는 이미 실행 중인 v2 호출은 그 getter를
`N/A`로 만들지만 같은 session에서 완료된 v1 sample은 유지합니다. 대기 작업은 쌓지
않으며, 읽는 동안 provider registration/session이 바뀌면 v1을 포함한 snapshot 전체를
폐기합니다. 따라서 provider는 다음 sample의 v2 값이 오래된 호출 결과로 오염되지
않도록 각 getter 자체도 bounded/non-blocking으로 구현해야 합니다.

제품이 이 계약을 VINTF-stable `aidl_interface`로 배포한다면 app-side 파일만 바꾸면
안 됩니다. v2/v3 API dump를 순서대로 생성·review한 뒤 freeze하고 hash/version metadata,
`versions_with_info`, VINTF manifest와 compatibility matrix를 함께 갱신해야 합니다.
Provider/client product branch에는 같은 frozen 정의를 사용하고, v1/v2 provider와 v3
client 조합에서 광고된 version보다 뒤의 transaction이 호출되지 않는 호환성 test를
제품 빌드에 포함하세요.

Exynos DECON, Qualcomm SDE, MediaTek DISP에는 Galaxy 제품 전체에서 공통으로 쓸 수 있는
안정적 DPU utilization sysfs ABI가 없습니다. HWC DEVICE layer 수, GPU busy, 예상
traffic, DPU frequency를 utilization로 변환하지 말고 제품의 PMU/driver sampling
interval에서 직접 계산한 값만 반환하세요.

### API v3 Battery Saver performance-policy 계약

현재 v3 control bit는 `DISABLE_BATTERY_SAVER = 1` 하나뿐입니다. Thermal mitigation,
low-memory safety, Doze/device-idle, CPU/GPU/DPU DVFS, devfreq governor와 frequency
lock/write는 요청 bit로 정의되어 있지 않습니다. Provider는 알 수 없는 bit나 복합
요청을 거부해야 하며, 앱도 지원되지 않는 제어를 자동 fallback이나 성공으로
표시하지 않습니다. Doze/device-idle은 현재 start/continuity 조건으로만 검사합니다.

앱은 BEGIN 전에 `PowerManager.isPowerSaveMode`를 읽어 original state를 별도로
보존합니다. Original이 ON이면 broker가 테스트 동안 OFF로 바꿔도 앱의 power-save
layer/FPS/workload cap은 계속 적용됩니다. Provider는 요청을 받은 시점의 system policy를
원상복구 authority로 capture하고 실제 Battery Saver OFF를 확인한 뒤에만 BEGIN의
`commandVersion`을 그대로 반환해야 합니다. 단순 enqueue나 setting write 성공은
acknowledgment가 아닙니다.

Session identity는 `(clientToken, sessionId)`이고 command version은 같은 session에서
단조 증가합니다. Provider는 새 version을 policy commit과 원자적으로 비교·기록해,
늦게 끝난 낮은 version의 BEGIN/RENEW가 더 높은 version의 END를 되돌리지 못하게 해야
합니다. Mutating method는 요청 상태가 실제 적용된 뒤에만 입력 `commandVersion`을
반환하고, 실패·stale·unknown은 그 값과 구분되는 결과를 반환해야 합니다.
`getPerformanceSessionState()`는 `minimumAppliedVersion` 이상인 같은 session이
유효할 때만 `1`, original state 복구/expiry가 확인됐으면 `0`, 판단할 수 없으면
음수를 반환합니다.

앱의 lease duration은 10초이고 정상 renewal cadence는 2초입니다. Provider는 renewal
한 번마다 deadline을 bounded하게 연장하되 expired/ended session을 재활성화해서는 안
됩니다. `clientToken`에는 mutation 전에 `linkToDeath`를 연결하고, Binder death,
lease expiry와 명시적 END에서 BEGIN 직전의 exact original state를 복구합니다.
END는 idempotent이며 실제 복구 뒤에만 해당 command version을 acknowledge합니다.
Original이 ON이면 restore 결과도 ON이어야 하고, original이 OFF이면 OFF를 유지해야
합니다. 앱은 restore가 불명확한 동안 process-wide latch로 다음 plan을 막습니다.
Timeout으로 더 높은 version의 END가 재시도되는 동안 이미 in-flight이던 같은 session의
이전 END가 exact acknowledgment를 반환할 수 있습니다. 앱은 active ownership이 이미
종료되어 그 사이에 BEGIN/RENEW가 들어갈 수 없고 newer command가 모두 END retry인
경우에만 그 응답을 복구 증거로 받습니다. 그 뒤에도 renewal Job의 실제 종료와 직접
읽은 Battery Saver의 BEGIN 전 상태 일치를 확인해야 controller owner가 해제됩니다.

Battery Saver는 앱/session별 속성이 아니라 user의 system-wide policy입니다. 따라서
provider는 다음 두 모델 중 하나를 제품 계약으로 고정해야 합니다.

- 해당 user/policy scope에 active performance lease를 하나만 허용하고 overlap을
  명시적으로 거부합니다.
- 첫 lease가 capture한 original baseline 하나와 active-session refcount를 공유하고,
  각 lease의 END/death/expiry를 독립 정산한 뒤 마지막 lease가 끝날 때만 original
  baseline으로 복구합니다.

두 모델 모두 두 번째 BEGIN이 이미 임시 OFF인 값을 새 original baseline으로 저장하면
안 됩니다. Caller UID/signature, Android user와 policy scope를 검증하고, 서로 다른
UID/user의 lease가 한 전역 상태를 독립적으로 복구하는 race를 허용하지 마세요.
Provider process crash가 Battery Saver를 OFF에 남기지 않도록 policy owner와 같은
신뢰 경계의 watchdog 또는 restart-safe lease/baseline 보존도 제품에서 구현해야
합니다. 앱의 Binder timeout만으로 system-wide policy를 되돌릴 수는 없습니다.

Broker가 없고 Battery Saver가 이미 OFF면 앱은 policy를 바꾸지 않는 app-only
monitoring으로 실행할 수 있습니다. Saver가 ON이거나 BEGIN이 timeout/ambiguous
ticket을 남겼으면 producer를 시작하지 않습니다. Active session 중 Saver 재활성화,
interactive/device-idle 조건 변화, renewal/health 실패 또는 provider service-session
변경은 fail-closed 중단 사유입니다. 정상 END에서 original ON이 복원되는 전이는
RESTORING 상태로 분리해 실행 중 격리 상실로 오인하지 않아야 합니다.
Renewal/health/service-session integrity가 한 번 실패한 process는 이후 exact END로
원래 Battery Saver가 복구되더라도 새 session을 시작하지 않으며, 앱 process 재시작과
제품-side failure 원인 확인이 필요합니다.

서비스가 SBWC required 요청을 받았지만 최종 buffer modifier/metadata를 확인하지
못했다면 성공을 반환하면 안 됩니다. 앱은 모든 phase의 route 응답을
`COMPRESSION_ROUTE`, 종료/reset 응답을 `COMPRESSION_RESET` event로 기록합니다.
Adapter의 거부/timeout, 활성 SBWC 중 adapter 상실, linear/default reset 미확인은
fail-closed `ABORTED`이며 남은 plan을 실행하지 않습니다. 따라서 provider의 `true`는
단순 request enqueue가 아니라 해당 route가 확인되었다는 bounded acknowledgment여야
합니다. `setCompressionMode(0)`도 실제 안전 default 복구를 확인한 뒤에만 `true`여야
합니다.

정상 cooldown에서도 앱은 마지막 SBWC/decoder/GL phase를 유지한 채 reset하지 않습니다.
Phase/target null 게시와 generated-load zero 이후 실제 Surface/codec/EGL/Canvas
producer teardown acknowledgment를 먼저 확인하고 그 다음 `setCompressionMode(0)`을
호출합니다. Provider는 producer가 아직 해당 allocation을 소유한 상태에서 route
reset이 들어오는 흐름을 정상 순서로 가정하면 안 되며, teardown 또는 reset 확인 실패
뒤 후속 plan이 오지 않는 fail-closed 동작을 허용해야 합니다.

동일한 ordering은 scenario 내부 route 변경에도 적용됩니다. 앱은 이전 load/NPU의 zero
acknowledgment와 renderer teardown을 먼저 확인한 뒤 새 `setCompressionMode(...)`를
호출하고, 그 후에만 새 producer generation을 게시합니다. Run warm-up은 route 적용 전에
codec/SBWC allocation을 만들지 않는 1-layer RGB/DISPLAY producer입니다. Activity
destroy 시 process-wide producer lease가 남아 있으면 bridge/NPU는 닫지만 compression
reset은 생략하며, sticky cleanup 상태가 다음 controller의 recovery를 차단/직렬화합니다.
현재 구현은 lifecycle destruction이 Compose/AndroidView teardown의 동기 증거가
아니라고 보고 lease 관찰 여부와 무관하게 `close()`에서 compression reset을 호출하지
않습니다. 비선형 route가 active/unknown일 때만 sticky latch를 남기며, RGB-only
renderer teardown 지연은 compression latch를 만들지 않습니다.

비선형 route를 적용한 `setCompressionMode()` acknowledgment는 해당 Binder service
session ID와 결속됩니다. Active SBWC 중 실제 service disconnect/reconnect로
process-local registration ID가 없어지거나 바뀌면 앱은
`COMPRESSION_SESSION_CHANGED`로 즉시 중단합니다. 이 registration ID는 remote
telemetry transaction의 성공 여부와 별도로 `connectionLock` 아래에서 읽으므로,
고부하로 `snapshot()`이 timeout/null이 된 것만으로 disconnect를 오인하지 않습니다.
Route 전환 후 preparation과 모든 active tick은 target의 discrete allocation topology를
유지합니다. Transition fraction-zero origin은 FPS/workload 같은 연속 값에만 적용되며,
이미 vendor에 target mode를 설정한 뒤 이전 RGB/SBWC topology를 다시 게시하지 않습니다.

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

앱의 reflection 제어 lane은 daemon thread, bounded queue, 짧은 초기화/종료 timeout과
latest-wins setpoint를 사용해 phase 변경 때 오래된 NPU request backlog가 쌓이지 않게
합니다. 다만 Java reflection으로 interruption을 무시하는 vendor method를 강제로
종료할 수는 없습니다. 제품 adapter는 별도 broker process의 bounded call, lease token,
heartbeat, `linkToDeath`와 watchdog으로 client death/timeout 시 accelerator 부하를
반드시 0으로 복구해야 합니다.

앱은 vendor telemetry, compression safety control, NPU latest-wins를 별도 bounded
executor lane으로 분리합니다. 따라서 정상 범위의 느린 snapshot이 compression reset을
queue에서 막지 않습니다. Plan 항목 경계에서는 사용 가능했던 각 NPU backend의 기존
명령과 같은 lane에 zero/stop confirmation을 넣고 응답을 확인하며, enqueue만으로
release 성공을 보고하지 않습니다. Activity 종료에서는 NPU lane의 in-flight command가
quiesce된 뒤에 온 stop 응답만 최종 확인으로 인정합니다. Quiesce/응답이 timeout되면
앱은 미확인으로 기록하고 process-wide latch로 후속 reflection adapter 초기화와 새
plan을 차단합니다. 종료 lane이 interruption을 무시해 closed bridge가 process 안에
격리되면, 그 bridge의 과거 stop/reset 응답은 이후 controller의 cleanup 증거로
재사용하지 않습니다. Provider lease/watchdog는 여전히 최종 안전망이어야 합니다.

Positive phase 적용에도 같은 lane의 command ticket/acknowledgment를 사용합니다.
Acknowledgment 이전에는 적용 성공이나 measured NPU load로 보고하지 않으며 active
phase의 health poll이 실패하면 그 phase와 남은 plan을 중단합니다. 이 계약은 ordered
zero cleanup과 별개로 둘 다 만족해야 합니다.

Reflection adapter constructor가 200 ms 안에 끝나지 않고 interruption도 무시하면 Java
프로세스가 그 thread를 강제 종료할 수 없습니다. 앱은 같은 프로세스에서 후속
constructor 시도를 차단해 Activity 재생성마다 stuck thread가 누적되지 않게 하지만,
제품 구현은 constructor를 non-blocking으로 유지하고 무거운 초기화는 broker process로
옮겨야 합니다.

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

초기 bring-up에서만 앱의 fallback reader를 사용할 수 있습니다. 아래에서 읽을 수 있는
설정들을 순서대로 merge하며, 뒤 설정의 같은 key가 앞 값을 덮어씁니다.

- 앱 내부 `files/probe_paths.conf`
- `/vendor/etc/dpulayerlab/probe_paths.conf`
- `/data/local/tmp/dpulayerlab-probes.conf` — `BuildConfig.DEBUG`인 debug APK에서만

허용 key와 `/sys/` 또는 `/proc/`의 명시적 절대 경로만 받아들이며 임의 path 탐색은
하지 않습니다.

지원 key:

```ini
# GPU busy format 중 정확히 하나만 선택:
# gpu_busy=/sys/...
# gpu_busy_percent=/sys/...
# gpu_busy_window=/sys/...
# gpu_busy_mtk_triplet=/sys/...
# GPU frequency unit/format 중 정확히 하나만 선택:
# gpu_frequency_hz=/sys/...
# gpu_frequency_khz=/sys/...
# gpu_frequency_mhz=/sys/...
# gpu_frequency_index_khz=/sys/...
# bus_busy=/sys/...
# dpu_busy=/sys/...
# dpu_frequency_hz=/sys/...
# dpu_underrun=/sys/...
```

값의 포맷:

- legacy `gpu_busy`: 단일 0~100 값 또는 **누적** `busy total` 두 정수
- `gpu_busy_percent`: 단일 0~100 direct percentage
- `gpu_busy_window`: 한 sampling window의 `busy total`. Qualcomm KGSL `gpubusy`는
  이 형식이며 이전 sample과 다시 delta를 계산하지 않음. `0 0`은 powered-off 0%
- `gpu_busy_mtk_triplet`: MediaTek GED의 정확한 `loading blocking idle` 3개 정수.
  세 값 모두 0~100일 때 첫 값을 GPU busy로 사용
- `bus_busy`, `dpu_busy`: 단일 0~100 값 또는 누적 `busy total` 두 정수
- `gpu_frequency_hz`, `gpu_frequency_khz`, `gpu_frequency_mhz`: key suffix에 명시된
  단위의 0 이상 정수. 세 typed key와 legacy key 중 정확히 하나만 설정해야 하며 둘
  이상이면 단위를 추측하지 않고 GPU clock을 `N/A`로 처리함
- legacy `gpu_frequency`는 호환성을 위해 **Hz로만** 해석함. 새 BSP 설정에는 typed
  key를 사용함
- 앱에 내장된 KGSL `cur_freq`/`gpuclk`와 legacy Mali `devfreq0/cur_freq`는 Hz,
  `clock_mhz`와 Samsung compatibility `gpu_clock`은 MHz로만 해석하며 크기 기반
  Hz/kHz/MHz 추측을 하지 않음
- `gpu_frequency_index_khz`: 정확한 `<OPP index> <frequency kHz>` 두 정수. MediaTek
  GED bring-up에서 exact product path를 검증한 경우에만 사용
- `dpu_frequency_hz`: 반드시 Hz 단위인 0 이상의 정수. 제품에서 명시적으로 설정한
  경로만 읽으며, 단위가 불명확한 일반 경로를 자동 탐색하지 않음
- `dpu_underrun`: monotonic 누적 정수

`dpu_frequency_hz`를 포함한 모든 probe는 read-only입니다. 앱은 DPU clock,
devfreq/governor 또는 전원 상태를 쓰거나 고정하지 않습니다. DVFS settle/shock
시나리오는 governor가 낮은 clock을 선택할 기회를 줄 뿐 실제 clock 하강을 강제하거나
보장하지 않습니다.

예제는 `system_integration/vendor/probe_paths.conf.example`에 있습니다. 이 fallback은
파일 값을 그대로 읽을 뿐 SELinux를 우회하지 않습니다. `/data/local/tmp` 설정은
bring-up용 debug convenience이며 release APK에서는 로드되지 않습니다.

내장 GPU 후보는 임의 directory scan 없이 다음 검증된 고정 ABI만 시도합니다.

- Qualcomm: KGSL `gpu_busy_percentage`, `gpubusy` window pair;
  `cur_freq`/`gpuclk` Hz와 `clock_mhz`/`gpu_clock` MHz
- Exynos Xclipse(AMD RDNA): `/sys/class/drm/card0/device/gpu_busy_percent` direct
  percent. 다른 DRM minor를 쓰는 제품은 `gpu_busy_percent`로 exact path를 지정
- 구형/비-Xclipse Exynos Mali: `/sys/class/misc/mali0/device/utilization` direct percent와
  `devfreq/devfreq0/cur_freq` Hz
- MediaTek: `/sys/module/ged/parameters/gpu_loading` direct percent
- 호환 SKI `/sys/kernel/gpu/gpu_busy`는 위 architecture-specific ABI를 가리지 않도록
  마지막 direct-percent 후보로만 사용

GED debugfs와 address가 포함된 devfreq/platform directory는 기본 탐색하지 않습니다.
제품이 explicit typed key를 하나 설정하면 그 경로가 authoritative하며 unreadable,
malformed, 단위 충돌일 때 다른 기본 probe로 우회하지 않고 `N/A`입니다. 여러 GPU
busy format key 또는 여러 GPU frequency unit key를 동시에 선언해도 의미가 충돌하므로
`N/A`입니다. Platform signing만으로 이 node들의 SELinux/DAC 접근이 생기지 않으므로
locked Samsung 제품은 위 API v2 broker를 우선 구현하세요.

예제 probe 설정은 모든 항목이 주석 상태입니다. 필요한 target 항목만 정확히 하나
활성화해야 하며, placeholder나 다른 SoC용 항목을 그대로 배포하면 authoritative
explicit probe가 정상 built-in 후보를 차단합니다. Legacy `gpu_busy`가 scalar와 누적
pair 중 어떤 형식으로 해석됐는지는 source에 `observed=…`로 남겨 형식 전환 시
graph/peak continuity를 끊습니다.

## Exact counter와 report 계약

`getDpuUnderrunCount()` 또는 allowlisted kernel counter의 baseline은 Surface/codec
warm-up이 끝난 뒤 실제 scenario phase 직전에 잡습니다. 앱은 1초 HUD sampler와
post-warm-up baseline 요청을 한 lane에서 직렬화하고 fresh sample 완료를 기다립니다.
Run generation 이전에 요청된 in-flight sample은 다음 queue 항목의 counter/peak/sample로
귀속하지 않습니다. Baseline 이후 source,
`MetricQuality`, monotonic value가 연속적인지 검사하며 unavailable/source 변경/
quality 변경/reset/regress 시 `EXACT_COUNTER_INVALIDATED` event를 남깁니다. 연속 구간에서
이미 본 양의 delta는 underrun 증거로 보존하지만, delta 0은 baseline 뒤 sample이 있고
run 끝까지 연속성이 유지된 경우에만 `CLEAN` 근거가 됩니다.

정상 verdict를 계산하기 전에는 final physical producer teardown barrier를 통과한 뒤
같은 serialized lane에서 fresh terminal sample을 수집합니다. Terminal/periodic sample
실패는 telemetry gap으로 exact continuity를 무효화하고, 5초 stale은 run도 중단합니다.
Source/quality 변경 또는 reset/regress도 continuity를 무효화합니다. 이때 신뢰할
delta가 없으면 report/UI에서 baseline source를 유효한 delta provenance처럼 남기지
않고 source/quality를 `N/A`/`UNAVAILABLE`로 기록해야 합니다.

JSON report의 현재 `schemaVersion`은 2입니다. Consumer는 exact delta와
source/quality, telemetry source, phase transition, event, sample을 별도 필드로
해석해야 하며 유한하지 않은 수가 `null`일 수 있음을 처리해야 합니다. Provider는
counter scope(display/CRTC), boot/reset/wrap 의미와 sampling interval을 제품 계약에
명시해야 합니다.

연속성이 확인된 exact delta가 있으면 exact verdict가 proxy보다 우선합니다. Exact
delta가 0인 동안 frame-deadline proxy가 증가해도 verdict는 `CLEAN`이고 proxy는 별도
`PROXY_SIGNAL` event/수치로만 보존합니다. Adaptive boundary는 topology 준비 직전과
active phase 종료 직후의 serialized fresh sample을 사용해 setup/tail 증가를 포함하며,
두 sample의 source/quality/monotonic continuity가 다르면 exact boundary delta를
사용하지 않습니다.

앱은 phase의 generation이 승인한 모든 physical producer buffer를 합산하고, 100 ms
control tick과 thermal/recovery 변경을 반영한 `producer FPS × physical producer count`
적분값과 비교합니다. Flattened backend의 physical count는 logical layer 수와 무관하게
1입니다. 기대 aggregate가 30 frame 이상인데 actual이 70% 미만이면
`PRODUCER_RATE_SHORTFALL` event를 남깁니다. Verified exact underrun delta가 양수면 exact
판정이 우선하고, 그렇지 않으면 부하가 실제로 수행됐다고 볼 수 없어 `INCONCLUSIVE`입니다.
Active topology가 pending으로 바뀌면 callback timestamp와 physical total에서 적분을
즉시 정산·pause하고 CPU/memory/NPU setpoint를 0으로 내린 뒤 새 relay set의
commit/restart 뒤에만 resume합니다. HUD expected count는 unpublished/pending/
process-lease 동안 `—P`이며 frame-budget용 committed count와 분리합니다.

Broker가 반환하는 NPU/compression status는 신뢰하지 않는 문자열입니다. 앱은 각 값을
HUD, telemetry sample, JSON report에 넣기 전에 UTF-16 기준 256자로 제한하고
whitespace/control/format 문자를 정규화합니다. 제품 broker도 작은 enum/typed state를
우선 사용하고 비정상적으로 큰 문자열을 반환하지 않아야 합니다.

실행 HUD에는 build version과 layer/DPU/CPU/GPU 숫자·graph가 함께 표시됩니다. 각
gauge는 source와 `MetricQuality`를 보존하며 provenance가 바뀌거나 unavailable인
sample 앞뒤를 한 선으로 연결하지 않고 gap으로 표시합니다. BSP adapter는 source
문자열을 동적으로 바꾸어 서로 다른 counter를 하나의 연속 추세처럼 보이게 해서는 안
됩니다.

Result peak도 provenance 계약을 따릅니다. CPU/memory/generated traffic과
DPU/GPU/bus/produced FPS/HWC DEVICE·CLIENT는 유효 범위의 sample이 같은 quality/source를
유지할 때만 집계하며, 도중 source가 바뀌면 서로 다른 계측을 합친 peak 대신 `N/A`를
표시합니다. DPU/GPU/bus/FPS/HWC peak는 schema에 중복 필드를 추가하지 않고 보존된
sample에서 결과 UI가 계산합니다.

보고서는 credential-encrypted internal `files/reports`에서 temp write/fsync/rename으로
발행한 뒤 `dpu-layer-lab-` prefix와 앱 파일명
형식이 확인된 완료 `.json` 최근 200개를 process-serialized best-effort로 보존합니다.
방금 발행한 파일은 prune에서 보호하고 `.part`나 unrelated `.json`은 자동 삭제하지
않습니다. FileProvider의 internal `files-path` 중 canonical `files/reports` 안에
실제로 존재하고 managed completed `dpu-layer-lab-…json` 이름을 통과한 파일만
공유합니다. Traversal, 외부/foreign JSON과 누락 파일은 거부하며 과거 external
보고서는 자동 import하지 않습니다. Cloud/D2D/legacy backup rule은 report뿐 아니라
`probe_paths.conf`를 포함한 모든 app data domain을 제외합니다.

## Workload / composition 의미

Workload setpoint는 정확한 0 또는 `0.001`보다 큰 값만 허용합니다. `0 < load <= 0.001`은
표시상 양수와 실제 worker idle이 달라질 수 있어 실행 전에 거부합니다. 양의 GPU load는
flattened GPU-backed producer 또는 독립 GL producer가 실제 topology에 있어야 하며,
graphics budget 때문에 이를 제거해야 하면 load를 0으로 낮춰 성공처럼 보이지 않고
plan을 거부합니다.

`DEVICE_ONLY`/`CLIENT_REQUIRED` phase는 topology를 강제한다는 뜻이 아니라 fresh
DEVICE/CLIENT 원자 쌍으로 확인할 관측 계약입니다. Runtime safety policy가 계약
phase의 layer topology, producer FPS, display pacing, GL producer 또는 GPU pressure를
clamp해야 하면 축소된 topology로 우연히 조건을 통과시키지 않고 preflight에서
거부합니다. 3초 first-buffer readiness, 최대 4초의 pre-target periodic sample mutex
drain, probe당 4초 bounded telemetry와 post-target 관측 tick을 포함하도록 한 번의
fresh evidence가 필요한 `DEVICE_ONLY`는 최소 12초, 서로 다른 fresh evidence 2회가
필요한 `CLIENT_REQUIRED`는 최소 16초를 유지해야 합니다.
Target arm 중 periodic telemetry는 mutex에 blocking waiter를 만들지 않고 try-lock
실패를 drop하는 latest-wins 방식으로 동작합니다. CLIENT forced sample 2회는 같은
serialized ownership에서 수행하며 각 sample 사이에 cancellation, thermal contract,
fresh producer count와 topology revision을 재검증합니다. 각 forced sample은 전체
safety/exact telemetry를 함께 갱신하고, terminal/cancel/error/phase-finally에서는
matching priority owner를 반드시 해제합니다.

START plan은 첫 scenario 전에 전체 queue/repeat가 공유하는 capacity 관측을 한 번
수행합니다. Safety/graphics budget이 승인한 최대 20개의 30fps opaque RGB Surface를
non-overlap tile로 배치하고 모든 first buffer/topology publish 뒤 100ms 안정화한 다음
fresh composition 원자 쌍을 한 번만 읽습니다. 준비/쌍 실패는 retry나 0으로 바꾸지 않고
N/A입니다. 이어서 load zero, renderer teardown과 3초 settle을 확인한 뒤 기존 1L
scenario warm-up/fresh baseline을 시작하므로 calibration traffic/frame/counter는
scenario evidence에 포함되지 않습니다.

관측값은 해당 candidate topology의 D/C일 뿐 maximum plane 수가 아닙니다. matching
opaque RGB topology의 DEVICE/CLIENT boundary 참고로만 queue/repeat에서 공유하고
ScenarioSafetyPolicy hard cap은 변경하지 않습니다. Format, transform, alpha, scaling,
display 또는 system surface가 바뀌면 fresh vendor evidence가 필요합니다.

Composition source는 API v1 `compositionLayerCounts`의 같은-snapshot 원자 쌍을
우선합니다. Dashboard/idle은 3-sample cadence의 bounded `dumpsys SurfaceFlinger
--hwclayers`를 사용할 수 있고 plan-start capacity 관측은 cache를 우회한 fresh fallback
한 번을 허용합니다(child 800ms/전체 sample 4초 상한). Active scenario에서는 periodic과
typed target boundary 모두 dump를 억제하며 fresh vendor 원자 쌍만 인정합니다. 따라서
capacity cache가 phase 증거가 되지 않고 vendor pair가 없으면 N/A/INCONCLUSIVE가
올바른 결과입니다. HWC count를 logcat 또는 임의 sysfs/debugfs path에서 추론하지 마세요.

Transition controller는 absolute-deadline 100 ms fixed period를 사용하고 늦은 tick을
busy catch-up하지 않습니다. Preflight window 검증과 별도로 runtime coverage가 ramp
중간값, staircase의 모든 level, pulse ON/OFF, triangle 상승/하강, soak
attack/hold/recovery를 실제로 보지 못하면 `INCONCLUSIVE`입니다.

`Z_ORDER_SWAP`은 Android View/client ordering을 움직이는 proxy입니다. Report의 typed
motion semantics는 이를 physical HWC plane Z-order 변경으로 표시하지 않으며
`physicalHwcZOrderChange=false`를 유지합니다. 실제 plane 순서는 vendor/HWC typed
snapshot에서 별도로 확인해야 합니다.

## 4K/8K 자산

APK에는 대형 영상을 넣지 않았습니다. 테스트 화면에서 SAF URI로 로컬 영상을 선택하면
현재 앱 세션의 read grant로 provider가 연 seekable `AssetFileDescriptor`를 pin하고,
preflight와 renderer가 이 open file description의 dup을 사용합니다. 그 뒤
`MediaExtractor` track을 선택하고 `MediaCodec` output을 primary Surface에 직접 보내
반복 decode합니다. URI를 영구 저장하지 않으므로 persistable grant도 보관하지 않습니다.

Provider open은 5초, native `MediaExtractor` 검사는 10초 제한의 별도 daemon worker에서
실행합니다. Descriptor open 직전부터 parser 종료까지 process-wide single preflight
lease가 유지되고 각 worker는 refcount hold를 가집니다. Timeout/cancel 시 signal과
interrupt를 보내고 run을 중단하지만 외부 provider/native parser가 즉시 반환했다고
가정하지 않습니다. Worker가 실제 `finally`에서 descriptor/`MediaExtractor`를 해제하고
hold를 반납할 때까지 Activity 재생성을 포함한 후속 plan은 fail-closed입니다. 제품
ContentProvider도 cancellation signal에 신속히 응답하고 seek 가능한 regular asset을
반환해야 하며 pipe/stream descriptor를 사용하면 안 됩니다.

Capability와 phase 사전 검증에는 `MediaMetadataRetriever`의 container MIME이 아니라
`MediaExtractor` video track의 실제 decoder MIME, encoded dimensions, crop으로 계산한
visible dimensions, FPS, profile과 codec string을 사용합니다. Crop의 horizontal
left/right pair와 vertical top/bottom pair는 독립 처리합니다. 한 축의 pair가 모두
없으면 encoded frame의 그 축 전체를 사용하고, pair 중 lone key나 범위 오류는
fail-closed합니다. Source FPS metadata가 없거나 허용 오차를 포함해 phase target과
reachable transition FPS 요구보다 낮으면 거부합니다. Codec capability의 size는 exact
encoded dimensions, rate는 source/decoder-phase/reachable-transition FPS의 최댓값으로
확인합니다. Gradual transition은 직전 phase FPS 전체, STEP boundary는
`min(60, 직전 FPS)`를 포함합니다. 선택 영상을 쓰는 P010 decoder phase는 extractor가
HEVC Main10 계열,
AV1 Main10 계열 또는 AVC High10을 확인해야 합니다. VP9 Profile 2는 10/12-bit
4:2:0을 함께 포괄하므로 `MediaFormat.KEY_CODECS_STRING`이 512자 이하이고 canonical
`vp09.02.<level>.10...` 형식으로 bit-depth 10을 명시한 경우에만 허용합니다. 여러
VP9 entry의 bit depth가 충돌하거나 profile field가 non-canonical이거나 string이
oversized/malformed이면 `N/A`/거부로 fail-closed합니다. Profile/필수 codec-string
bit depth가 미확인되거나 8-bit/12-bit 입력이면 실행하지 않습니다. 4:2:2/4:4:4
계열인 VP9 Profile 3과 `KEY_PROFILE`만으로 정확한 10/12-bit Surface layout을 확정할
수 없는 Dolby Vision은 P010 gate 또는 3 B/px linear reference의 근거로 사용하지
않습니다. 영상 미선택 YUV/P010/SBWC decoder는 RGBA visual proxy로 대체하지 않고
preflight에서 fail-closed합니다.

YUV/P010/SBWC decoder route는 선택·pin·검증된 영상과 concrete codec binding이
필수이며 동일한 codec-to-Surface primary 계약을 사용합니다. `SBWC_REQUIRED`는
decoder 콘텐츠를 사용하더라도 compression route를 먼저
vendor adapter로 적용·검증해야 하며, 거부/timeout 또는 reset 미확인은 기존처럼
fail-closed입니다.

Precheck는 위 size/rate를 만족하는 concrete hardware codec name을 고릅니다. P010
phase일 때만 extractor profile과 codec의 advertised profile을 exact-match하고,
YUV/SBWC는 size/rate를 확인합니다. URI/MIME/codec name, encoded/visible dimensions,
source FPS, profile, codecs/P010 verification을 immutable binding으로 renderer에
전달해 `MediaCodec.createByCodecName()`으로 같은 codec을 사용합니다. Renderer는 URI를
다시 열어 fingerprint와 crop pair validity를 재검증하며 binding이 없거나 stale이면
procedural proxy로 대체하지 않고 fail-closed합니다.

Source `MediaFormat.KEY_MAX_WIDTH/HEIGHT`는 pair가 모두 absent이거나 각각 exact encoded
width/height와 같을 때만 고정 해상도 입력으로 허용합니다. Partial pair, encoded보다
크거나 작은 adaptive declaration은 거부합니다. Renderer는 pinned descriptor에서 이
pair까지 fingerprint로 다시 확인한 뒤 fixed-resolution run의
`MediaCodec.configure()` 직전에 두 `KEY_MAX_*`를 모두 제거합니다.

Graphics-memory hard budget과 output allocation guard에는 encoded width/height를 각
축 64 px 단위로 올림한 ceiling을 RGBA triple-buffer 기준으로 보수 반영합니다. 이
ceiling은 오직 graphics budget/output guard용이며 codec capability, `KEY_MAX_*` 또는
adaptive-playback 최대 source 크기가 아닙니다. GL producer budget은 RGBA color에 더해
driver의 24/32-bit 확장을 고려한 4 B/px depth attachment도 별도로 triple buffering합니다.
Output encoded dimensions가 ceiling을 넘거나 crop pair 내부에 lone key/범위 오류가
있거나 visible resolution이 동적으로 바뀌면 fail-closed합니다. Track fingerprint를
확인할 수 없거나 decoder primary 한 장도 budget을 넘으면 render 전에 거부합니다.

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

## 통합 검증

Host build/test의 기준 명령은 다음과 같습니다.

```powershell
$env:JAVA_HOME='<JDK_17_HOME>'
$env:ANDROID_HOME='<ANDROID_SDK_ROOT>'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

제품 이미지에서는 추가로 다음을 확인합니다.

- release alias만 `CONTROL_TESTS`로 보호되고 implicit resolution/direct Main control이
  거부되는지
- exact counter의 post-warmup baseline, post-teardown terminal sample, telemetry gap,
  source/quality 변화, reset/wrap, invalid-delta provenance와 stable-source peak
- SBWC route 거부/timeout/provider death와 linear reset 실패가 fail-closed이고 모든
  route/reset event가 report에 남는지, 정상 cooldown에서 physical producer teardown
  acknowledgment가 compression reset보다 먼저인지
- NPU/SBWC lease expiry, client death와 provider watchdog이 load/default state를
  복구하는지
- API v3가 original Battery Saver ON/OFF 각각에서 BEGIN/2초 renewal/10초 expiry/
  client death/idempotent END 뒤 exact prior state를 복구하는지. Original ON을
  임시 해제해도 앱의 power-save cap이 유지되고, late/stale BEGIN이 더 높은 END를
  되돌리지 않는지
- Battery Saver system-wide arbitration이 single-lease 또는 shared baseline/refcount
  중 문서화한 모델을 따르고, overlapping UID/session, provider restart와 마지막
  lease expiry에서도 임시 OFF 상태를 남기지 않는지
- Broker unavailable+Saver OFF는 monitor-only, Saver ON/ambiguous mutation은
  producer 전 거부인지. Doze/device-idle을 강제 해제하거나 thermal/low-memory
  보호를 끄거나 DVFS/governor/frequency를 쓰지 않는지
- 양의 NPU command ticket/acknowledgment와 active health failure가
  `NPU_WORKLOAD_APPLY_FAILED`로 fail-closed하는지. Plan-start immutable 앱 선제
  SEVERE 옵션이 기본 OFF이고 OFF에서는 Android/kernel mitigation을 방해하지 않으며,
  ON에서만 ordered zero → reduced workload acknowledgment → display acknowledgment
  순서를 지키는지
- Thermal CRITICAL, low-memory, local-worker failure와 power/display/SystemUI 격리
  무결성 fail-safe가 SEVERE 옵션과 무관하게 항상 활성인지
- local CPU/memory worker의 예상하지 못한 `Throwable` 또는 active external interrupt가
  first-wins process latch, `LOCAL_WORKER_FAILURE`, `ABORTED`를 만들고 process 재시작
  전까지 후속 worker/plan을 차단하는지, partial start 뒤 same-owner overlap도 막는지
- Activity 재생성/partial start/cancel에서 receiver callback이 먼저 detach되고
  backend job/thread/process/descriptor의 실제 terminal 증거 전에는 cleanup gate가
  다음 controller/run을 막으며, application context 경로가 파괴된 Activity를
  보존하지 않는지
- memory workload의 worker별 allocation/page-touch prewarm이 measured byte baseline
  전에 끝나고 generated traffic에 포함되지 않는지, allocation/ack timeout이
  fail-closed인지
- 이전 Surface/codec의 늦은 frame이 generation이 바뀐 phase startup을 만족하지 않는지
- 100 ms cadence에서 실제 transition window가 중간 tick/각 step/한 cycle/
  attack-hold-recovery를 보존하고, STEP target이 fresh baseline과 origin buffer 뒤의
  measured tick에서만 적용되는지, noncyclic nonzero floor plan이 reject되고 pure
  evaluator의 defensive fallback도 origin을 건너뛰지 않는지, absolute-deadline loop와
  runtime coverage 누락이 `INCONCLUSIVE`인지
- aggregate physical producer actual/expected가 30 frame 이상에서 70% 미만이면
  `PRODUCER_RATE_SHORTFALL`과 exact-positive 우선/그 외 `INCONCLUSIVE`를 만드는지,
  flattened count가 1인지, topology-pending callback 경계에서 적분과 교차 부하가
  즉시 멈추고 HUD가 `—P`를 표시하는지
- 비절전 envelope 실행 중 Battery Saver 전환이 `SAFETY_ENVELOPE_CHANGED`로 중단되고
  후속 plan이 현재 전원 상태로 다시 검증되는지
- display identity/physical-size 변경과 thermal workload/display derate 적용 실패가
  fail-closed로 중단되는지
- `0 < workload <= 0.001`이 reject되고, 양의 GPU load가 실제 GPU producer를 요구하며
  flattened 1-layer GPU intensity가 bounded extra hardware-canvas work를 바꾸는지
- 과대/제어문자/양방향 제어문자를 포함한 broker status가 256자 안에서 정규화되는지
- 선택 media 없는 YUV/P010/SBWC decoder가 proxy 없이 거부되는지, 4K/8K/P010 자산의
  encoded/visible dimensions·FPS·profile·codecs fingerprint,
  horizontal/vertical crop pair, absent/exact source `KEY_MAX_*` pair와 configure 전
  제거, source/phase/reachable-transition FPS 최대 codec capability, 64 px
  graphics/output ceiling
- provider open 5초/parser 10초 timeout·cancel 뒤 worker `finally`까지 preflight
  refcount lease가 후속 plan을 차단하고 pinned AFD가 seekable인지
- GL color/depth triple-buffer budget 경계와 Adaptive Hunt의 `STEADY` memory plateau
- HUD build version과 gauge source/quality, provenance/unavailable graph gap,
  View/client Z-order proxy가 physical HWC 증거로 표시되지 않는지
- report share가 canonical internal managed completed file만 허용하고 traversal,
  foreign/missing JSON을 거부하는지
