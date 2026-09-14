#!/usr/bin/env bash
# V25-W02 SNAPSHOT-3 | fixes a REAL GAP found in snapshot-2: snapshot-2 resolved the
# secondary worktrees to <repo>/.git/worktrees/<name>, which are git DIRECTORIES, not work
# trees. `git worktree list --porcelain` shows the real locations are OUTSIDE the repo:
#   .f88-baseline-wt -> D:/Develop/GraduationProject/.f88-baseline-wt
#   m3-jdk8fix       -> D:/Develop_code/GraduationProject-wt/m3-jdk8fix
# Snapshot-3 derives worktree paths FROM GIT (never hard-coded) and scans every one.
# Additive: does not touch snapshot-1 or snapshot-2.
set -u
REPO=/mnt/d/Develop_code/GraduationProject
BK3=/mnt/d/Develop/backup/v25-w02-20260914-snap3
EV=$REPO/docs/acceptance/v25-w02-w03-wsl-runtime-20260914/raw
MAN=$EV/62-w02-backup-manifest-snap3-sha256.txt
SECRET_RE='(\.env$|\.env\.|credentials|secret|id_rsa|id_ed25519|\.pem$|\.p12$|\.jks$|\.keystore$|keystore|\.npmrc$|\.pypirc$|\.netrc$|settings\.xml$|\.mvn/|token|password|passwd|\.git-credentials)'

echo "=== worktree list BEFORE (authoritative source of paths) ==="
git -C "$REPO" worktree list --porcelain | tee "$EV/61b-w02-worktree-list-authoritative.txt"
echo
echo "=== HEAD per worktree (recorded for evidence) ==="
git -C "$REPO" worktree list --porcelain | awk '/^worktree /{p=substr($0,10)} /^HEAD /{print p"  HEAD="substr($0,6)} /^branch /{print p"  branch="substr($0,8)} /^detached/{print p"  detached"}' | tee "$EV/61c-w02-worktree-heads.txt"

mkdir -p "$BK3"
: > "$MAN"
{
  echo "# V25-W02 SNAPSHOT-3 backup manifest (sha256) — generated $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "# ADDITIVE. snapshot-1 = 12:20:25 state; snapshot-2 = 12:32:49 state; snapshot-3 = now."
  echo "# snapshot-3 additionally covers the two OUT-OF-REPO worktrees that snapshot-2 addressed by wrong paths."
  echo "# columns: worktree-label<TAB>worktree-root<TAB>git-status<TAB>relative-path<TAB>sha256<TAB>bytes<TAB>verdict"
} >> "$MAN"

TOTAL=0; VERIFIED=0; SECRETS=0; BYTES=0; GONE=0
declare -a LOCKED_FILES=()

scan() {
  local label="$1" root="$2"
  echo "### [$label] $root"
  if [ ! -d "$root" ]; then echo "  ROOT ABSENT — skipped"; return; fi
  local before=$VERIFIED
  while IFS= read -r -d '' e; do
    [ ${#e} -lt 4 ] && continue
    local st="${e:0:2}" rel="${e:3}"
    [ -z "$rel" ] && continue
    TOTAL=$((TOTAL+1))
    local src="$root/$rel" dst="$BK3/$label/$rel"
    if [ ! -f "$src" ]; then
      GONE=$((GONE+1))
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$root" "$st" "$rel" "N/A" "0" "SRC_GONE_OR_DIR" >> "$MAN"
      continue
    fi
    if printf '%s' "$rel" | grep -Eqi "$SECRET_RE"; then
      SECRETS=$((SECRETS+1))
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$root" "$st" "$rel" "REDACTED" "0" "SECRET_EXCLUDED_PRESENCE_ONLY" >> "$MAN"
      continue
    fi
    local sha; sha=$(sha256sum "$src" 2>/dev/null | cut -d' ' -f1)
    if [ -z "$sha" ]; then
      LOCKED_FILES+=("$label|$src|$st|$rel|$root")
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$root" "$st" "$rel" "UNREADABLE" "0" "SRC_UNREADABLE_LOCKED" >> "$MAN"
      continue
    fi
    local sz; sz=$(stat -c%s "$src")
    mkdir -p "$(dirname "$dst")"
    if cp -p "$src" "$dst" 2>/dev/null; then
      local sha2; sha2=$(sha256sum "$dst" | cut -d' ' -f1)
      if [ "$sha" = "$sha2" ]; then
        VERIFIED=$((VERIFIED+1)); BYTES=$((BYTES+sz))
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$root" "$st" "$rel" "$sha" "$sz" "BACKED_UP:VERIFIED" >> "$MAN"
      else
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$root" "$st" "$rel" "$sha" "$sz" "COPY_HASH_MISMATCH" >> "$MAN"
      fi
    else
      LOCKED_FILES+=("$label|$src|$st|$rel|$root")
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$root" "$st" "$rel" "$sha" "$sz" "COPY_FAILED_LOCKED" >> "$MAN"
    fi
  done < <(git -C "$root" status --porcelain=v1 -uall -z 2>/dev/null)
  echo "  entries this worktree: $((VERIFIED-before)) verified"
}

# derive every worktree path straight from git — no hard-coding
mapfile -t WTS < <(git -C "$REPO" worktree list --porcelain | awk '/^worktree /{print substr($0,10)}')
for wt in "${WTS[@]}"; do
  case "$wt" in
    "$REPO")            scan "main" "$wt" ;;
    *".f88-baseline-wt") scan "f88-baseline-wt" "$wt" ;;
    *"m3-jdk8fix")       scan "m3-jdk8fix" "$wt" ;;
    *)                  scan "$(basename "$wt" | tr -c 'A-Za-z0-9._-' '_')" "$wt" ;;
  esac
