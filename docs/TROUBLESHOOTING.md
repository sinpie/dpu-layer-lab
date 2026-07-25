# Troubleshooting과 안전한 진단

> **Authority:** 사용자-visible 증상에서 가능한 원인, 확인 순서와 안전한 복구 조치
> **Audience:** 시험자, 앱 개발자, BSP integrator, lab operator
> **Update when:** 새 오류 문구, sticky latch, 계측 source, build/device 절차 또는 recovery가 바뀔 때
> **Does not own:** safety 규칙 원문, source architecture, provider policy, release publish
> **Related:** [Documentation index](INDEX.md), [README.md](../README.md),
> [METRICS.md](METRICS.md), [TESTING.md](TESTING.md),
> [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md), [STATE_MACHINES.md](STATE_MACHINES.md)

진단 목표는 “숫자를 보이게 만드는 것”이 아니라 unavailable, unsupported, proxy와
실제 failure를 구분하는 것이다. 임의 root command, sysfs/debugfs 탐색, SELinux 우회,
thermal disable과 frequency lock을 해결책으로 사용하지 않는다.

## 빠른 분류

| 증상 | 먼저 확인 | 안전한 다음 조치 |
|---|---|---|
| 빌드가 시작되지 않음 | JDK 17, SDK 36, wrapper 8.13 | [Testing](TESTING.md)의 표준 명령 사용 |
| DPU/GPU/bus가 N/A | 실행 HUD의 DPU/GPU 또는 Dashboard/System의 MEM BUS·direct sensor | product provider/allowlist 확인; 0으로 대체 금지 |
| DEVICE/CLIENT가 N/A | DUMP permission 또는 current vendor 원자 쌍 | system build 권한/provider 확인 |
| CLIENT가 기대대로 늘지 않음 | format/alpha/scale/topology와 system surface | typed expectation과 fresh evidence 확인 |
| scenario가 시작 전 거부됨 | safety adjustment/rejection, media/NPU/SBWC requirement | 조건을 충족하거나 더 낮은 preset 선택 |
| 실행 중 중단됨 | terminal reason, thermal/low-memory/display/power event | 원인을 제거하고 sticky 여부 확인 |
| STOP 후 새 START 거부 | renderer/NPU/SBWC/telemetry/performance cleanup latch | 실제 종료를 기다리고 지속되면 process 재시작 |
| HWC capacity가 다시 측정되지 않음 | process-session terminal result | 의도된 동작; 새 측정은 app process 재시작 |
| report 공유 실패 | managed filename와 internal path | 앱 결과 화면에서 생성된 최신 report만 공유 |
| Intent START가 무시/거부 | explicit alias/action/extra/permission | [External contracts](EXTERNAL_CONTRACTS.md) 대조 |

## Build와 Android Studio

### JDK 또는 Gradle 오류

기준은 JDK 17, SDK 36, AGP 8.12.2, Gradle wrapper 8.13이다.

