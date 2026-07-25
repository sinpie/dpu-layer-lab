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

Release별 고정 host evidence, APK 검증과 checksum은
[docs/RELEASE.md](docs/RELEASE.md)만 authority로 사용한다. 연결된 실기기 stress
validation은 P-006의 입력이 있기 전까지 자동 실행하지 않는다.
