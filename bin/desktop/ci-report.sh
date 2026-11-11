#!/usr/bin/env bash
# Helpers for the desktop CI workflow.
#
# Downloading workflow logs requires an authenticated token, while check run
# annotations are readable anonymously. These helpers therefore turn the output of
# a failed Gradle/jpackage run into `::error::` annotations, so a broken packaging
# step can be diagnosed from the public GitHub API.
#
# Usage (inside a workflow step):
#   source bin/desktop/ci-report.sh
#   if ! ./gradlew :desktop:app:createDistributable --console=plain > /tmp/x.log 2>&1; then
#     report_gradle_failure "createDistributable" /tmp/x.log
#     dump_jpackage_logs desktop/app/build/compose/logs
#     dump_jpackage_args desktop/app/build/compose/tmp/createDistributable.args.txt
#     exit 1
#   fi

# Prints the interesting lines of a failed Gradle run as annotations.
report_gradle_failure() {
  local label="$1" log="$2"
  echo "::error::$label failed"
  [ -f "$log" ] || return 0
  grep -aE "What went wrong|Execution failed|Caused by|^> |FAILURE|error:|Error:|Exception" "$log" \
    | head -12 | while IFS= read -r line; do
      echo "::error::${line//$'\r'/}"
    done
}

# Dumps every jpackage stdout/stderr file below <root> (one level per task).
dump_jpackage_logs() {
  local root="${1:-desktop/app/build/compose/logs}"
  local f
  for f in "$root"/*/*-err.txt "$root"/*/*-out.txt; do
    [ -f "$f" ] || continue
    local tag
    tag="$(basename "$(dirname "$f")")/$(basename "$f")"
    while IFS= read -r line; do
      echo "::error::[$tag] $line"
    done < <(head -25 "$f")
  done
}

# Dumps a generated jpackage @args file, one argument per annotation.
dump_jpackage_args() {
  local file="$1"
  [ -f "$file" ] || return 0
  while IFS= read -r line; do
    echo "::error::jpackage-arg $line"
  done < <(tr -s ' ' '\n' < "$file" | grep -v '^$' | head -60)
}

# Surfaces JUnit XML failures, JVM hs_err crash logs, and log tails as annotations.
report_test_failures() {
  local label="$1" log="$2"
  report_gradle_failure "$label" "$log"
  if command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY' || true
import glob, xml.etree.ElementTree as ET
for path in sorted(glob.glob("desktop/*/build/test-results/test/*.xml")):
    try:
        root = ET.parse(path).getroot()
    except Exception:
        continue
    for tc in root.findall("testcase"):
        for tag in ("failure", "error"):
            el = tc.find(tag)
            if el is not None:
                cls = tc.attrib.get("classname", "")
                name = tc.attrib.get("name", "")
                msg = (el.attrib.get("message") or "").replace("\r", "").replace("\n", " ")
                print(f"::error::[JUnit {cls}.{name}] {msg}")
                body = (el.text or "").replace("\r", "").splitlines()
                for line in body[:12]:
                    print(f"::error::  {line}")
PY
  fi
  local hs
  for hs in hs_err_pid*.log desktop/*/hs_err_pid*.log; do
    [ -f "$hs" ] || continue
    while IFS= read -r line; do
      echo "::error::[hs_err] ${line//$'\r'/}"
    done < <(head -35 "$hs")
  done
  if [ -f "$log" ]; then
    while IFS= read -r line; do
      echo "::error::[tail] ${line//$'\r'/}"
    done < <(tail -20 "$log")
  fi
}
