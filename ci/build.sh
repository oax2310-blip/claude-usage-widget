#!/usr/bin/env bash
# GitHub Actions에서 실행: 서명 키 준비 → 테스트·빌드 → 릴리스 업로드 (실패 시 로그를 이슈로 등록)
set -uo pipefail

report_failure() {
  {
    echo '```'
    if [ -f build.log ]; then
      grep -nE "error:|FAILED|What went wrong|Exception" build.log | head -60
      echo '--- tail ---'
      tail -n 80 build.log
    else
      echo "build.log 없음 (빌드 전 단계에서 실패)"
    fi
    echo '```'
  } > body.md
  gh issue create --title "빌드 실패 #${RUN}" --body-file body.md || true
  exit 1
}

# 1) 서명 키: 첫 빌드 때 만들어 저장소에 저장 → 이후 같은 키로 서명(업데이트 덮어쓰기 설치 가능)
if [ -f keystore/debug.keystore.b64 ]; then
  base64 -d keystore/debug.keystore.b64 > app/debug.keystore || report_failure
else
  keytool -genkeypair -keystore app/debug.keystore -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" || report_failure
  mkdir -p keystore
  base64 -w 76 app/debug.keystore > keystore/debug.keystore.b64
  git config user.name "github-actions[bot]"
  git config user.email "41898283+github-actions[bot]@users.noreply.github.com"
  git add keystore/debug.keystore.b64
  git commit -m "서명 키 생성 (업데이트 덮어쓰기 설치용 고정 키)"
  git push || report_failure
fi

# 2) 단위 테스트 + APK 빌드
gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | tee build.log
[ "${PIPESTATUS[0]}" -eq 0 ] || report_failure

# 3) 릴리스에 APK 업로드
cp app/build/outputs/apk/release/app-release.apk ClaudeUsage.apk || report_failure
gh release create "v1.0.${RUN}" ClaudeUsage.apk \
  --title "Claude 사용량 v1.0.${RUN}" --notes "자동 빌드 #${RUN}" --latest || report_failure
