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
`20260724_111816`/debug suffix와 gauge provenance gap을 유지하고 View/client Z-order를
physical HWC 증거로 표현하지 않습니다. 또한 lifecycle close에서는 compression reset을
하지 않고, active SBWC route를 명령 acknowledgment의 vendor service session에
결속하며, remote snapshot timeout과 실제 registration 단절을 구분해야 합니다.
