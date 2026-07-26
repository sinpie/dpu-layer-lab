# Release 절차

> **Authority:** version/tag/artifact naming, build·checksum·signing·GitHub publish,
> release별 고정 host/APK/device evidence와 product handoff 절차
> **Audience:** release engineer, repository maintainer, product integration 담당자
> **Update when:** version 규칙, build type, asset allowlist, signing 또는 publish 절차가 바뀔 때
> **Does not own:** 구현 architecture, safety invariant, 테스트 의미, BSP provider 계약
> **Related:** [Documentation index](INDEX.md), [README.md](../README.md), [AGENTS.md](../AGENTS.md),
> [PROJECT_MEMORY.md](../PROJECT_MEMORY.md), [TESTING.md](TESTING.md),
> [EXTERNAL_CONTRACTS.md](EXTERNAL_CONTRACTS.md), [REPORT_SCHEMA.md](REPORT_SCHEMA.md),
> [SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md)

Canonical remote:
`https://github.com/sinpie/dpu-layer-lab.git`

## 최신 공개 release contract

| 항목 | 값 |
|---|---|
| launcher/project | `DPULayerTest` |
| application ID | `com.example.dpulayerlab` |
| versionCode | `8` |
| release versionName | `20260726_101046` |
| debug versionName | `20260726_101046-debug` |
| tag | `v20260726_101046` |
| Soong module/APK | `DpuLayerLab` |
| report prefix | `dpu-layer-lab-` |

`yyyyMMdd_HHmmss`는 KST build 시각이다. Source version의 machine authority는
[app/build.gradle.kts](../app/build.gradle.kts)다. 새 release를 발행할 때 이 표,
`README.md`, `PROJECT_MEMORY.md`, `AGENTS.md`, release tag와 asset 이름을 함께
갱신한다.

package, automation component/action, report prefix와 Soong 이름은 제품 호환성 계약이다.
단순 rebranding 작업에서 바꾸지 않는다.

## 최신 공개 release asset allowlist

GitHub Release에는 다음 세 파일만 올린다.

- `DPULayerTest-20260726_101046-debug.apk`
- `DPULayerTest-20260726_101046-release-unsigned.apk`
- `SHA256SUMS.txt`

현재 checksum은 최종 source 상태의 APK를 빌드·검증한 뒤 이 위치에 고정한다. 다른
version의 값을 재사용하지 않는다.

```text
5199361c2d77a20ecd97ef7a74516554297f7c301db755b65dbac4f08e1b5ed2  DPULayerTest-20260726_101046-debug.apk
ad90d94f6bfe26afaf188bfa5345cf0ef4cfa87429a52ef370d9d7c546ad8a29  DPULayerTest-20260726_101046-release-unsigned.apk
```

이 값은 tag `v20260726_101046`의 공개 asset에만 해당한다. 새 build에 재사용하지
않는다. 아래 release evidence도 이 문서만 authority이며 가변 test 수를 README나
PROJECT_MEMORY에 복제하지 않는다.

## 최신 공개 release evidence

최종 gate가 끝나면 XML/report와 APK 자체를 기준으로 다음 표를 채운다. 명령 exit code만
성공 증거로 사용하지 않는다.

| 검증 | `20260726_101046` 결과 |
|---|---|
| host toolchain | Android Studio JBR OpenJDK 21.0.10, Java/Kotlin target 17, SDK/build-tools 36.0.0, Gradle 8.13 |
| JVM unit test XML | 44 suites / 773 tests / failure 0 / error 0 / skipped 0 |
| `lintDebug` XML | fatal 0 / error 0 / warning 5 — dependency 새 version 알림만 존재 |
| assemble | `assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest` 성공 — 25,281,548 B / 18,781,478 B |
| APK badging | debug `com.example.dpulayerlab.debug` / `20260726_101046-debug`; release `com.example.dpulayerlab` / `20260726_101046`; 모두 code 8, label `DPULayerTest`, SDK 29/36 |
| APK signing | debug v2, `C=US, O=Android, CN=Android Debug`, cert SHA-256 `d8fab8fa579e800823ef9f571f938581356ab5bb0515b4686c01ff9b35864d9d`; release는 의도대로 unsigned이며 `apksigner`가 `Missing META-INF/MANIFEST.MF`로 거부 |
| zip alignment | 두 APK 모두 `zipalign -c -P 16 4` 통과; native library 0개 |
| merged manifest | release alias만 `CONTROL_TESTS`(`signature\|privileged`) 요구; debug alias permission 없음; 두 variant 모두 alias action 3개, `CATEGORY_DEFAULT` 0개, MainActivity automation action 0개 |
| device stress | 미실행 — 대상 실험기와 실행 범위가 지정되지 않음 |

