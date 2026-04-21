#!/bin/bash
set -e

echo "=== KosherEats Courier CI: post-clone ==="

if ! command -v xcodegen &> /dev/null; then
    echo "Installing xcodegen..."
    brew install xcodegen
fi

cd "$CI_PRIMARY_REPOSITORY_PATH/ios/courier"
xcodegen generate
echo "Xcode project generated."
