FROM mcr.microsoft.com/devcontainers/go:latest

# Install build essentials and curl
RUN apt-get update && apt-get install -y \
    curl \
    gcc \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install tailwindcss
RUN curl -sLO https://github.com/tailwindlabs/tailwindcss/releases/latest/download/tailwindcss-linux-x64 \
    && chmod +x tailwindcss-linux-x64 \
    && mv tailwindcss-linux-x64 /usr/local/bin/tailwindcss

# Set up Go environment
ENV GOPATH=/go
ENV PATH=$PATH:$GOPATH/bin

# Create and set permissions for Go directories
RUN mkdir -p /go/bin /go/pkg /go/src && \
    chown -R vscode:vscode /go

# Create workspace and audiobooks directories
RUN mkdir -p /workspace/audiobooks && \
    mkdir -p /workspace/node_modules && \
    chown -R vscode:vscode /workspace