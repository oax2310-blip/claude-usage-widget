#!/usr/bin/env bash
# GitHub Actions에서 실행: 서명 키 준비 → 테스트·빌드 → 릴리스 업로드 (실패 시 로그를 이슈로 등록)
set -uo pipefail

# 실패 로그를 이슈로(공개 저장소라 비밀값은 절대 넣지 않음). $1: 이슈 맨 위에 붙일 설명
report_failure() {
  {
    if [ -n "${1:-}" ]; then echo "$1"; echo; fi
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

# 1) 서명 키: 비밀번호는 GitHub Secrets(SIGNING_PASSWORD)에만 있고, 저장소에는 그 비밀번호로 암호화한 키만 둠.
#    첫 빌드 때 키를 만들어 암호화해 올림 → 이후 같은 키로 서명(업데이트 덮어쓰기 설치 가능).
#    예전 debug 키는 저장소에 공개돼 있어 누구나 같은 서명으로 가짜 APK를 만들 수 있어서 더 쓰지 않음.
ENC=keystore/release.p12.enc
KEY=app/release.p12
trap 'rm -f "$KEY"' EXIT
SECRET_HELP="저장소 Settings → Secrets and variables → Actions → New repository secret에서 이름 SIGNING_PASSWORD로 추가해 주세요 (영문·숫자·기호만, 16자 이상)."
if [ -z "${SIGNING_PASSWORD:-}" ]; then
  report_failure "서명 비밀번호(SIGNING_PASSWORD)가 없어서 빌드하지 않았어요. $SECRET_HELP"
fi
if [ "${#SIGNING_PASSWORD}" -lt 16 ] || printf %s "$SIGNING_PASSWORD" | LC_ALL=C grep -q '[^ -~]'; then
  report_failure "서명 비밀번호(SIGNING_PASSWORD)는 영문·숫자·기호만으로 16자 이상이어야 해요(서명 키 형식이 한글을 못 씀). $SECRET_HELP"
fi
if [ -f "$ENC" ]; then
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in "$ENC" -out "$KEY" -pass env:SIGNING_PASSWORD \
    || report_failure "서명 키를 풀지 못했어요 — SIGNING_PASSWORD가 키를 만들 때와 달라요. 비밀번호를 잊었으면 $ENC 를 지우고 새 비밀번호로 다시 만들어야 하고, 그때는 앱을 지우고 새로 설치해야 해요."
else
  keytool -genkeypair -keystore "$KEY" -storetype pkcs12 -storepass:env SIGNING_PASSWORD \
    -alias claudeusage -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Claude Usage Widget" || report_failure "서명 키를 만들지 못했어요"
  mkdir -p "$(dirname "$ENC")"
  openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -in "$KEY" -out "$ENC" -pass env:SIGNING_PASSWORD \
    || report_failure "서명 키를 암호화하지 못했어요"
  git config user.name "github-actions[bot]"
  git config user.email "41898283+github-actions[bot]@users.noreply.github.com"
  git add "$ENC"
  git commit -m "비공개 서명 키 생성 (비밀번호는 GitHub Secrets에만, 저장소에는 암호화된 키만)"
  # 키를 저장소에 못 올렸으면 이 키로 서명한 릴리스를 내지 않음(다음 빌드가 다른 키를 만들면 업데이트가 깨짐)
  git push || report_failure "암호화한 서명 키를 저장소에 올리지 못했어요"
fi

# 2) 단위 테스트 + APK 빌드
gradle --no-daemon testReleaseUnitTest assembleRelease 2>&1 | tee build.log
[ "${PIPESTATUS[0]}" -eq 0 ] || report_failure

# 3) 릴리스에 APK 업로드
cp app/build/outputs/apk/release/app-release.apk ClaudeUsage.apk || report_failure
gh release create "v1.0.${RUN}" ClaudeUsage.apk \
  --title "Claude 사용량 v1.0.${RUN}" --notes "자동 빌드 #${RUN}" --latest || report_failure
