FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# Android SDK environment variables
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

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
