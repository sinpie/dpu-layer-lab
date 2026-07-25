# DPULayerTest 작업 계획

> **Authority:** 현재 작업의 범위, 우선순위, 상태와 완료 조건을 관리하는 유일한 작업 원장
> **Audience:** 프로젝트 owner, maintainer, coding agent, reviewer
> **Update when:** 작업이 추가·분할·완료·차단되거나 acceptance criteria가 바뀔 때
> **Does not own:** 안전 불변식, 현재 구현 구조, 계측 의미, 릴리스 절차, 과거 설계 결정
> **Related:** [Documentation index](docs/INDEX.md), [Requirements](docs/REQUIREMENTS.md),
> [AGENTS.md](AGENTS.md), [ARCHITECTURE.md](ARCHITECTURE.md),
> [PROJECT_MEMORY.md](PROJECT_MEMORY.md), [docs/TESTING.md](docs/TESTING.md),
> [docs/HWC_CAPACITY_CALIBRATION.md](docs/HWC_CAPACITY_CALIBRATION.md),
> [docs/RELEASE.md](docs/RELEASE.md)

이 문서는 앞으로 할 일을 기록한다. 구현된 사실은
[ARCHITECTURE.md](ARCHITECTURE.md), 장기간 유지할 결정과 이유는
[PROJECT_MEMORY.md](PROJECT_MEMORY.md), 검증 방법은
[docs/TESTING.md](docs/TESTING.md)가 authority다. 이 계획은
[AGENTS.md](AGENTS.md)의 안전 계약을 덮어쓰지 않는다.

## 상태 값

| 상태 | 의미 |
|---|---|
| `PROPOSED` | 범위와 완료 조건을 검토하기 전 |
| `READY` | 의존성과 acceptance criteria가 명확함 |
| `IN_PROGRESS` | shared tree에서 구현 또는 검증 중 |
| `BLOCKED` | 외부 결정이나 환경이 없으면 진행할 수 없음 |
| `DONE` | 코드·테스트·문서 gate를 모두 충족함 |

`DONE` 항목은 릴리스 뒤 이 문서에서 제거한다. 장기적으로 중요한 결과만
`PROJECT_MEMORY.md`에 남기고, 일회성 작업 로그를 누적하지 않는다.

## 현재 목표

테스트 담당자가 목적 중심 UI에서 DPU 저부하→고부하, DEVICE 후보 유지,
DEVICE→CLIENT 전환, layer 크기 변화와 교차 자원 경합을 선택하고 반복 실행하며,
정확한 provenance가 포함된 HUD와 결과를 해석할 수 있게 한다.

현재 변경은 다음 원칙을 유지해야 한다.

- HWC 경로를 topology만으로 보장하지 않는다. `DEVICE_ONLY`와
  `CLIENT_REQUIRED`는 fresh DEVICE/CLIENT evidence를 요구하는 관측 계약이다.
- layer 표시 면적을 줄여도 physical producer 수, buffer allocation과 graphics
  safety budget을 축소했다고 가정하지 않는다.
- HWC capacity candidate는 process session에서 한 번만 시도하고 terminal 결과를
  재사용해 scenario별 또는 START별 calibration burst를 만들지 않는다.
- exact DPU underrun counter가 없으면 proxy를 exact 결과로 승격하지 않는다.
- 테스트 종료·중단·오류에서 producer, load worker, NPU/SBWC 상태, Battery Saver,
  display request와 system bar 상태 복구를 확인한다.

## Now

### P-001 Layer 표시 크기 profile

- **상태:** `DONE`
- **완료 근거:** model→safety→controller→renderer→traffic/HUD→report/test를
  연결했고, geometry ACK/recovery/teardown 경쟁 조건 회귀 테스트와 전체 host gate를
  통과했다.
- **목표:** layer 수·FPS·backend와 독립적으로 destination footprint를 제어한다.
- **계약:**
  - `FULL_SCREEN`: 기존 phase와 custom test의 기본값
  - `SMALL_UNIFORM`: 모든 physical child를 작은 동일 크기로 표시
  - `MIXED_SIZES`: small/medium/large footprint를 결정적으로 혼합
  - `GRADUAL_SMALL_TO_FULL`: phase 진행률에 따라 작은 크기에서 full로 점진 확대
  - `ABRUPT_SMALL_FULL`: phase 안에서 small/full을 bounded 횟수로 급변
- **영향 코드:**
  - `app/src/main/java/com/example/dpulayerlab/model/LabModels.kt`
  - `app/src/main/java/com/example/dpulayerlab/render/LayerStageView.kt`
  - `app/src/main/java/com/example/dpulayerlab/engine/ScenarioCatalog.kt`
  - `app/src/main/java/com/example/dpulayerlab/model/ScenarioClassifier.kt`
  - `app/src/main/java/com/example/dpulayerlab/ui/DpuLayerLabApp.kt`
