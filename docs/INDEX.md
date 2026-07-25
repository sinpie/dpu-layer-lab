# Documentation index와 유지 규칙

> **Authority:** 문서별 책임, 읽기 순서, 중복 방지와 변경 시 갱신 범위
> **Audience:** 시험자, maintainer, coding agent, reviewer, BSP·release 담당자
> **Update when:** 문서가 추가·삭제·분할되거나 authority와 필수 읽기 순서가 바뀔 때
> **Does not own:** 앱 동작, safety 수치, metric 의미, release asset 자체
> **Related:** [README.md](../README.md), [AGENTS.md](../AGENTS.md),
> [ARCHITECTURE.md](../ARCHITECTURE.md), [PROJECT_MEMORY.md](../PROJECT_MEMORY.md),
> [RECONSTRUCTION.md](RECONSTRUCTION.md)

이 인덱스는 DPULayerTest 문서의 진입점이다. 같은 사실이 여러 문서에 필요하면 한 문서만
규범적 authority로 정하고 나머지는 링크와 짧은 요약만 둔다. Source와 test가 문서와
충돌하면 실행 가능한 현재 사실은 source/test를, 안전 금지선은
[AGENTS.md](../AGENTS.md)를 우선하고 충돌을 같은 변경에서 정리한다.

## 역할별 읽기 순서

### 시험자

1. [README.md](../README.md)
2. [Scenario 계약](SCENARIOS.md)
3. [Metric 계약](METRICS.md)
4. [Troubleshooting](TROUBLESHOOTING.md)

### 앱 개발자와 reviewer

1. [README.md](../README.md)
2. [Project memory](../PROJECT_MEMORY.md)
3. [AGENTS.md](../AGENTS.md)
4. [Requirements](REQUIREMENTS.md)
5. [Architecture](../ARCHITECTURE.md)
6. [State machines](STATE_MACHINES.md)
7. [Repository map](REPOSITORY_MAP.md)
8. 변경 도메인의 scenario/metric/testing 문서

### source 재생성 또는 손상 복구

1. [README.md](../README.md)
2. [Project memory](../PROJECT_MEMORY.md)
3. [AGENTS.md](../AGENTS.md)
4. [Requirements](REQUIREMENTS.md)
5. [Repository map](REPOSITORY_MAP.md)
6. [Architecture](../ARCHITECTURE.md)
7. [State machines](STATE_MACHINES.md)
8. [External contracts](EXTERNAL_CONTRACTS.md)
9. [HWC capacity calibration](HWC_CAPACITY_CALIBRATION.md)
10. [Report schema](REPORT_SCHEMA.md)
11. [Reconstruction guide](RECONSTRUCTION.md)
12. [Testing](TESTING.md)

### BSP·system image 통합

1. [External contracts](EXTERNAL_CONTRACTS.md)
2. [System/BSP integration](SYSTEM_INTEGRATION.md)
3. [Metric 계약](METRICS.md)
4. [Testing](TESTING.md)

### Automation·report consumer

1. [External contracts](EXTERNAL_CONTRACTS.md)
2. [Automation guide](AUTOMATION.md)
3. [Scenario 계약](SCENARIOS.md)
4. [Report schema](REPORT_SCHEMA.md)
5. [Metric 계약](METRICS.md)
6. [Testing](TESTING.md)

### release 담당자

1. [Release](RELEASE.md)
2. [Testing](TESTING.md)
3. [External contracts](EXTERNAL_CONTRACTS.md)

## 문서 authority 표

| 문서 | 단일 책임 |
|---|---|
| [INDEX.md](INDEX.md) | 문서별 authority, 읽기 순서와 변경 시 갱신 범위 |
| [README.md](../README.md) | 시험자 관점의 기능, UI, 빠른 시작, 제한 |
| [AGENT.md](../AGENT.md) | 단수 파일명을 요구하는 도구용 진입점 |
| [AGENTS.md](../AGENTS.md) | 수정 규칙, 안전 불변식, 금지사항, 완료 정의 |
| [PLAN.md](../PLAN.md) | 현재·다음 작업, 상태, acceptance criteria |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | 현재 component/data flow/resource ownership |
| [PROJECT_MEMORY.md](../PROJECT_MEMORY.md) | 장기 결정, 선택 이유, 유지할 맥락 |
| [REQUIREMENTS.md](REQUIREMENTS.md) | 사용자 목적과 구현·검증 간 traceability |
| [REPOSITORY_MAP.md](REPOSITORY_MAP.md) | 파일·package 책임과 변경 영향 |
| [STATE_MACHINES.md](STATE_MACHINES.md) | plan/producer/telemetry/cleanup 상태 전이 |
| [EXTERNAL_CONTRACTS.md](EXTERNAL_CONTRACTS.md) | stable identifier, wire version과 migration 경계 |
| [AUTOMATION.md](AUTOMATION.md) | Intent 호출 예, ordering, 입력 cap과 오류 의미 |
| [HWC_CAPACITY_CALIBRATION.md](HWC_CAPACITY_CALIBRATION.md) | process-session 최초 1회 20L candidate 관측 |
| [SCENARIOS.md](SCENARIOS.md) | scenario/phase/catalog/facet/transition 의미 |
| [METRICS.md](METRICS.md) | metric source/quality, exact/proxy, verdict |
| [REPORT_SCHEMA.md](REPORT_SCHEMA.md) | schema v2 JSON field, type, nullability와 consumer 규칙 |
| [UI_SPEC.md](UI_SPEC.md) | 화면 정보 구조, 실행 HUD와 상태별 표시 |
| [TESTING.md](TESTING.md) | host/device gate와 invariant-to-test 지도 |
| [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md) | product/BSP 배치, permission, SELinux, provider |
| [RELEASE.md](RELEASE.md) | version, build artifact, signing, publish |
| [RECONSTRUCTION.md](RECONSTRUCTION.md) | dependency 순서의 source 복구 절차 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 증상에서 원인·안전한 조치로 가는 운영 진단 |

