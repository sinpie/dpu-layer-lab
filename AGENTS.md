# Repository Working Rules

이 파일은 사람과 coding agent가 DPU Layer Lab을 수정할 때 따르는 canonical repository
instruction입니다. 장기 설계 맥락은 `PROJECT_MEMORY.md`, 사용자-facing 설명은
`README.md`를 먼저 확인합니다.

## 기본 작업 규칙

- 사용자 변경과 unrelated dirty worktree를 보존한다.
- source 수정에는 작은 patch를 사용하고, generated output은 source와 섞지 않는다.
- app 동작, safety policy, report schema 또는 계측 의미가 바뀌면 test와 문서를 함께
  갱신한다.
- 실제 BSP에 종속된 가정은 portable code에 숨기지 말고 adapter/typed contract로
  격리한다.
- 오류를 삼켜 성공처럼 보이게 하지 않는다. unsupported/unavailable/proxy를 구분한다.
- APK, capture, report, signing material과 local SDK 경로를 commit하지 않는다.

## 빌드

기준 환경은 JDK 17, SDK 36, AGP 8.12.2, Gradle wrapper 8.13이다.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='D:\Project\Android_SDK'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

환경별 SDK/JDK 절대 경로를 Gradle source나 tracked 파일에 넣지 않는다.

## 안전 불변식

- renderer 입력은 반드시 runtime safety policy를 통과해야 한다.
- hard cap(layer 20, producer 120 fps, requested display 240 Hz)을 늘리려면 명시적인
  요구, budget 근거, boundary test와 문서 변경이 모두 필요하다.
- graphics memory는 최소 triple buffering을 가정하고 총/available RAM을 함께
  고려한다. 한 producer가 budget을 넘으면 reject한다.
- low-RAM/power-save cap을 우회하지 않는다.
- `ActivityManager.MemoryInfo.lowMemory`와 thermal CRITICAL 이상은 active run을
  중단한다.
- thermal SEVERE derating은 이후 phase에도 유지한다.
- loop, thread, buffer allocation, codec dequeue, Binder call에는 상한이나
  cancellation 경로가 있어야 한다.
- 모든 종료 경로에서 CPU/memory worker, codec, Surface, GL, vendor NPU/SBWC state,
  wake flag를 해제한다.
- 연결된 실기기에서 stress scenario를 자동 실행하지 않는다. 사용자가 대상 실험기와
  실행 범위를 명시해야 한다.

## 계측 정확성

- 숫자는 `MetricQuality`와 source를 유지한다.
- DPU busy/exact underrun은 검증된 vendor 또는 kernel source만 사용한다.
- missed frame, `Choreographer`, SurfaceFlinger HWC/GPU miss, producer stall은
  `PROXY`이며 exact underrun으로 표현하지 않는다.
- source가 없거나 parse가 불확실하면 0이 아니라 `N/A`/`UNAVAILABLE`을 반환한다.
- traffic은 linear full-buffer `ESTIMATED` 모델이다. 실측 bus 점유율과 합치거나 capacity
  판정에 사용하지 않는다.
- SBWC REQUIRED는 실제 allocation/compression state를 확인하지 못하면 성공으로
  처리하지 않는다.
- NPU adapter가 없을 때 CPU 연산으로 대체해 NPU 사용이라고 표시하지 않는다.
- counter의 monotonicity, reset/wrap, display scope, sampling interval을 test하고
  report/source에 보존한다.

## 금지사항

- platform signing만으로 SELinux/DAC 또는 vendor node 접근이 가능하다고 가정하지 않는다.
- 앱 domain 전체에 광범위한 `/sys`/debugfs read/write 권한을 권장하지 않는다.
- 임의 sysfs/debugfs path 탐색, root 명령 또는 SELinux 우회를 portable app에 넣지 않는다.
- 무제한 layer/buffer/thread 생성, busy loop 또는 blocking Binder getter를 추가하지 않는다.
- thermal/low-memory abort를 “벤치마크 연속성”을 이유로 비활성화하지 않는다.
- background network upload, analytics, 영상 본문 수집을 명시적 요구와 privacy 설계 없이
  추가하지 않는다.
- platform key, `*.pk8`, `*.pem`, keystore, password, token, device report를 commit하지
  않는다.
- build artifact를 source commit에 포함하지 않는다.

## 완료 정의

변경은 다음을 만족해야 완료다.

1. 요청 동작과 실패/취소/수명주기 edge case가 구현됐다.
2. safety cap과 계측 provenance가 유지된다.
3. 관련 boundary/unit test가 추가 또는 갱신됐다.
4. `testDebugUnitTest`와 `lintDebug`가 통과한다.
5. `assembleDebug`와 release 요청이 있으면 `assembleRelease`가 통과한다.
6. renderer/load 변경은 종료 후 resource가 남지 않는지 검토했다.
7. 사용자-facing 의미가 바뀌면 `README.md`, 장기 결정이 바뀌면
   `PROJECT_MEMORY.md`, BSP 계약이 바뀌면 `docs/SYSTEM_INTEGRATION.md`를 갱신했다.
8. tracked 파일에 secret, APK, report, local path가 없다.
