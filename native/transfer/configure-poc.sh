#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then
  echo "Usage: configure-poc.sh PINNED_BORINGSSL_SOURCE BUILD_DIRECTORY" >&2
  exit 2
fi
task_source=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cmake -S "$task_source" -B "$2" -G Ninja \
  "-DD2FA_BORINGSSL_SOURCE=$1" -DD2FA_TEST_API=ON \
  -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release