- **Acceptance:**
  - 기존 phase와 custom builder는 명시하지 않으면 `FULL_SCREEN`이다.
  - transform은 source buffer 재할당 없이 child의 destination footprint에 적용한다.
  - 크기 profile은 topology identity나 physical producer count를 거짓으로 바꾸지 않는다.
  - non-finite progress, 잘못된 index/count는 보수적으로 full-screen을 반환한다.
  - frame hot path에서 layer마다 반복 객체를 할당하지 않는다.
  - 크기 profile별 evaluator, renderer geometry와 UI 요약 boundary test가 있다.
  - HUD는 base size-profile footprint의 screen-equivalent 합과 producer당 평균 면적을
    별도로 표시하고 MotionProfile scale/overlap/crop을 제외하며, conservative
    full-buffer traffic과 measured bus를 줄이지 않는다.

### P-002 크기와 DPU burst 시나리오 확장

- **상태:** `DONE`
- **완료 근거:** 7개 size 조합 preset과 classifier/facet/test를 추가하고 전체 host
  gate를 통과했다.
- **목표:** 표시 면적, layer 수, FPS/Hz와 HWC expectation을 조합한 catalog preset을
  추가한다.
- **예정 preset 계약:**
  - 작은 layer density sweep
  - small/mixed/full A/B matrix
  - 점진 small→full 확대
  - 급격 small↔full toggle
  - size + layer + FPS STEP burst
  - 보수적 4L DEVICE candidate
  - 20L mixed/alpha/GL CLIENT pressure
- **Acceptance:**
  - 모든 preset ID가 유일하고 `ScenarioCatalog.presets` 순서가 결정적이다.
  - `ScenarioSafetyPolicy` clamp가 typed HWC 계약을 바꾸면 축소 실행하지 않고 거부한다.
  - typed HWC phase와 dynamic size profile 조합은 fresh target geometry를 흐리므로
    거부한다.
  - `DEVICE_ONLY` phase는 12초 이상, `CLIENT_REQUIRED` phase는 16초 이상이며 각각
    필요한 fresh evidence 수와 post-target tick을 확보한다.
  - media·vendor 요구가 있는 phase는 proxy fallback 없이 fail-closed한다.
  - classifier facet과 catalog UI에 크기 조건이 노출된다.

### P-003 목적 중심 시나리오 UI

- **상태:** `DONE`
- **완료 근거:** 목적 card, queue/profile 요약, custom size 선택과 실행 HUD를 연결하고
  UI/model boundary test 및 전체 host gate를 통과했다.
- **목표:** 사용자가 “무엇을 검증할지”를 먼저 선택하고, 선택된 입력 변화·합성 목표·
  확인할 metric을 실행 전에 이해하게 한다.
- **Acceptance:**
  - 빠른 목적: `급격한 DPU 부하`, `DEVICE 후보 유지`, `CLIENT 전환 목표`
  - 고급 facet은 같은 행 OR, 서로 다른 행 AND 의미를 유지한다.
  - filtered append/replace는 catalog 순서와 40-run cap을 지킨다.
  - queue의 중복, 순서, 명시적 이동과 repeat 수를 그대로 미리 보여 준다.
  - scenario card, queue, running HUD에 layer size profile을 일관되게 표시한다.
  - UI의 `RAW MATCH/WAIT/N/A`는 controller 최종 verdict처럼 보이지 않는다.

### P-004 canonical 문서 분리

- **상태:** `DONE`
- **완료 근거:** 신규·기존 문서의 authority를 분리하고 링크·제한된 inline path·
  code-fence·버전·preset 정합 검사와 `git diff --check`를 통과했다. 전체 host gate는
  42개 suite/629개 test(실패·오류·skip 0), lint error 0,
  `assembleDebug` 성공으로 확인했다.
- **목표:** 사용법·안전·구조·시나리오·계측·검증·릴리스·복구의 authority를 분리한다.
- **산출물:**
  - `docs/INDEX.md`
  - `docs/REQUIREMENTS.md`
  - `docs/REPOSITORY_MAP.md`
  - `docs/STATE_MACHINES.md`
  - `docs/EXTERNAL_CONTRACTS.md`
  - `docs/AUTOMATION.md`
  - `docs/HWC_CAPACITY_CALIBRATION.md`
  - `docs/REPORT_SCHEMA.md`
  - `docs/UI_SPEC.md`
  - `docs/TROUBLESHOOTING.md`
  - `PLAN.md`
  - `ARCHITECTURE.md`
  - `docs/SCENARIOS.md`
  - `docs/METRICS.md`
  - `docs/TESTING.md`
  - `docs/RELEASE.md`
  - `docs/RECONSTRUCTION.md`