## Artifact 의미

### Debug APK

- Android debug key로 서명돼 설치 가능하다.
- 전용 lab/개발 환경용이다.
- debug manifest는 explicit automation alias의 `CONTROL_TESTS` permission을 제거한다.
- 열린 automation trust boundary를 제품 보안으로 간주하지 않는다.
- package suffix는 `.debug`이므로 release privapp allowlist와 동일하지 않다.

### Release unsigned APK

- 제품 build/platform signing pipeline의 입력이다.
- 최종 설치 가능한 제품 APK가 아니다.
- 저장소 Gradle configuration은 제품 signing key를 참조하지 않는다.

### Product APK

- secure product environment에서 platform/product key로 서명한다.
- `priv-app` allowlist, SELinux, Binder/provider와 device telemetry를 함께 검증한다.
- platform signing만으로 vendor sysfs/debugfs의 DAC/SELinux 접근이 생기지 않는다.

## 금지 asset

저장소, tag, GitHub Release, build artifact directory에 다음을 넣지 않는다.

- platform/product private key, `*.pk8`, private `*.pem`
- keystore, certificate bundle과 password
- signing token, vendor credential, `.env`
- device report, build fingerprint를 포함한 lab JSON
- 선택 영상 본문 또는 비공개 test content

`.gitignore`가 막는다는 사실만 믿지 말고 staged tree와 release directory를 직접 검사한다.

## 새 version 준비

1. KST build timestamp를 결정한다.
2. `app/build.gradle.kts`의 `versionCode`를 증가시키고 `versionName`을 timestamp로 바꾼다.
3. 다음 문서의 current release 값을 함께 갱신한다.
   - `README.md`
   - `PROJECT_MEMORY.md`
   - `AGENTS.md`
   - 이 문서
4. tag `v<versionName>`과 asset 이름을 결정한다.
5. UI/HUD/report가 `BuildConfig.VERSION_NAME`을 표시하는지 test한다.
6. 기존 compatibility identifier가 변하지 않았는지 확인한다.

release version은 한 commit에서 고정한다. publish 뒤 같은 tag의 APK를 다시 빌드해
교체하지 않는다.

## Preflight

```powershell
git status --short
git remote -v
git tag --list "v<versionName>"
git diff --check
```

확인:

- unrelated/user change를 포함하지 않는가?
- local SDK/JDK path가 tracked file에 없는가?
- signing material과 report가 staged되지 않았는가?
- debug/release manifest의 automation permission 차이가 유지되는가?
- versionName/versionCode와 문서·tag·asset 이름이 일치하는가?
- `PLAN.md`의 release 대상 작업이 acceptance gate를 충족했는가?

## Build와 host validation

[TESTING.md](TESTING.md)의 기준 환경에서:

```powershell
$env:JAVA_HOME='<JDK_17_HOME>'
$env:ANDROID_HOME='<ANDROID_SDK_ROOT>'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

검증 결과는 명령 exit code뿐 아니라 XML/HTML report와 APK 존재로 확인한다. 실패, skip,
warning과 실기기 미검증 범위를 release note에 사실대로 기록한다.

release 요청에서 renderer/NDK/native library가 포함되면 APK의 16 KiB page alignment도
검증한다. 현재 pure Java/Kotlin artifact라고 가정해 해당 확인을 생략하지 않는다.

## Staging directory

tracked source와 섞이지 않는 version별 directory를 사용한다.

```powershell
$releaseVersion = '<yyyyMMdd_HHmmss>'
$stage = Join-Path 'build-artifacts' $releaseVersion
New-Item -ItemType Directory -Path $stage -ErrorAction Stop

Copy-Item `
  app/build/outputs/apk/debug/app-debug.apk `
  (Join-Path $stage "DPULayerTest-$releaseVersion-debug.apk")

Copy-Item `
  app/build/outputs/apk/release/app-release-unsigned.apk `
  (Join-Path $stage "DPULayerTest-$releaseVersion-release-unsigned.apk")
