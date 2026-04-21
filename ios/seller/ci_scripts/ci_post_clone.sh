#!/bin/bash
set -e

echo "=== KosherEats Seller CI: post-clone ==="

if ! command -v xcodegen &> /dev/null; then
    echo "Installing xcodegen..."
    brew install xcodegen
fi

cd "$CI_PRIMARY_REPOSITORY_PATH/ios/seller"
xcodegen generate
echo "Xcode project generated."
