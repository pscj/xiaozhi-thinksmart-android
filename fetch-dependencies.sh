#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p app/libs
curl -fL --retry 3 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar' -o app/libs/sherpa-onnx-2.0.1.aar
(cd app/libs && shasum -a 256 -c ../../sherpa-onnx.sha256)