## 변경 유형별 필수 갱신

| 변경 | 최소 동반 문서 |
|---|---|
| safety cap, abort, cleanup | `AGENTS.md`, `PROJECT_MEMORY.md`, `TESTING.md` |
| package/component/action/extra | `EXTERNAL_CONTRACTS.md`, `AUTOMATION.md`, `SYSTEM_INTEGRATION.md`, 관련 test |
| scenario ID, phase, facet | `SCENARIOS.md`, `REQUIREMENTS.md`, `TESTING.md` |
| metric/source/quality/verdict | `METRICS.md`, `REPORT_SCHEMA.md`, `TESTING.md` |
| package 책임, state, ownership | `ARCHITECTURE.md`, `STATE_MACHINES.md`, `REPOSITORY_MAP.md` |
| HWC session calibration | `HWC_CAPACITY_CALIBRATION.md`, `STATE_MACHINES.md`, `TESTING.md` |
| navigation/HUD/result UX | `UI_SPEC.md`, `REQUIREMENTS.md`, `README.md` |
| report field/type/publication | `REPORT_SCHEMA.md`, `EXTERNAL_CONTRACTS.md`, `TESTING.md` |
| build toolchain/variant | `TESTING.md`, `RELEASE.md`, `RECONSTRUCTION.md` |
| version/tag/asset | `RELEASE.md`와 공개 release를 인용하는 root 문서 |
| BSP AIDL/probe/permission | `EXTERNAL_CONTRACTS.md`, `SYSTEM_INTEGRATION.md` |
| 복구 dependency/checkpoint | `RECONSTRUCTION.md`, `REPOSITORY_MAP.md` |

## 중복 방지 규칙

- Safety 수치와 fail-closed 규칙의 원문은 `AGENTS.md`에만 둔다.
- “왜 이 선택을 했는가”는 `PROJECT_MEMORY.md`에만 장기 결정으로 남긴다.
- 현재 코드 구조는 `ARCHITECTURE.md`, 파일 위치는 `REPOSITORY_MAP.md`가 소유한다.
- 허용 상태 전이와 cleanup ordering은 `STATE_MACHINES.md`가 소유한다.
- Scenario와 metric 표는 각각 `SCENARIOS.md`, `METRICS.md`가 소유한다.
- Intent wire identifier는 `EXTERNAL_CONTRACTS.md`, 사용·ordering은 `AUTOMATION.md`가
  소유한다.
- JSON field와 nullability는 `REPORT_SCHEMA.md`가 소유한다.
- 공개 release checksum은 `RELEASE.md`만 소유한다.
- `PLAN.md`의 미래 의도를 현재 구현 사실처럼 다른 문서에 복사하지 않는다.
- 변동 가능한 test 수는 완료 시점 evidence에만 기록하고 suite 설명에는 고정하지 않는다.
- 임시 로컬 경로, SDK 위치, device serial, report 본문과 signing material을 문서에 넣지
  않는다.

## 문서 자체 검증

문서 변경 뒤 최소한 다음을 확인한다.

```powershell
git diff --check
$trackedDocs = git ls-files "*.md" | Where-Object { $_ -ne "docs/INDEX.md" }
git grep -n "sinpie/DPULayerTest" -- $trackedDocs
git grep -n "file://\\|C:\\\\Users\\\\\\|D:\\\\Project\\\\" -- $trackedDocs
```

상대 Markdown 링크는 각 문서가 있는 directory를 기준으로 실제 경로가 존재하는지
검사한다. 코드 symbol을 문서에 적었다면 `git grep`으로 현재 symbol과 일치하는지도
확인한다.