```powershell
$env:JAVA_HOME='<JDK_17_HOME>'
$env:ANDROID_HOME='<ANDROID_SDK_ROOT>'
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

- `gradle` system command 대신 tracked wrapper를 사용한다.
- `local.properties`와 절대 SDK/JDK 경로를 commit하지 않는다.
- Lint의 “새 버전 사용 가능” 경고만으로 AGP/Gradle/dependency를 부분 업그레이드하지
  않는다. Toolchain 변경은 전체 호환성 작업이다.

### Android Studio configuration이 보이지 않음

`.idea/runConfigurations/DPULayerTest_Debug.xml`과
`DPULayerTest_Release.xml`이 checkout에 있는지 확인한다. Release configuration은
unsigned APK를 만들며 local keystore를 참조하면 안 된다.

## DPU·GPU·bus metric

### 모든 vendor metric이 N/A

1. 일반 APK인지 product priv-app인지 확인한다.
2. `Sensor source`와 `MetricQuality`를 확인한다.
3. vendor service binding/permission 또는 명시적 probe config를 확인한다.
4. provider status가 timeout인지 unavailable인지 구분한다.
5. 제품 SELinux/DAC log를 BSP 쪽에서 확인한다.

Platform signing만으로 vendor node 접근이 보장되지 않는다. Portable app에 root shell이나
임의 path scan을 추가하지 않는다.

Generic `/sys/class/drm/.../underrun_count`는 display scope와 counter 의미가 제품마다
달라 portable build의 automatic exact source로 채택하지 않는다. 제품 BSP가 검증한
typed vendor source 또는 key-specific allowlisted DPU sysfs contract를 제공해야 한다.

`vendor_broker` source/status가 `CONFIG_MISSING`, permission/grant 오류, signer 또는
service contract 불일치를 표시하면 transient timeout이 아니다.
`/product/etc/dpulayerlab/vendor_broker.conf`의 explicit component와 signer digest,
permission owner, app grant와 service manifest를 제품 이미지 기준으로 고친 뒤 새
process에서 확인한다. App에서 implicit service scan을 켜거나 reconnect를 반복해
우회하지 않는다.

### Samsung Xclipse GPU가 보이지 않음

Xclipse는 Mali가 아니라 AMD RDNA 기반이다. 제품이 제공하는 DRM/driver counter 또는
vendor service를 명시적으로 연결해야 한다. Legacy Mali path를 Xclipse fallback으로
사용하지 않는다. Source 선택 기준은 [Metrics](METRICS.md)의 GPU 절을 따른다.

### 값이 0으로 고정됨

0이 실제 hardware counter인지 unsupported parser의 default인지 먼저 확인한다.
source/quality가 `UNAVAILABLE`이면 UI와 report도 N/A여야 한다. Counter unit, reset,
display scope와 sampling interval을 provider contract와 대조한다.

## HWC DEVICE/CLIENT

### SurfaceFlinger evidence가 없음

- 일반 앱은 `DUMP` permission이 없을 수 있다.
- release product APK가 priv-app allowlist를 실제로 받았는지 확인한다.
- vendor service의 composition pair가 같은 session/transaction의 완전한 쌍인지 확인한다.
- active untyped load 중 앱이 새 dumpsys child를 억제하는 것은 정상이다.

### HWC maximum처럼 보이지 않음

DEVICE/CLIENT count는 format, scale, transform, alpha, display mode와 system layer에 따라
달라지는 snapshot이다. Process-session 20L calibration도 해당 opaque RGB candidate의
관측값이지 보편적 plane maximum이 아니다.

### 최초 20L calibration이 N/A

Terminal detail에서 다음을 구분한다.

- safety/graphics budget rejection
- display identity unavailable/change
- producer topology/geometry/heartbeat 미확인
- vendor/SF 원자 쌍 unavailable
- absolute producer-active deadline
- telemetry worker quiescence 또는 teardown 실패

성공과 실패 모두 같은 process에서는 재시도하지 않는다. 반복 burst가 실제 scenario를
오염시키지 않기 위한 동작이다. 새 관측이 필요하면 app process를 완전히 종료한 뒤
재시작한다.

## Scenario와 selected media

### Safety policy가 layer/FPS/duration을 줄임

HUD와 preflight adjustment를 확인한다. Runtime RAM, graphics budget, low-RAM과
power-save envelope가 catalog 요청보다 우선한다. Hard cap을 올리거나 validation을
우회하지 않는다.

### Linear ramp가 끝까지 올라갔는데 `INCONCLUSIVE`

명목상 phase 종료 뒤 exact endpoint target을 적용한 동일 control revision frame이
committed physical producer 전부에서 확인되어야 한다. 일부 producer ACK가 없거나
revision이 다르거나 topology recovery 뒤 fresh endpoint 재측정이 timeout되면 의도적으로
`INCONCLUSIVE`다. Proof hold에서 추가된 frame은 producer-rate shortfall을 숨기는 데
사용하지 않는다.

### YUV/P010/SBWC scenario가 거부됨

다음을 모두 확인한다.

1. SAF로 선택한 seekable local descriptor
2. video track MIME, encoded/visible dimensions와 FPS
3. target/reachable FPS를 지원하는 concrete hardware codec
4. P010이면 명시적 10-bit evidence
5. SBWC이면 vendor allocation/compression acknowledgment

RGBA procedural layer로 자동 대체되지 않는 것이 정상이다.

### 8K media가 선택되지만 실행되지 않음

Track metadata와 codec capability는 exact encoded size/rate로 확인한다. 64px alignment는
allocation ceiling에만 쓰며 capability를 더 작은 크기로 속이지 않는다. Source FPS가
transition boundary를 포함한 요구 FPS에 미달해도 거부한다.

## Thermal, power와 memory

### “열 보호” 또는 thermal 중단

앱의 선제 thermal SEVERE derating은 기본 OFF지만 Android/firmware의 thermal protection은
항상 authoritative하다. Thermal CRITICAL과 low-memory abort는 앱에서 비활성화하지
않는다. 설정은 plan 시작 시 immutable하며 Intent로 바꿀 수 없다. OFF에서는 SEVERE에도
앱 setpoint를 유지하고 실제 throttling은 Android/kernel에 맡긴다. Derating을 켰다면
ordered load zero와 acknowledgment가 실패할 때 run을 중단한다.

### Battery Saver 때문에 시작되지 않음

비절전 envelope의 active run 중 Battery Saver가 다시 켜지면
`SAFETY_ENVELOPE_CHANGED`로 중단한다. Vendor API v3가 없어도 Battery Saver가 이미
OFF라면 app-only monitoring으로 실행할 수 있다. Saver가 ON이거나 원격 변경 여부가
모호한데 performance lease를 확인하지 못하면 성공처럼 계속하지 않는다. 앱은
thermal/DVFS/governor를 끄지 않는다.

### Memory allocation 또는 prewarm 실패

Graphics budget은 triple buffering, 총/available RAM, decoder ceiling과 GL depth를
포함한다. Memory worker prewarm 실패를 저부하 성공으로 처리하지 않는다. 다른 앱을
정리하거나 더 작은 scenario를 사용한다.

## Cleanup과 process restart

다음 message는 일반 retry보다 process restart가 필요한 sticky integrity failure다.

- local worker exception 또는 partial-start worker 미종료
- renderer/codec/EGL/Canvas teardown 미확인
- NPU ordered zero/adapter close 미확인
- SBWC linear/default reset 미확인
- telemetry local/SF/vendor worker quiescence 미확인
- performance policy restore 미확인
- test Window의 원래 status/navigation bar visibility 복원 미확인
- selected-media parser/refcount worker 미종료

Sticky latch를 UI에서 clear하는 기능을 추가하지 않는다. 먼저 report/log의 terminal
reason을 보존하고 앱 process를 완전히 종료한다. Provider의 global policy가 남았으면
BSP service 상태도 확인한다.

## Automation

### START가 무시됨

- `MainActivity`가 아니라 explicit `AutomationActivity` alias를 사용했는지 확인한다.
- action이 정확한지 확인한다.
- release에서 caller가 `CONTROL_TESTS`를 보유하는지 확인한다.

### START가 거부됨

- `scenario_id`와 `scenario_ids`를 동시에 보내지 않는다.
- ID는 catalog preset만 허용된다.
- `repeat_count`는 1~10, expanded plan은 최대 40회다.
- 이미 실행 중이면 새 START는 거부된다.

STOP은 malformed START extra를 읽지 않고 최신 STOP이 미실행 START를 폐기한다.

## Report와 공유

Report는 background network upload를 하지 않는다. 공유 오류가 나면 다음을 확인한다.

- 파일이 `files/reports` 아래 실제 completed JSON인지
- 이름이 `dpu-layer-lab-` prefix인지
- `.part`, traversal 또는 외부에서 넣은 foreign JSON이 아닌지
- FileProvider authority가 현재 application ID와 일치하는지

Device report를 issue/commit에 그대로 첨부하기 전에 build fingerprint, media name과
제품 정보의 privacy를 검토한다.

## 안전한 evidence 수집

Host 검증:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
git diff --check
git status --short
```

실기기 stress scenario는 사용자가 대상 실험기와 실행 범위를 명시한 뒤에만 수행한다.
승인 없이 연결된 기기에 START Intent나 장시간 stress plan을 보내지 않는다.
