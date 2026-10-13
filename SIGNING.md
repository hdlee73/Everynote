# 서명 키 관리 (Everynote Android)

저장소에는 키스토어·비밀번호가 없습니다. 정식 릴리스 APK는 GitHub Actions의 `release` Environment에 저장된 비밀값으로만 서명됩니다.

## 구조

| 단계 | 하는 일 | 비밀값 접근 |
| --- | --- | --- |
| `build` 잡 | 테스트, 디버그 APK, **서명 안 된** 릴리스 APK 빌드 | 없음 (PR·포크 포함) |
| `release` 잡 | 태그 `v*` 또는 main의 `[release]` 푸시에서만 실행. zipalign → `apksigner sign --lineage` → 검증 → GitHub 릴리스 게시(+`.sha256`) | `release` Environment 비밀값 |

필요한 값(`release` Environment의 Secrets): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `SIGNING_LINEAGE_BASE64`.
선택 값(Variables): `EXPECTED_CERT_SHA256` — 새 인증서 SHA-256. 설정하면 다른 키로 서명된 APK는 게시되지 않습니다.

PR 빌드는 정식 키를 쓰지 않으므로 `test-latest` 테스트 APK는 더 이상 게시되지 않습니다. 디버그 APK는 Actions 아티팩트로 받을 수 있습니다(기본 디버그 키라 정식 앱 위에는 설치되지 않음).

## 키 교체 절차 (처음 한 번, 직접 해야 하는 작업)

`apksigner`는 Android SDK `build-tools`(28 이상)에 들어 있습니다. 아래에서 `$APKSIGNER`는 그 경로입니다(예: `~/Android/Sdk/build-tools/35.0.0/apksigner`).

### 1. 옛 키스토어를 오프라인에 백업
1. 이 저장소의 v1.56.0 이하 커밋에서 `app/pdfnote-release.jks`를 받습니다(별칭 `pdfnote`, 비밀번호는 예전 `app/build.gradle`에 있던 값).
2. USB 등 **오프라인** 저장소에 복사합니다. 키 교체 lineage를 만들 때 한 번만 필요합니다.
3. 지문을 기록해 둡니다: `keytool -list -v -keystore pdfnote-release.jks -alias pdfnote | grep SHA256`

### 2. 새 키스토어 생성 (내 컴퓨터에서)
```bash
keytool -genkeypair -v -keystore everynote-release.jks -alias everynote \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```
- 비밀번호는 새로 정하고 비밀번호 관리자에 저장합니다(PKCS12는 키 비밀번호 = 저장소 비밀번호).
- 새 지문을 기록합니다: `keytool -list -v -keystore everynote-release.jks -alias everynote | grep SHA256`
- **이 파일과 비밀번호를 잃으면 이 앱은 영영 업데이트할 수 없습니다.** 오프라인 사본을 2곳 이상 두세요.

### 3. 키 교체 lineage 파일 생성
```bash
$APKSIGNER rotate --out everynote.lineage \
  --old-signer --ks pdfnote-release.jks --ks-key-alias pdfnote \
  --new-signer --ks everynote-release.jks --ks-key-alias everynote
```
비밀번호는 프롬프트에 입력합니다(명령줄에 쓰지 마세요). 확인: `$APKSIGNER lineage --print-certs --in everynote.lineage` 에 인증서 2개(옛 → 새)가 순서대로 나와야 합니다. lineage 파일에는 공개 인증서만 있어 비밀은 아니지만, 영구 보관하세요(다음 교체 때 필요).

### 4. GitHub에 비밀값 등록
1. 저장소 **Settings → Environments → New environment** → 이름 `release`.
2. **Deployment branches and tags** → *Selected branches and tags* → `main` 브랜치와 `v*` 태그만 추가. (이렇게 해야 PR이 비밀값에 접근하지 못합니다.)
3. 같은 화면 **Environment secrets**에 등록:
   - `KEYSTORE_BASE64` = `base64 -w0 everynote-release.jks` 출력(macOS는 `base64 -i everynote-release.jks`)
   - `KEYSTORE_PASSWORD`, `KEY_PASSWORD` = 새 키스토어 비밀번호
   - `KEY_ALIAS` = `everynote`
   - `SIGNING_LINEAGE_BASE64` = `base64 -w0 everynote.lineage` 출력
4. **Environment variables**에 `EXPECTED_CERT_SHA256` = 새 인증서 SHA-256(콜론 있어도 됨).
5. (권장) 저장소 Settings → Actions → General 에서 *Workflow permissions*를 *Read repository contents*로, Settings → Branches 에서 main 보호 규칙을 켭니다.

### 5. 릴리스 후 실기기 확인 (병합 전에 4단계가 끝나 있어야 합니다)
1. 이 PR을 병합해 새 버전(v1.57.0)을 릴리스합니다(제목에 `[release]`).
2. Actions의 `release` 잡 로그에서 `Verified using v3 scheme ... true`와 인증서 지문을 확인합니다.
3. **v1.56.0(옛 키)이 설치된 Android 9 이상 기기**에서 앱 정보 → 업데이트 확인 → 설치. 데이터가 유지되며 설치되어야 합니다.
4. 앱 정보 화면의 "서명 인증서 SHA-256"이 새 지문과 같은지 확인합니다.
5. 서명은 v3(키 교체 지원) 전용이라 **Android 9 이상에서만** 설치·업데이트됩니다. Android 8.x는 새 APK를 설치할 수 없습니다. 8.x까지 지원하려면 옛 키스토어를 Secrets에 추가해 v1/v2를 옛 키로, v3를 새 키로 서명하는 방식(`--next-signer`)으로 바꿔야 합니다.

### 6. 노출된 옛 키 정리
- 옛 비밀번호는 공개 저장소의 git 히스토리에 남아 있고 이미 누구나 옛 키로 서명할 수 있다고 봐야 합니다. 이 PR은 파일을 제거하지만 **히스토리는 그대로**입니다.
- 모든 기기가 업데이트된 뒤(충분한 기간 후) 옛 `pdfnote-release.jks`를 폐기하세요. lineage 파일은 보관합니다.
- 히스토리 정리(`git filter-repo --path app/pdfnote-release.jks --invert-paths` 후 force push)는 되돌릴 수 없으므로 별도 확인 후 진행합니다. 이미 복제·캐시된 사본은 회수되지 않으므로 정리는 "추가 노출 방지"일 뿐 옛 키가 안전해지는 것이 아닙니다.

## 로컬에서 서명 빌드가 필요할 때
저장소 루트에 `keystore.properties`(git 무시됨)를 만듭니다.
```
storeFile=/절대/경로/everynote-release.jks
storePassword=...
keyAlias=everynote
keyPassword=...
```
이 파일이 있으면 debug/release 빌드가 그 키로 서명됩니다. lineage가 없으므로 이렇게 만든 APK는 옛 키로 설치된 앱 위에 업데이트되지 않습니다.
