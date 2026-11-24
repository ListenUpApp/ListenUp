# Pre-baked Linux CI runner image for ListenUp.
#
# Bundles the system dependencies, compilers, and browser binaries that otherwise get apt-installed
# or compiled on every CI run (Temurin JDK 21, Node 24, pnpm, FFmpeg, GStreamer + Fraunhofer FDK
# fdkaacdec, native server link libraries, and Playwright Chromium + OS deps).

FROM eclipse-temurin:21-jdk AS jdk

FROM node:24-slim AS node

FROM ubuntu:26.04 AS gst-builder
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        xz-utils \
        pkg-config \
        build-essential \
        gstreamer1.0-tools \
        libgstreamer1.0-dev \
        libgstreamer-plugins-base1.0-dev \
        meson \
        ninja-build \
        autoconf \
        automake \
        libtool \
    && rm -rf /var/lib/apt/lists/*
COPY tools/scripts/build-gst-fdkaac.sh /tmp/build-gst-fdkaac.sh
RUN bash /tmp/build-gst-fdkaac.sh /opt/listenup/gst-plugins

FROM ubuntu:26.04
LABEL org.opencontainers.image.source="https://github.com/ListenUpApp/ListenUp"
LABEL org.opencontainers.image.description="Pre-baked Linux CI runner image for ListenUp (JDK 21, Node 24, pnpm, FFmpeg, GStreamer+fdkaacdec, native server link libs, Playwright Chromium)"

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_HOME=/opt/java/openjdk \
    GST_PLUGIN_PATH=/opt/listenup/gst-plugins \
    PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright \
    PATH="/opt/java/openjdk/bin:/usr/local/bin:${PATH}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        unzip \
        xz-utils \
        python3 \
        pkg-config \
        gcc \
        libc6-dev \
        ffmpeg \
        gstreamer1.0-tools \
        gstreamer1.0-plugins-base \
        gstreamer1.0-plugins-good \
        libargon2-dev \
        libsqlite3-dev \
        libcurl4-openssl-dev

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -sf ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -sf ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
COPY --from=gst-builder /opt/listenup/gst-plugins /opt/listenup/gst-plugins

COPY app/webApp/web/package.json /tmp/web-package.json
RUN pnpm_ver="$(node -e "const p=require('/tmp/web-package.json'); console.log(p.packageManager.split('@')[1])")" \
    && pw_ver="$(node -e "const p=require('/tmp/web-package.json'); console.log(p.devDependencies.playwright.replace(/^[^0-9]*/, ''))")" \
    && npm install -g "pnpm@${pnpm_ver}" "playwright@${pw_ver}" \
    && playwright install --with-deps chromium \
    && npm uninstall -g playwright \
    && npm cache clean --force \
    && rm -rf /tmp/web-package.json /root/.npm /var/lib/apt/lists/* \
    && chmod -R a+rX /opt/ms-playwright /opt/listenup/gst-plugins

RUN java -version \
    && node -v \
    && pnpm -v \
    && ffmpeg -version | head -1 \
    && ffprobe -version | head -1 \
    && gst-inspect-1.0 fdkaacdec | head -3 \
    && gst-inspect-1.0 matroskademux | head -3 \
    && ldd /opt/listenup/gst-plugins/libgstfdkaac.so | grep /opt/listenup/gst-plugins/fdk-aac/lib/libfdk-aac \
    && pkg-config --modversion libargon2 sqlite3 libcurl
