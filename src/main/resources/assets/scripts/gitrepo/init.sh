#!/usr/bin/env bash

#
# Copyright 2023 teddyxlandlee
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Arguments:
# - $1: timeout millis, stop join
# - $2: timeout millis, force termination
# - $3: url (file:/ schema) to version_manifest_v2.json
# - $4: url (file:/ schema) to vineflower.jar
# - $5: decompiler name
# - $6+: mappings name

# Working directory is right here
# Assume the repos are cloned
XDECOMPILER_PWD=$(pwd)
alias xdecompiler-run-raw="java -Dxdecompiler.download.vineflower=$4 -Dxdecompiler.download.mc.manifest=$3"\
"-jar XDecompiler-fat.jar --decompiler $5"
XDECOMPILER_TIMEOUT_SOFT=$1
XDECOMPILER_TIMEOUT_FORCE=$2

XDECOMPILER_MAPPINGS=()
for ((i=6; i<=$#; i++)); do
    XDECOMPILER_MAPPINGS+=("--mappings")
    XDECOMPILER_MAPPINGS+=("${!i}")
done

# Init
XDECOMPILER_INITIAL_DATE=$(date +%s%3N)
XDECOMPILER_TERMINATES=false

cd "${XDECOMPILER_PWD}/out/src"
git config user.name 'github-actions[bot]'
git config user.email 'github-actions[bot]@noreply.github.com'
cd "${XDECOMPILER_PWD}/out/resources"
git config user.name 'github-actions[bot]'
git config user.email 'github-actions[bot]@noreply.github.com'
cd "${XDECOMPILER_PWD}"

xdecompiler-checkout () {
  if "${XDECOMPILER_TERMINATES}" ; then return 1 ; fi

  # Arguments:
  # - $1: branch name

  cd "${XDECOMPILER_PWD}/out/src"
  git checkout -B $1
  cd "${XDECOMPILER_PWD}/out/resources"
  git checkout -B $1
  cd "${XDECOMPILER_PWD}"
}

xdecompiler-run () {
  if "${XDECOMPILER_TERMINATES}" ; then return 1 ; fi

  # Arguments:
  # - $1: version name

  # 0. Copy old files, into new directory
  mkdir ${XDECOMPILER_PWD}/out-tmp
  mkdir ${XDECOMPILER_PWD}/out-tmp/src
  mkdir ${XDECOMPILER_PWD}/out-tmp/resources
  cp -t ${XDECOMPILER_PWD}/out-tmp/src ${XDECOMPILER_PWD}/out/src/.git
  cp -t ${XDECOMPILER_PWD}/out-tmp/resources ${XDECOMPILER_PWD}/out/resources/.git
  #rm -rf ${XDECOMPILER_PWD}/out

  xdecompiler-run0() {
    # 1. Run main program, then add version stamp
      cd "${XDECOMPILER_PWD}"
      xdecompiler-run-raw --output-code "${XDECOMPILER_PWD}/out-tmp/src" \
                          --output-resources "${XDECOMPILER_PWD}/out-tmp/resources" \
                          "${XDECOMPILER_MAPPINGS[@]}" \
                          "$1"
      echo "$1" >> "${XDECOMPILER_PWD}/out-tmp/src/version.txt"

      # 2. Commit, tagging
      cd "${XDECOMPILER_PWD}/out-tmp/src"
      git add .
      git commit -m "$1"
      git tag "$1"
      cd "${XDECOMPILER_PWD}/out-tmp/resources"
      git add .
      git commit -m "$1"
      git tag "$1"
      cd "${XDECOMPILER_PWD}"
  }

  # Use force termination
  timeout $(echo "( ${XDECOMPILER_INITIAL_DATE} + ${XDECOMPILER_TIMEOUT_FORCE} - $(date +%s%3N) ) * 0.001" | bc) xdecompiler-run0 "$1"
  if [ "$?" == 124 ] ; then
    XDECOMPILER_TERMINATES=true
    return 1
  fi

  # 3. move back
  rm -rf "${XDECOMPILER_PWD}/out"
  mv -T "${XDECOMPILER_PWD}/out-tmp" "${XDECOMPILER_PWD}/out"

  # Use soft termination
  if [ $(echo "${XDECOMPILER_INITIAL_DATE} + ${XDECOMPILER_TIMEOUT_SOFT}" - $(date +%s%3N) | bc) -le 0 ] ; then
    XDECOMPILER_TERMINATES=true
    return 1
  fi
}