```

기존 directory를 덮어쓰지 않는다. 이미 같은 version staging이 있으면 내용과 checksum을
검증하고 새 version을 만들지, 작업을 중단할지 명시적으로 결정한다.

## Checksum

PowerShell:

```powershell
$releaseVersion = '<yyyyMMdd_HHmmss>'
$stage = Join-Path 'build-artifacts' $releaseVersion
$assets = @(
  "DPULayerTest-$releaseVersion-debug.apk",
  "DPULayerTest-$releaseVersion-release-unsigned.apk"
)
$lines = foreach ($name in $assets) {
  $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $stage $name)).Hash.ToLowerInvariant()
  "$hash  $name"
}
$lines | Set-Content -Encoding ascii -LiteralPath (Join-Path $stage 'SHA256SUMS.txt')
```

release upload 직전과 GitHub에서 다시 다운로드한 뒤 모두 검증한다. checksum 파일에
절대 경로나 추가 artifact를 넣지 않는다.

## Secret와 asset 검사

staging directory에는 allowlist 세 파일만 있어야 한다.

```powershell
$releaseVersion = '<yyyyMMdd_HHmmss>'
$stage = Join-Path 'build-artifacts' $releaseVersion
Get-ChildItem -LiteralPath $stage -File | Select-Object Name,Length

git ls-files |
  Select-String -Pattern '\.(pk8|jks|keystore|p12|pfx|key)$|(^|/)\.env($|\.)'
```

APK에 private key가 포함되지 않았는지도 archive entry와 certificate 정보를 확인한다.

## Commit, tag와 push

이 단계는 사용자에게 commit/push 권한을 받은 작업에서만 수행한다.

1. source와 canonical 문서만 의도적으로 stage한다.
2. build output, staging APK와 reports를 commit하지 않는다.
3. host gate가 통과한 exact source state를 commit한다.
4. annotated 또는 repository 정책에 맞는 tag `v<versionName>`을 그 commit에 만든다.
5. canonical `origin`에 commit과 tag를 push한다.
6. remote tag가 같은 commit을 가리키는지 확인한다.

tag는 APK를 만든 source state와 달라서는 안 된다.

## GitHub Release publish

1. 정확한 tag에서 release를 만든다.
2. release title/version과 tag를 일치시킨다.
3. 주요 변경, host validation, 미실행 device validation과 artifact 의미를 기록한다.
4. allowlist 세 asset만 업로드한다.
5. publish 결과가 terminal인지 확인한다.
6. asset 이름, size, download 가능 여부와 checksum을 재검증한다.

debug APK에는 “installable lab-only”, unsigned release에는 “product signing input”을
명시한다.

## Product signing handoff

권장 방식은 `system_integration/product/Android.bp`가 Soong `certificate: "platform"`을
사용하는 것이다. 외부 signing이 필요하면 secure build machine에서만 수행한다.

```powershell
apksigner sign `
  --key '<PLATFORM_KEY_PATH>' `
  --cert '<PLATFORM_CERT_PATH>' `
  --out DPULayerTest-platform.apk `
  app\build\outputs\apk\release\app-release-unsigned.apk
```

제품 통합 결과는 이 공개 repository release와 별개의 internal artifact다.
[SYSTEM_INTEGRATION.md](SYSTEM_INTEGRATION.md)의 permission, AIDL, SELinux와 telemetry
검증을 통과해야 한다.

## Post-publish 검증

- remote tag commit과 local release commit 일치
- asset 세 개 외 추가 파일 없음
- 다운로드한 SHA-256 일치
- debug APK의 package/version/signature 확인
- unsigned release가 실제 unsigned pipeline input인지 확인
- README의 version, tag, link와 artifact 의미 일치
- GitHub release note에 device stress 미검증 범위 명시
- platform key/credential가 source/history/release에 없음

## Rollback과 정정

- 잘못된 공개 APK를 같은 tag에서 조용히 교체하지 않는다.
- 아직 사용되지 않은 명백한 publish 오류라면 release를 중단하고 owner와 정정 절차를
  결정한다.
- 사용자가 다운로드할 수 있었던 artifact는 새 versionCode/versionName/tag로 supersede하고
  이전 release에 문제를 명시한다.
- signing material 노출 가능성이 있으면 release 삭제만으로 충분하지 않다. credential
  revoke/rotate와 repository history incident 절차를 수행한다.
- product signing/SELinux 문제는 공개 unsigned APK를 재작성하기 전에 BSP 통합 문제인지
  source 문제인지 분리한다.
