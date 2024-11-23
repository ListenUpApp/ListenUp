#!/bin/bash
set -e
echo "Starting post-create setup..."

echo "Setting up Go environment..."
export GOPATH=/go
export PATH=$PATH:$GOPATH/bin

echo "Installing Air..."
go install github.com/air-verse/air@latest || { echo "Air installation failed"; exit 1; }

echo "Installing Templ..."
go install github.com/a-h/templ/cmd/templ@latest || { echo "Templ installation failed"; exit 1; }

echo "Ensuring correct permissions..."
sudo chown -R vscode:vscode /workspace/node_modules

echo "Running npm install..."
if [ -f "package.json" ]; then
    npm install || { echo "npm install failed"; exit 1; }
else
    echo "No package.json found, initializing new npm project..."
    npm init -y
    # Install common development dependencies
    npm install --save-dev tailwindcss postcss autoprefixer
    npx tailwindcss init -p
fi

echo "Verifying installations..."
which air || echo "Air not found in PATH"
which templ || echo "Templ not found in PATH"
node --version || echo "Node not found in PATH"
npm --version || echo "npm not found in PATH"

echo "Setup complete!"