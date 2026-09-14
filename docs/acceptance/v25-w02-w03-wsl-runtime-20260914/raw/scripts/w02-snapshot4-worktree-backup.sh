#!/usr/bin/env bash
# V25-W02 SNAPSHOT-4 (FINAL, corrected) | backs up uncommitted work in the secondary worktrees,
# which snapshots 1-3 MISSED:
#   - snapshot-1 relied on `git worktree list` labels -> only caught m3-jdk8fix 4 files by luck of path
#   - snapshot-2/3 scanned `<repo>/.git/worktrees/<name>` = git ADMIN dirs, not work trees -> 0 entries
# `git worktree list` reports the admin dir because both worktrees are marked `prunable`
# (their .git pointer files store Windows paths, unusable when git runs inside WSL).
# Workaround that is READ-ONLY and writes nothing into any repo:
#   git --git-dir=<admin> --work-tree=<real root> status
# Additive: snapshot-1/2/3 untouched.
set -u
MAIN=/mnt/d/Develop_code/GraduationProject
BK4=/mnt/d/Develop/backup/v25-w02-20260914-snap4-worktrees
EV=$MAIN/docs/acceptance/v25-w02-w03-wsl-runtime-20260914/raw
MAN=$EV/65-w02-backup-manifest-snap4-worktrees-sha256.txt
SECRET_RE='(\.env$|\.env\.|credentials|secret|id_rsa|id_ed25519|\.pem$|\.p12$|\.jks$|\.keystore$|keystore|\.npmrc$|\.pypirc$|\.netrc$|settings\.xml$|\.mvn/|token|password|passwd|\.git-credentials)'
GITDIR="$MAIN/.git/worktrees/m3-jdk8fix"
WT="$MAIN/../GraduationProject-wt/m3-jdk8fix"

mkdir -p "$BK4"
: > "$MAN"
{
  echo "# V25-W02 SNAPSHOT-4 backup manifest (sha256) — generated $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "# Covers the secondary worktrees that snapshots 1-3 missed. ADDITIVE."
  echo "# method: git --git-dir=<admin> --work-tree=<real> status (read-only; no git writes)"
  echo "# columns: label<TAB>git-status<TAB>rel-path<TAB>sha256<TAB>bytes<TAB>verdict"
} >> "$MAN"

echo "=== [1] m3-jdk8fix: full uncommitted inventory via explicit git-dir/--work-tree ==="
echo "git-dir : $GITDIR"
echo "worktree: $WT"
echo "HEAD    : $(cat "$GITDIR/HEAD")"
echo
echo "--- status code tally ---"
timeout 240 git --git-dir="$GITDIR" --work-tree="$WT" status --porcelain=v1 -uall -z 2>/dev/null \
  | tr '\0' '\n' | awk 'NF{print substr($0,1,2)}' | sort | uniq -c | sort -rn

TOTAL=0; VERIFIED=0; SECRETS=0; BYTES=0; SKIPPED_BUILD=0
declare -a LOCKED=()

