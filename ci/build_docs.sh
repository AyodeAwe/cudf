#!/bin/bash
# Copyright (c) 2023, NVIDIA CORPORATION.

set -euo pipefail

rapids-logger "Create test conda environment"
. /opt/conda/etc/profile.d/conda.sh

rapids-dependency-file-generator \
  --output conda \
  --file_key docs \
  --matrix "cuda=${RAPIDS_CUDA_VERSION%.*};arch=$(arch);py=${RAPIDS_PY_VERSION}" | tee env.yaml

rapids-mamba-retry env create --force -f env.yaml -n test
conda activate test

rapids-print-env

rapids-logger "Downloading artifacts from previous jobs"
CPP_CHANNEL=$(rapids-download-conda-from-s3 cpp)
VERSION_NUMBER=$(rapids-get-rapids-version-from-git)

rapids-mamba-retry install \
  --channel "${CPP_CHANNEL}" \
  --channel "${PYTHON_CHANNEL}" \
  libcudf \
  cudf


# Build Doxygen docs
gpuci_logger "Build Doxygen docs"
pushd cpp/doxygen
doxygen Doxyfile
popd

# Build Python docs
gpuci_logger "Build Sphinx docs"
pushd docs/cudf
sphinx-build -b dirhtml source _html
sphinx-build -b text source _text
popd


if [[ ${RAPIDS_BUILD_TYPE} == "branch" ]]; then
  aws s3 sync --delete cpp/doxygen/html "s3://rapidsai-docs/cudf/${VERSION_NUMBER}/html"
  aws s3 sync --delete docs/cudf/_text "s3://rapidsai-docs/cudf/${VERSION_NUMBER}/txt"
  aws s3 sync --delete docs/cudf/_html "s3://rapidsai-docs/libcudf/${VERSION_NUMBER}/html"
fi
