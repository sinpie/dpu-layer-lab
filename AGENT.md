# Agent guide

이 파일은 도구가 단수 이름을 기대할 때 사용하는 진입점입니다. 이 저장소의 canonical
작업 규칙, 안전 상한, 검증 절차는 [AGENTS.md](AGENTS.md)에 있습니다.

코드나 시나리오를 변경하기 전에 `AGENTS.md` 전체를 읽고 따르세요. 특히 실제 기기에서
stress test를 자동 실행하지 말고, layer/FPS/graphics-memory 상한, local-worker failure
latch, terminal counter continuity와 media fingerprint fail-closed 규칙을 우회하지
마세요. Media preflight의 pinned seekable AFD/process-wide refcount lease,
선택 media 없는 YUV/P010/SBWC decoder의 proxy 없는 거부와 reachable transition FPS,
absolute-deadline 100 ms transition/runtime coverage와 measured STEP,
`0 < workload <= 0.001` 거부, 실제 GPU producer, GL color+depth budget, aggregate physical
producer-rate fidelity/topology-pending 즉시 pause, 실행 중 Battery Saver envelope
및 display-envelope 무효화, NPU positive ticket/ack+health, thermal ordered
zero→reduced workload ack→display ack, managed internal report만 공유, 전체 backup
제외와 Adaptive Hunt `STEADY` plateau도 `AGENTS.md`의 안전 불변식입니다. HUD는
`20260725_090252`/debug suffix와 gauge provenance gap을 유지하고 View/client Z-order를
physical HWC 증거로 표현하지 않습니다. 또한 lifecycle close에서는 compression reset을
하지 않고, active SBWC route를 명령 acknowledgment의 vendor service session에
결속하며, remote snapshot timeout과 실제 registration 단절을 구분해야 합니다.
Test producer는 tokenized immersive Window의 status/navigation-bar hidden 확인 뒤에만
시작하고 재등장/focus loss를 fail-closed 처리해야 합니다. 종료도 원래 bar visibility의
Insets acknowledgment 전에는 token/process lease를 해제하지 않습니다. 재생성된
Activity의 IDLE Window도 이전 process lease 해제 전에는 SystemUI hide를 유지합니다.
Foreign hide 미확인은 bounded retry 뒤 원래 lease owner에 fail-closed로 전달합니다.
KGSL window counter,
Exynos Xclipse AMD-RDNA DRM direct-percent와 legacy Mali/typed MediaTek GPU probe,
vendor API v2 unit/provenance도 임의 추측이나 fallback 없이 유지합니다. Optional v2
Binder 호출은 v1/exact-counter lane과 분리한 bounded no-backlog lane을 사용하고,
같은 service session의 v1 snapshot은 개별 v2 실패 때문에 폐기하지 않습니다.
테스트 중 성능 정책 변경은 API v3 typed broker의 Battery Saver 임시 해제로만
제한합니다. BEGIN 전 original state를 safety cap과 exact restore authority로 보존하고,
10초 lease/2초 renew, death/expiry/END 복구와 system-wide overlapping-client
arbitration을 유지하세요. Platform signing을 power-policy 권한으로 간주하지 않으며
선택형 앱 선제 thermal SEVERE 감속은 기본 OFF/plan-start snapshot 계약을 유지하고,
thermal CRITICAL·low-memory·local-worker·power/display 격리 fail-safe,
Doze/device-idle 거부와 read-only DVFS/frequency 계약은 우회하지 않습니다.
순간 부하는 prewarm·재사용 buffer·latest-wins 경로로 만들고,
Activity보다 오래 사는 receiver/job/thread는 application context 또는 Activity-free
callback과 terminal cleanup 증거를 가져야 합니다. 함수 단위 state/owner 경계 검증 뒤
partial start·cancel·Activity 재생성·provider death/expiry를 포함한 전체 흐름을
검증합니다. Renderer topology는 transactional publish/rollback을 유지하고,
Canvas/EGL native call을 가로지른 completion token도 relay teardown에서 controller
callback을 분리해야 합니다. Plan-wide Battery Saver restore 실패는 앞서 발행한 결과와
report path까지 무효화합니다. Telemetry monitor/watchdog 중 한쪽의 unexpected exit와
performance renewal/session integrity 실패도 exact cleanup 성공만으로 같은 process에서
재사용하지 않습니다. Typed HWC phase는 topology를 강제한다는 뜻이 아닙니다. 동일
provenance의 fresh DEVICE/CLIENT pair, target-ready 이후의 bounded probe와 phase 간
방향성 계약을 유지하고, UI의 `RAW MATCH`를 최종 검증 결과로 승격하지 마세요.
START plan의 첫 scenario 전에는 전체 queue/repeat가 공유하는 safety-approved 최대 20L
opaque RGB tile HWC 관측을 한 번만 수행합니다. 모든 producer readiness→100ms
stabilization→fresh 원자 쌍 1회→zero/teardown→3초 settle 순서를 지키고, 결과를 보편
maximum이나 safety cap으로 승격하지 마세요. Active phase에서는 typed/untyped 모두
SurfaceFlinger child와 calibration cache를 사용하지 않고 fresh vendor pair가 없으면
N/A/INCONCLUSIVE 의미를 유지하세요.