- **Acceptance:**
  - 각 문서에 authority metadata가 있다.
  - README와 기존 canonical 문서는 상세 내용을 복제하지 않고 새 authority로 연결한다.
  - 모든 상대 링크와 언급한 저장소 경로가 존재한다.
  - `git diff --check`가 통과한다.

### P-005 Process-session HWC capacity one-shot

- **상태:** `DONE`
- **현재 근거:** process-local session store, controller deadline, vendor-prefetch/SF
  fallback 분기와 관련 boundary test가 구현됐고 전체 host gate가 통과했다.
- **목표:** 첫 START의 첫 scenario 전에만 bounded capacity candidate를 계측하고 같은 앱
  process의 모든 후속 실행에서 terminal 결과를 재사용한다.
- **계약:**
  - requested topology는 20L/30fps/60Hz independent opaque RGB DISPLAY
    `CAPACITY_TILES`
  - safety/graphics budget이 actual candidate를 줄일 수 있으며 requested/actual을 구분
  - topology readiness, 100ms stabilize와 single sample을 absolute 6000ms
    producer-active deadline으로 제한
  - success뿐 아니라 timeout/cancel/failure도 terminal N/A이며 같은 process에서 재시도
    burst 금지
  - scenario/repeat/후속 START와 Activity 재생성에서 in-memory reuse, disk persistence
    금지
  - orientation-only 축 교환은 reuse, display ID/normalized-size 변경은 N/A projection 후
    process restart 요구
  - vendor snapshot 한 번의 current-session atomic pair가 있으면 SF 생략, 없으면 SF
    fallback 한 번
  - optional vendor v2는 생략하고 v1 실패 뒤 actual worker quiescence가 확인된 경우에만
    SF fallback 시작; 미확인은 probe overlap 없이 N/A
  - periodic priority 획득 뒤 local/SF/vendor lane을 pre-drain하고 teardown 뒤 실제
    completion을 다시 확인하며 미확인은 process-sticky failure
  - sample 전후 topology/geometry revision, discontinuity serial과 fresh heartbeat 유지
  - watchdog pause/resume grace는 success timestamp와 분리하고 direct safety recheck를
    producer readiness cadence에 유지
  - load zero와 renderer teardown은 모든 terminal path에서 확인하고, cleanup-confirmed
    경로의 3초 settle은 producer deadline 밖에서 수행
  - final producer teardown 뒤 calibration frame/generated-traffic counter를 drain해
    첫 scenario evidence와 분리
  - result는 advisory-only이며 safety cap/catalog target/typed phase evidence가 아님
- **Acceptance:**
  - `HwcCapacityCalibrationSessionTest`, `LabControllerMathTest`,
    `SystemMonitorMathTest`의 one-shot/deadline/source/display boundary가 통과한다.
  - report event가 `SESSION_HWC_CAPACITY_CALIBRATION`과
    `SESSION_HWC_CAPACITY_REUSE_GUIDANCE`다.
  - 전체 host gate와 Markdown 검사가 통과한다.

## Next

### P-006 수동 device validation matrix

- **상태:** `BLOCKED`
- **차단 조건:** 대상 실험기, BSP probe/provider 구성, 허용된 시나리오 범위가 지정되지 않음
- **필요 입력:**
  - SoC/GPU와 Android build fingerprint
  - 지원 refresh mode와 physical display size
  - vendor API version 및 exact underrun source
  - DEVICE/CLIENT atomic pair 지원 여부
  - 4K/8K/P010 검증 media
- 연결된 실기기에서 stress scenario를 자동 실행하지 않는다.

## Later

- 제품별 HWC capacity advisory 결과를 scenario 결과와 비교하는 분석 도구
- schema v2 report의 offline 비교·회귀 요약 도구
- vendor provider reference implementation은 별도 BSP repository에서 관리

이 항목들은 새 권한, telemetry 계약 또는 privacy 범위를 자동으로 허가하지 않는다.

## 검증 gate

코드 변경이 포함된 작업의 완료 gate는 다음과 같다.

1. 관련 unit/boundary test
2. `testDebugUnitTest`
3. `lintDebug`
4. `assembleDebug`
5. release 요청이 있을 때만 `assembleRelease`
6. renderer/load 변경의 teardown·memory ownership 검토
7. Markdown 링크·경로 검사와 `git diff --check`
8. 사용자-visible 의미가 바뀌면 README와 도메인 문서 갱신

정확한 명령과 산출물은 [docs/TESTING.md](docs/TESTING.md), publish 절차는
[docs/RELEASE.md](docs/RELEASE.md)를 따른다.

이번 완료 상태의 host evidence는 42개 suite, 629개 unit test
(`failures=0`, `errors=0`, `skipped=0`), `lintDebug`, `assembleDebug` 성공이다.
이번 요청에는 release build가 포함되지 않아 `assembleRelease`는 다시 실행하지 않았다.
연결된 실기기 stress validation은 P-006의 입력이 없어 실행하지 않았다.
