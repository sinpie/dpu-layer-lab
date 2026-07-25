# External compatibility contracts

> **Authority:** 저장소 밖의 harness, report consumer, product image와 호환돼야 하는 stable identifier와 wire contract
> **Audience:** automation 작성자, report consumer, BSP·system integrator, release engineer
> **Update when:** package/component/action/extra, AIDL API, report schema/name, provider authority 또는 variant 보안이 바뀔 때
> **Does not own:** provider 배치·SELinux 구현, UI 사용법, metric algorithm, release checksum
> **Related:** [Documentation index](INDEX.md), [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md),
> [AUTOMATION.md](AUTOMATION.md), [METRICS.md](METRICS.md),
> [REPORT_SCHEMA.md](REPORT_SCHEMA.md), [RELEASE.md](RELEASE.md),
> [TESTING.md](TESTING.md), [RECONSTRUCTION.md](RECONSTRUCTION.md)

표시 이름 `DPULayerTest`와 아래 compatibility identifier는 별개다. Rebranding만으로
package, Intent, report prefix 또는 Soong module을 바꾸지 않는다. 변경이 필요하면
consumer와 product migration을 먼저 설계한다.

## Stable identifier

| 계약 | 값 | Source authority |
|---|---|---|
| release application ID | `com.example.dpulayerlab` | `app/build.gradle.kts` |
| debug application ID | `com.example.dpulayerlab.debug` | `applicationIdSuffix` |
| launcher Activity | `com.example.dpulayerlab.MainActivity` | main manifest |
| automation alias | `com.example.dpulayerlab.AutomationActivity` | manifest + Intent contract |
| control permission | `${applicationId}.permission.CONTROL_TESTS` | main manifest |
| vendor bind action | `com.example.dpulayerlab.VENDOR_TELEMETRY` | manifest + `VendorBridge.kt` |
| vendor access permission | `com.example.dpulayerlab.permission.ACCESS_VENDOR_TELEMETRY` | manifest/product |
| FileProvider authority | `${applicationId}.files` | main manifest |
| report prefix | `dpu-layer-lab-` | `ReportWriter.kt` |
| report schema | `schemaVersion: 2` | `ReportWriter.kt` |
| Soong module/APK | `DpuLayerLab` | `system_integration/product/Android.bp` |
| project/launcher label | `DPULayerTest` | settings/resource |

Version name과 versionCode는 변동값이다. Machine authority는 `app/build.gradle.kts`,
공개 tag·asset authority는 [Release](RELEASE.md)다.

## Automation Intent

### Component와 resolution

외부 caller는 대상 variant의 explicit component를 지정한다.

```text
release: com.example.dpulayerlab/com.example.dpulayerlab.AutomationActivity
debug:   com.example.dpulayerlab.debug/com.example.dpulayerlab.AutomationActivity
```

- release alias는 `signature|privileged` `CONTROL_TESTS` permission으로 보호된다.
- debug overlay는 lab automation을 위해 alias permission을 제거한다.
- alias filter에는 `CATEGORY_DEFAULT`가 없으므로 일반 implicit resolution 대상이 아니다.
- direct `MainActivity`의 START는 automation command로 처리하지 않는다.
- STOP은 START extra unmarshalling보다 먼저 처리한다.

### Action

| Action | Extra | 의미 |
|---|---|---|
| `com.example.dpulayerlab.action.SHOW` | 없음 | UI만 foreground |
| `com.example.dpulayerlab.action.START` | 아래 표 | catalog plan 시작 |
| `com.example.dpulayerlab.action.STOP` | 읽지 않음 | 실행/대기 START 중단 |

### START extra

| Key | Type | 계약 |
|---|---|---|
| `scenario_id` | string | catalog preset 하나 |
| `scenario_ids` | comma string, string array 또는 string list | 순서·중복 보존 plan |
| `repeat_count` | Byte/Short/Int, Int 범위 Long 또는 정수 CharSequence | 생략 시 1, 최대 10 |

`scenario_id`와 `scenario_ids`는 동시에 보낼 수 없다. Custom scenario, unknown preset,
빈 ID, 총 40회를 넘는 expanded plan과 실행 중 START는 거부한다.

호출 예, command ordering과 parser 오류 의미는 [Automation guide](AUTOMATION.md)를
사용한다. Shell quoting은 platform에 따라 달라질 수 있으므로 이 wire contract와 예시
command를 혼동하지 않는다.

## Report와 FileProvider

- Report는 internal `files/reports` 아래에만 쓴다.
- 임시 `.part`를 fsync한 뒤 rename하여 completed JSON을 발행한다.
- 공유 대상은 canonical internal directory 안에 실제 존재하는
  `dpu-layer-lab-…json` completed file이어야 한다.
- 해당 경로는 현재 controller의 `lastReportFile` 또는 현 plan result history에 publish된
  report와 canonical-equal해야 한다.
- traversal, foreign JSON, missing file과 `.part` 공유를 거부한다.
- 최신 400개 managed completed report만 best-effort 보존한다.
- app data는 cloud backup, device transfer와 legacy backup에서 전부 제외한다.

