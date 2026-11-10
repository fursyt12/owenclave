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
