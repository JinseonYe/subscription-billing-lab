#!/usr/bin/env bash
cmd=$(jq -r '.tool_input.command // empty')
branch=$(git -C "$CLAUDE_PROJECT_DIR" branch --show-current 2>/dev/null)

if [[ "$cmd" =~ git[[:space:]]+commit ]] && [[ "$branch" == "main" ]]; then
  echo "main 브랜치에 직접 커밋할 수 없습니다. 기능 브랜치를 만드세요." >&2
  exit 2
fi
if [[ "$cmd" =~ git[[:space:]]+push.*[[:space:]]main ]]; then
  echo "main에 직접 push할 수 없습니다. PR로 반영하세요." >&2
  exit 2
fi
exit 0
