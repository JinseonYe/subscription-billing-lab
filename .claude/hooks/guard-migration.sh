#!/usr/bin/env bash
file=$(jq -r '.tool_input.file_path // empty')
case "$file" in
  */db/migration/V*.sql)
    if git -C "$CLAUDE_PROJECT_DIR" ls-files --error-unmatch "$file" >/dev/null 2>&1; then
      echo "커밋된 Flyway 마이그레이션은 수정할 수 없습니다. 새 버전 파일을 만드세요." >&2
      exit 2
    fi ;;
esac
exit 0
