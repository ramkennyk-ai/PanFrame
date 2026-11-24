#!/data/data/com.termux/files/usr/bin/bash
# Usage: bash termux_push.sh [repo-name] [private|public]
set -euo pipefail
REPO="${1:-PanFrame}"
VIS="${2:-private}"
command -v git  >/dev/null || pkg install -y git
command -v curl >/dev/null || pkg install -y curl
cd "$(dirname "$0")"

GH_TOKEN="${GH_TOKEN:-}"
if [ -z "$GH_TOKEN" ] && command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  GH_TOKEN="$(gh auth token)"
fi
[ -n "$GH_TOKEN" ] || { read -rsp "GitHub token (hidden): " GH_TOKEN; echo; }

GH_USER=$(curl -s -H "Authorization: Bearer $GH_TOKEN" https://api.github.com/user \
  | grep -o '"login": *"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$GH_USER" ] || { echo "Token rejected. Check it and its scopes."; exit 1; }
echo "Logged in as: $GH_USER"

PRIV=true; [ "$VIS" = "public" ] && PRIV=false
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST https://api.github.com/user/repos \
  -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" \
  -d "{\"name\":\"$REPO\",\"private\":$PRIV}")
case "$CODE" in
  201) echo "Repo created: $GH_USER/$REPO ($VIS)";;
  422) echo "Repo already exists, pushing to it";;
  *)   echo "GitHub API returned $CODE"; exit 1;;
esac

[ -d .git ] || git init -q -b main
git config user.name  "$GH_USER"
git config user.email "$GH_USER@users.noreply.github.com"
AUTH="https://${GH_USER}:${GH_TOKEN}@github.com/${GH_USER}/${REPO}.git"

if git fetch -q "$AUTH" main:refs/remotes/origin/main 2>/dev/null; then
  git symbolic-ref HEAD refs/heads/main
  git update-ref refs/heads/main refs/remotes/origin/main
fi

git add -A
git commit -q -m "${COMMIT_MSG:-PanFrame v3: 33ms pan sampling, camera off in re-pan, pause export while recording}" || echo "Nothing new to commit"
git push "$AUTH" main
git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/${GH_USER}/${REPO}.git"

echo
echo "Pushed. Build: https://github.com/${GH_USER}/${REPO}/actions"