done

echo
echo "### retry locked files"
for i in 1 2 3; do
  [ ${#LOCKED_FILES[@]} -eq 0 ] && break
  echo "--- retry pass $i ---"; sleep 4
  keep=()
  for entry in "${LOCKED_FILES[@]}"; do
    IFS='|' read -r lb src st rel root <<< "$entry"
    sha=$(sha256sum "$src" 2>/dev/null | cut -d' ' -f1)
    if [ -n "$sha" ]; then
      dst="$BK3/$lb/$rel"; mkdir -p "$(dirname "$dst")"
      if cp -p "$src" "$dst" 2>/dev/null && [ "$sha" = "$(sha256sum "$dst" | cut -d' ' -f1)" ]; then
        sz=$(stat -c%s "$src"); VERIFIED=$((VERIFIED+1)); BYTES=$((BYTES+sz))
        awk -v lb="$lb" -v st="$st" -v rel="$rel" -v sha="$sha" -v sz="$sz" -F'\t' -v OFS='\t' \
          '{ if ($1==lb && $3==st && $4==rel && $7=="SRC_UNREADABLE_LOCKED") {$5=sha;$6=sz;$7="BACKED_UP:VERIFIED_RETRY"} print }' "$MAN" > "$MAN.tmp" && mv "$MAN.tmp" "$MAN"
        echo "  RETRY_OK: $lb/$rel"; continue
      fi
    fi
    keep+=("$entry"); echo "  still locked: $lb/$rel"
  done
  LOCKED_FILES=("${keep[@]}")
done

echo
echo "=== secret-path exclusions (presence only; content NEVER copied) ==="
tail -n +5 "$MAN" | awk -F'\t' '$7=="SECRET_EXCLUDED_PRESENCE_ONLY"{print "  "$1"/"$4}'
echo "  count: $SECRETS"
echo
echo "================ SNAPSHOT-3 SUMMARY ================"
echo "entries scanned : $TOTAL"
echo "VERIFIED        : $VERIFIED"
echo "still locked    : ${#LOCKED_FILES[@]}"
echo "src gone/dirs   : $GONE"
echo "secret-excluded : $SECRETS"
echo "bytes           : $BYTES"
echo "backup tree     : $BK3"
echo "--- files on disk per worktree ---"
for d in "$BK3"/*/; do printf '  %-24s %5s files %8s\n' "$(basename "$d")" "$(find "$d" -type f | wc -l)" "$(du -sh "$d" | cut -f1)"; done
echo "  TOTAL $(find "$BK3" -type f | wc -l) files, $(du -sh "$BK3" | cut -f1)"
echo "--- verdict tally ---"
tail -n +5 "$MAN" | awk -F'\t' '{print $7}' | sort | uniq -c | sort -rn
echo "--- manifest lines: $(wc -l < "$MAN")"
echo "### DONE rc=0"
