#!/usr/bin/env bash
input=$(cat)

# 이미 한 번 막혀서 재시도 중이면 무한 루프 방지
if echo "$input" | grep -q '"stop_hook_active": *true'; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR" || exit 0

# Kotlin, 빌드 파일, 리소스 변경이 없으면 건너뜀
changed=$(git status --porcelain -- '*.kt' '*.kts' 'src/main/resources' | wc -l)
[ "$changed" -eq 0 ] && exit 0

if ! out=$(./gradlew spotlessApply check -q 2>&1); then
  echo "빌드 또는 테스트 실패. 원인을 고친 뒤 다시 종료하세요:" >&2
  echo "$out" | tail -n 80 >&2
  exit 2
fi
exit 0
