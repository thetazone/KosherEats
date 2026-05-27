#!/bin/bash
set -e

echo "=== KosherEats CI: post-clone ==="

# Install xcodegen if needed (for project generation)
if ! command -v xcodegen &> /dev/null; then
    echo "Installing xcodegen..."
    brew install xcodegen
fi

# Generate Xcode project from project.yml
cd "$CI_PRIMARY_REPOSITORY_PATH/ios/consumer"
xcodegen generate
echo "Xcode project generated."
