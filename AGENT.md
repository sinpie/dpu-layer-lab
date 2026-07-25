# Agent guide

이 파일은 단수 이름을 요구하는 도구용 compatibility entry point입니다.
Canonical 작업 규칙과 안전 불변식은 [AGENTS.md](AGENTS.md)에 있습니다.

코드를 수정하기 전에 다음 순서로 읽으세요.

1. [README.md](README.md) — 사용자 목적과 현재 제품 동작
2. [PROJECT_MEMORY.md](PROJECT_MEMORY.md) — 장기 결정과 이유
3. [AGENTS.md](AGENTS.md) — 금지선, safety cap, 계측과 완료 정의
4. [Documentation index](docs/INDEX.md) — 역할별 읽기 순서와 문서 authority
5. [Requirements](docs/REQUIREMENTS.md), [ARCHITECTURE.md](ARCHITECTURE.md),
   [Repository map](docs/REPOSITORY_MAP.md), [State machines](docs/STATE_MACHINES.md)
6. [Repository reconstruction](docs/RECONSTRUCTION.md) — 유실·손상 시 복구 순서
7. 변경 도메인의 canonical 문서 — [scenario](docs/SCENARIOS.md),
   [metric](docs/METRICS.md), [testing](docs/TESTING.md),
   [system integration](docs/SYSTEM_INTEGRATION.md)
8. 현재 작업은 [PLAN.md](PLAN.md), publish 작업은 [Release](docs/RELEASE.md)

연결된 실기기에서 stress scenario를 자동 실행하지 마세요. 사용자가 대상 실험기와
실행 범위를 명시해야 합니다.
