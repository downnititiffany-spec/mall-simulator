#!/usr/bin/env bash
# V25-W02 SNAPSHOT-2 | captures the CURRENT uncommitted/untracked state.
# Rationale: snapshot-1 (12:20:25) was complete for its instant, but other lanes kept
# editing the repo (files touched 12:20-12:31). Snapshot-2 is an ADDITIVE, separate tree
# so BOTH the 12:20 state and the 12:35 state stay recoverable.
# Read-only w.r.t. the repo. Never modifies git state.
set -u
REPO=/mnt/d/Develop_code/GraduationProject
WT3=/mnt/d/Develop_code/GraduationProject/.git/worktrees
BK1=/mnt/d/Develop/backup/v25-w02-20260914
BK2=/mnt/d/Develop/backup/v25-w02-20260914-snap2
EV=/mnt/d/Develop_code/GraduationProject/docs/acceptance/v25-w02-w03-wsl-runtime-20260914/raw
MAN=$EV/60-w02-backup-manifest-snap2-sha256.txt
SECRET_RE='(\.env$|\.env\.|credentials|secret|id_rsa|id_ed25519|\.pem$|\.p12$|\.jks$|\.keystore$|keystore|\.npmrc$|\.pypirc$|\.netrc$|settings\.xml$|\.mvn/|token|password|passwd|\.git-credentials)'

mkdir -p "$BK2"
: > "$MAN"
{
  echo "# V25-W02 SNAPSHOT-2 backup manifest (sha256) — generated $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "# snapshot-1 was taken $(stat -c '%y' "$BK1" 2>/dev/null | cut -d. -f1); snapshot-2 is ADDITIVE and does not touch snapshot-1."
  echo "# columns: worktree<TAB>git-status<TAB>relative-path<TAB>sha256<TAB>bytes<TAB>verdict"
} >> "$MAN"

TOTAL=0; VERIFIED=0; LOCKED=0; SECRETS=0; BYTES=0
declare -a LOCKED_FILES=()
declare -a SECRET_FILES=()

scan_worktree() {
  local name="$1" root="$2"
  echo "### scanning [$name] $root"
  # CRITICAL: pipe git -z STRAIGHT into read -d '' . Never store it in a variable or
  # command substitution: bash strips NUL bytes, which silently collapses the entire
  # status into one line and yields 0 entries (bug hit and fixed in this session).
  while IFS= read -r -d '' e; do
    [ ${#e} -lt 4 ] && continue
    local st="${e:0:2}" rel="${e:3}"
    # strip rename "old -> new" tail is not present in -z v1 for ??/M; guard anyway
    [ -z "$rel" ] && continue
    TOTAL=$((TOTAL+1))
    local src="$root/$rel"
    local dst="$BK2/$name/$rel"
    if [ ! -f "$src" ]; then
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$st" "$rel" "N/A" "0" "SRC_GONE_DIR_OR_MISSING" >> "$MAN"
      continue
    fi
    if printf '%s' "$rel" | grep -Eqi "$SECRET_RE"; then
      SECRETS=$((SECRETS+1)); SECRET_FILES+=("$name/$rel")
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$st" "$rel" "REDACTED" "0" "SECRET_EXCLUDED_PRESENCE_ONLY" >> "$MAN"
      continue
    fi
    local sha sz
    sha=$(sha256sum "$src" 2>/dev/null | cut -d' ' -f1)
    if [ -z "$sha" ]; then
      LOCKED=$((LOCKED+1)); LOCKED_FILES+=("$name|$src|$st|$rel")
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$st" "$rel" "UNREADABLE" "0" "SRC_UNREADABLE_LOCKED" >> "$MAN"
      continue
    fi
    sz=$(stat -c%s "$src")
    mkdir -p "$(dirname "$dst")"
    if cp -p "$src" "$dst" 2>/dev/null; then
      local sha2; sha2=$(sha256sum "$dst" | cut -d' ' -f1)
      if [ "$sha" = "$sha2" ]; then
        VERIFIED=$((VERIFIED+1)); BYTES=$((BYTES+sz))
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$st" "$rel" "$sha" "$sz" "BACKED_UP:VERIFIED" >> "$MAN"
      else
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$st" "$rel" "$sha" "$sz" "COPY_HASH_MISMATCH" >> "$MAN"
      fi
    else
      LOCKED=$((LOCKED+1)); LOCKED_FILES+=("$name|$src|$st|$rel")
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$st" "$rel" "$sha" "$sz" "COPY_FAILED_LOCKED" >> "$MAN"
    fi
  done < <(git -C "$root" status --porcelain=v1 -uall -z 2>/dev/null)
}

scan_worktree main "$REPO"
scan_worktree m3-jdk8fix "$WT3/m3-jdk8fix"
scan_worktree f88-baseline-wt "$WT3/.f88-baseline-wt"

echo
echo "### retry locked files (other lanes may have released them)"
for i in 1 2 3; do
  [ ${#LOCKED_FILES[@]} -eq 0 ] && break
  echo "--- retry pass $i ---"; sleep 4
  local_retry=()
  for entry in "${LOCKED_FILES[@]}"; do
    IFS='|' read -r nm src st rel <<< "$entry"
    sha=$(sha256sum "$src" 2>/dev/null | cut -d' ' -f1)
    if [ -n "$sha" ]; then
      dst="$BK2/$nm/$rel"; mkdir -p "$(dirname "$dst")"
      if cp -p "$src" "$dst" 2>/dev/null; then
        sha2=$(sha256sum "$dst" | cut -d' ' -f1)
        sz=$(stat -c%s "$src")
        if [ "$sha" = "$sha2" ]; then
          VERIFIED=$((VERIFIED+1)); BYTES=$((BYTES+sz))
          sed -i "s|^\($nm\t$st\t$rel\t\)UNREADABLE\t0\tSRC_UNREADABLE_LOCKED$|\1$sha\t$sz\tBACKED_UP:VERIFIED_RETRY|" "$MAN" 2>/dev/null
          echo "  RETRY_OK: $nm/$rel"
          continue
        fi
      fi
    fi
    local_retry+=("$entry")
    echo "  still locked: $nm/$rel"
  done
  LOCKED_FILES=("${local_retry[@]}")
done

echo
echo "### secret-path exclusion hits (presence only, content NEVER copied)"
if [ ${#SECRET_FILES[@]} -eq 0 ]; then echo "  (none)"; else printf '  %s\n' "${SECRET_FILES[@]}"; fi

echo
echo "================ SNAPSHOT-2 SUMMARY ================"
echo "entries scanned : $TOTAL"
echo "VERIFIED        : $VERIFIED"
echo "still locked    : ${#LOCKED_FILES[@]}"
echo "secret-excluded : $SECRETS"
echo "bytes           : $BYTES"
echo "backup tree     : $BK2"
echo "--- file counts on disk ---"
echo "main          : $(find "$BK2/main" -type f 2>/dev/null | wc -l)"
echo "m3-jdk8fix    : $(find "$BK2/m3-jdk8fix" -type f 2>/dev/null | wc -l)"
echo "f88-baseline  : $(find "$BK2/f88-baseline-wt" -type f 2>/dev/null | wc -l)"
echo "total         : $(find "$BK2" -type f 2>/dev/null | wc -l)"
echo "size          : $(du -sh "$BK2" 2>/dev/null | cut -f1)"
echo "--- verdict tally in manifest ---"
tail -n +4 "$MAN" | awk -F'\t' '{print $6}' | sort | uniq -c | sort -rn
echo "--- manifest ---"; wc -l "$MAN"
echo "### DONE rc=0"