backup_file() {
  local label="$1" rel="$2" st="$3" root="$4"
  local src="$root/$rel" dst="$BK4/$label/$rel"
  [ -f "$src" ] || { printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$st" "$rel" "N/A" "0" "SRC_GONE_OR_DIR" >> "$MAN"; return; }
  # skip pure build output to keep the archive source-only (rebuildable, not "lost work")
  case "$rel" in
    */target/*|target/*|*.class|*.jar|*.war)
      SKIPPED_BUILD=$((SKIPPED_BUILD+1))
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$st" "$rel" "SKIPPED" "0" "BUILD_OUTPUT_NOT_BACKED_UP" >> "$MAN"; return ;;
  esac
  if printf '%s' "$rel" | grep -Eqi "$SECRET_RE"; then
    SECRETS=$((SECRETS+1))
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$st" "$rel" "REDACTED" "0" "SECRET_EXCLUDED_PRESENCE_ONLY" >> "$MAN"; return
  fi
  local sha; sha=$(sha256sum "$src" 2>/dev/null | cut -d' ' -f1)
  if [ -z "$sha" ]; then
    LOCKED+=("$label|$src|$st|$rel|$root")
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$st" "$rel" "UNREADABLE" "0" "SRC_UNREADABLE_LOCKED" >> "$MAN"; return
  fi
  local sz; sz=$(stat -c%s "$src")
  mkdir -p "$(dirname "$dst")"
  if cp -p "$src" "$dst" 2>/dev/null && [ "$sha" = "$(sha256sum "$dst" | cut -d' ' -f1)" ]; then
    VERIFIED=$((VERIFIED+1)); BYTES=$((BYTES+sz))
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$st" "$rel" "$sha" "$sz" "BACKED_UP:VERIFIED" >> "$MAN"
  else
    LOCKED+=("$label|$src|$st|$rel|$root")
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$st" "$rel" "$sha" "$sz" "COPY_FAILED_LOCKED" >> "$MAN"
  fi
}

echo
echo "=== [2] backing up m3-jdk8fix uncommitted files ==="
while IFS= read -r -d '' e; do
  [ ${#e} -lt 4 ] && continue
  st="${e:0:2}"; rel="${e:3}"
  [ -z "$rel" ] && continue
  TOTAL=$((TOTAL+1))
  backup_file "m3-jdk8fix" "$rel" "$st" "$WT"
done < <(timeout 240 git --git-dir="$GITDIR" --work-tree="$WT" status --porcelain=v1 -uall -z 2>/dev/null)

F88GD="$MAIN/.git/worktrees/-f88-baseline-wt"
F88WT="/mnt/d/Develop/GraduationProject/.f88-baseline-wt"
echo
echo "=== [3] .f88-baseline-wt inventory (expected clean) ==="
echo "worktree: $F88WT   HEAD: $(cat "$F88GD/HEAD" 2>/dev/null)"
f88n=$(timeout 240 git --git-dir="$F88GD" --work-tree="$F88WT" status --porcelain=v1 -uall -z 2>/dev/null | tr '\0' '\n' | grep -c . || true)
echo "entries: $f88n  (0 = clean, nothing to back up)"
if [ "$f88n" != "0" ]; then
  while IFS= read -r -d '' e; do
    [ ${#e} -lt 4 ] && continue
    backup_file "f88-baseline-wt" "${e:3}" "${e:0:2}" "$F88WT"
  done < <(timeout 240 git --git-dir="$F88GD" --work-tree="$F88WT" status --porcelain=v1 -uall -z 2>/dev/null)
fi

echo
echo "### retry locked"
for i in 1 2 3; do
  [ ${#LOCKED[@]} -eq 0 ] && break
  echo "--- pass $i ---"; sleep 4; keep=()
  for entry in "${LOCKED[@]}"; do
    IFS='|' read -r lb src st rel root <<< "$entry"
    sha=$(sha256sum "$src" 2>/dev/null | cut -d' ' -f1)
    if [ -n "$sha" ]; then
      dst="$BK4/$lb/$rel"; mkdir -p "$(dirname "$dst")"
      if cp -p "$src" "$dst" 2>/dev/null && [ "$sha" = "$(sha256sum "$dst" | cut -d' ' -f1)" ]; then
        sz=$(stat -c%s "$src"); VERIFIED=$((VERIFIED+1)); BYTES=$((BYTES+sz))
        awk -v lb="$lb" -v st="$st" -v rel="$rel" -v sha="$sha" -v sz="$sz" -F'\t' -v OFS='\t' \
          '{ if ($1==lb && $2==st && $3==rel && $6=="SRC_UNREADABLE_LOCKED") {$4=sha;$5=sz;$6="BACKED_UP:VERIFIED_RETRY"} print }' "$MAN" > "$MAN.t" && mv "$MAN.t" "$MAN"
        echo "  RETRY_OK: $lb/$rel"; continue
      fi
    fi
    keep+=("$entry"); echo "  still locked: $lb/$rel"
  done
  LOCKED=("${keep[@]}")
done

echo
echo "=== [4] the 4 spark-jobs files: live vs snapshot-1 vs current HEAD ==="
for f in spark-jobs/pom.xml \
         spark-jobs/src/main/scala/com/graduation/analytics/job/MetricExportJob.scala \
         spark-jobs/src/test/scala/com/graduation/analytics/P2TestSupport.scala \
         spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala; do
  live="$WT/$f"; bk1="/mnt/d/Develop/backup/v25-w02-20260914/m3-jdk8fix/$f"; bk4="$BK4/m3-jdk8fix/$f"
  hl=$([ -f "$live" ] && sha256sum "$live" | cut -c1-16 || echo NONE)
  h1=$([ -f "$bk1" ] && sha256sum "$bk1" | cut -c1-16 || echo NONE)
  h4=$([ -f "$bk4" ] && sha256sum "$bk4" | cut -c1-16 || echo NONE)
  echo "  live=$hl  snap1=$h1  snap4=$h4  $([ "$hl" = "$h1" ] && echo SAME || echo DIFF)  $(basename "$f")"
done

echo
echo "================ SNAPSHOT-4 SUMMARY ================"
echo "entries scanned  : $TOTAL (+f88 entries)"
echo "VERIFIED         : $VERIFIED"
echo "still locked     : ${#LOCKED[@]}"
echo "secret-excluded  : $SECRETS"
echo "build-output skip: $SKIPPED_BUILD"
echo "bytes            : $BYTES"
echo "--- on disk ---"; for d in "$BK4"/*/; do printf '  %-20s %5s files %8s\n' "$(basename "$d")" "$(find "$d" -type f|wc -l)" "$(du -sh "$d"|cut -f1)"; done
echo "--- verdict tally ---"; tail -n +5 "$MAN" | awk -F'\t' '{print $6}' | sort | uniq -c | sort -rn
echo "--- manifest lines: $(wc -l < "$MAN")"
echo "### DONE rc=0"
