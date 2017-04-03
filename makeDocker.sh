#!/bin/bash

set -x
set -e

VERSION=`cat version.sbt | sed -Ee "s/version in [A-Za-z]+ := \"([0-9.]+(-SNAPSHOT)?)\"/\1/"`
find . -type d -name target | xargs rm -r
sbt server/assembly
docker build -t science-parse --build-arg SP_VERSION=$VERSION .
