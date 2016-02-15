#!/bin/bash

set -e

# Publish to BinTray if the HEAD commit is tagged with a version number.
if [ "$PULL_REQUEST_NUMBER" ]; then
  echo "Semaphore is building a pull request, not publishing."
  echo "PULL_REQUEST_NUMBER is equal to $PULL_REQUEST_NUMBER"
  exit 0
fi

if [ "$BRANCH_NAME" != "master" ]; then
  echo "Semaphore is building on branch $BRANCH_NAME, not publishing."
  echo "BRANCH_NAME is equal to $BRANCH_NAME"
  exit 0
fi

numParents=`git log --pretty=%P -n 1 | wc -w | xargs`
if [ $numParents -ne 2 ]; then
  echo "$numParents parent commits of HEAD when exactly 2 expected, not publishing."
  exit 0
fi

# One build is run for the merge to master, so we need to list all tags from the merged commits.
firstMergedCommit=`git rev-list HEAD^2 --not HEAD^1 | tail -n 1`
echo "First merged commit: $firstMergedCommit"

tags=$(git tag --contains $firstMergedCommit)

if [ `echo "$tags" | wc -l` -eq 0 ]; then
  echo "No tags found in merged commits, not publishing."
  exit 0
fi

if [ `echo "$tags" | wc -l` -gt 1 ]; then
  echo "Multiple tags found in merged commits, not publishing."
  echo "$tags"
  exit 0
fi

tag=$tags

echo "Merged commits contain tag: $tag"

if [[ $tag =~ ^v[0-9]+\..* ]]; then
  echo "Going to release from tag $tag"
  version=$(echo $tag | sed -e s/^v//)

  git checkout $tag
  sbt publish
  echo "Successfully published artifact."

  exit 0
fi
