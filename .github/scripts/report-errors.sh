#!/usr/bin/env bash
# Turns compiler and Gradle failures in a build log into GitHub annotations, so errors are
# visible on the run summary page (and through the API) without downloading the full log.
log="${1:-gradle.log}"
[ -f "$log" ] || exit 0
grep -E '^e: |error:|What went wrong|^> [^T]|^> Task .*FAILED|Caused by|Could not resolve|Unresolved' "$log" \
  | sed -E 's#file:///home/runner/work/[^/]+/[^/]+/##' \
  | head -300 > errors.txt
total=$(wc -l < errors.txt)
echo "Found $total error lines"
# Annotations are limited per step, so pack ~20 lines into each.
split -l 20 errors.txt chunk_
n=0
for f in chunk_*; do
  n=$((n + 1))
  [ "$n" -gt 15 ] && break
  msg=$(sed ':a;N;$!ba;s/%/%25/g;s/\r//g;s/\n/%0A/g' "$f")
  echo "::error title=Build errors part $n::$msg"
done
