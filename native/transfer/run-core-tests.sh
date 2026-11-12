#!/bin/sh
set -eu
task_source=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "$#" -ne 2 ]; then
  echo "Usage: run-core-tests.sh PINNED_BORINGSSL_SOURCE BUILD_DIRECTORY" >&2
  exit 2
fi
sh "$task_source/configure-poc.sh" "$1" "$2"
cmake --build "$2" --target d2fa_core_test --parallel 2
ctest --test-dir "$2" --output-on-failure