Schema v2의 field·type·nullability는 [Report schema](REPORT_SCHEMA.md), 값의 물리적
의미와 quality는 [Metrics](METRICS.md)가 authority다. Schema를 바꿀 때는 기존 consumer
migration 또는 새 schema version을 명시한다.

## Vendor AIDL version

App-side source:
`app/src/main/aidl/com/example/dpulayerlab/vendor/IDpuLabVendorService.aidl`

| API | 추가 기능 | 호환 규칙 |
|---|---|---|
| v1 | underrun, DPU/bus, DEVICE/CLIENT, SBWC, NPU | 기존 transaction 순서 유지 |
| v2 | GPU busy/frequency, DPU frequency | method를 v1 뒤에 append |
| v3 | Battery Saver performance lease | method를 v2 뒤에 append |

Provider가 vendor partition에 있으면 제품은 mirrored stable AIDL/VINTF contract를
사용해야 한다. Platform signing만으로 Binder service, sysfs 또는 debugfs 접근이
생긴다고 가정하지 않는다.

### Broker identity와 permission

Portable APK는 action에 응답한 임의 service를 자동 신뢰하지 않는다. Product는
`/product/etc/dpulayerlab/vendor_broker.conf`에 다음 exact trust contract를 설치한다.

- fully-qualified service package/class
- permission owner package
- permission owner signing-lineage SHA-256 allowlist
- service signing-lineage SHA-256 allowlist

App은 `ACCESS_VENDOR_TELEMETRY`가 signature-base permission인지, 자신에게 실제
grant됐는지, permission owner와 service signer가 설정된 lineage인지 확인한다. Service는
system/updated-system app, enabled/exported, exact access permission이어야 한다. 어느
하나라도 맞지 않으면 typed permanent `UNAVAILABLE`이며 implicit discovery나 반복
reconnect로 낮추지 않는다. 자세한 파일 형식과 rotation/multi-signer 규칙은
[System integration](SYSTEM_INTEGRATION.md)이 소유한다.

### 값 계약

- unavailable count/frequency는 음수 또는 contract가 정한 unavailable로 반환한다.
- utilization은 유한한 0~100 범위만 valid하다.
- DEVICE/CLIENT는 같은 transaction의 완전한 원자 쌍이어야 한다.
- status string은 app에서 256자로 제한하고 control/format 문자를 정규화한다.
- NPU/SBWC command는 enqueue가 아니라 matching acknowledgment 뒤에 성공이다.
- Binder snapshot timeout은 registration disconnect와 같은 의미가 아니다.

### API v3 lease

- 앱은 Battery Saver suppression만 요청한다.
- session ID와 command version은 단조 증가하며 latest command가 authoritative하다.
- provider는 client death, lease expiry와 END에서 원래 policy를 복원한다.
- thermal protection, DPU/GPU/DDR frequency lock과 governor override는 계약 밖이다.

상세 provider 요구사항은 [System integration](SYSTEM_INTEGRATION.md)을 따른다.

## Kernel/product probe configuration

Portable app은 임의 path를 탐색하지 않는다. 제품이 read-only allowlist를 제공할 때만
다음 typed key를 사용할 수 있다.

- GPU busy/frequency의 명시적 unit/format key
- `bus_busy`
- `dpu_busy`
- `dpu_frequency_hz`
- `dpu_underrun`

Generic DRM underrun node는 display scope와 ABI가 제품별로 모호하므로 automatic exact
source가 아니다. Exact 승격은 제품이 검증한 typed contract와 allowlist를 요구한다.

`dpu_frequency_hz`는 read-only counter다. 앱에 frequency/governor write path를
추가하지 않는다. Xclipse, Qualcomm, MediaTek과 legacy Mali의 정확한 source selection은
[Metrics](METRICS.md), path/SELinux 설정은 [System integration](SYSTEM_INTEGRATION.md)이
소유한다.

Custom probe path는 key별 `/sys` namespace allowlist와 canonical regular/readable
attribute 검사를 모두 통과해야 한다. `/proc`, traversal, control/whitespace가 있는
path는 wire input으로도 허용하지 않는다.

## Build variant와 signing

| Variant | 설치/서명 의미 | Automation |
|---|---|---|
| debug | Android debug key로 설치 가능한 lab build | alias permission 제거 |
| Gradle release | unsigned product signing 입력 | protected alias 유지 |
| product APK | secure environment에서 platform/product key 서명 | privapp/SELinux/provider 함께 검증 |

Platform private key, certificate, keystore, token과 password는 저장소·GitHub Release에
넣지 않는다. 공개 asset allowlist와 checksum은 [Release](RELEASE.md)가 소유한다.

## Compatibility migration checklist

Stable identifier 변경이 불가피할 때 한 commit에서 최소한 다음을 처리한다.

1. old/new identifier와 지원 기간 결정
2. manifest, Gradle, AIDL, Soong, allowlist, FileProvider와 source 상수 변경
3. external harness와 report consumer migration
4. debug/release alias security test
5. implicit/direct-main 거부와 malformed payload test
6. schema 또는 file prefix migration/retention test
7. `SYSTEM_INTEGRATION.md`, `RELEASE.md`, `RECONSTRUCTION.md` 갱신
8. old product image와 new app 조합의 fail-closed 동작 검증

단순 코드 검색·치환으로 compatibility identifier를 일괄 rename하지 않는다.
