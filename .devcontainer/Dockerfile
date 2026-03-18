FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# Remove stale Yarn apt source (expired GPG key in base image)
# and install Node.js 20 via NodeSource so Layer 2 (dc-build) skips its broken install
RUN rm -f /etc/apt/sources.list.d/yarn.list \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
       | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
       > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update -qq && apt-get install -y -qq nodejs socat \
    && rm -rf /var/lib/apt/lists/*

# Android SDK environment variables
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools
# Connect to host's adb server for USB device access (macOS Docker Desktop)
ENV ADB_SERVER_SOCKET=tcp:host.docker.internal:5037

# Install Android SDK command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip

# Accept licenses and install required SDK components
RUN yes | sdkmanager --licenses > /dev/null 2>&1 \
    && sdkmanager --install \
        "platforms;android-36" \
        "build-tools;36.0.0" \
        "platform-tools"

# Pre-warm the Gradle wrapper so first build is faster
COPY gradle/wrapper /tmp/gradle-wrapper/gradle/wrapper
COPY gradlew /tmp/gradle-wrapper/gradlew
RUN cd /tmp/gradle-wrapper && chmod +x gradlew && ./gradlew --version && rm -rf /tmp/gradle-wrapper
