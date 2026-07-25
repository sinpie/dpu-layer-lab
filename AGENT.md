# Agent guide

이 파일은 단수 이름을 요구하는 도구용 compatibility entry point입니다.
Canonical 작업 규칙과 안전 불변식은 [AGENTS.md](AGENTS.md)에 있습니다.

코드를 수정하기 전에 다음 순서로 읽으세요.

1. [AGENTS.md](AGENTS.md) — 금지선, safety cap, 계측과 완료 정의
2. [ARCHITECTURE.md](ARCHITECTURE.md) — component, 상태와 resource ownership
3. [Repository reconstruction](docs/RECONSTRUCTION.md) — 유실·손상 시 복구 순서
4. 변경 도메인의 canonical 문서 — [scenario](docs/SCENARIOS.md),
   [metric](docs/METRICS.md), [testing](docs/TESTING.md),
   [system integration](docs/SYSTEM_INTEGRATION.md)

연결된 실기기에서 stress scenario를 자동 실행하지 마세요. 사용자가 대상 실험기와
실행 범위를 명시해야 합니다.
