FROM ghcr.io/cirruslabs/android-sdk:35
ENV DEBIAN_FRONTEND=noninteractive ANDROID_HOME=/opt/android-sdk-linux ANDROID_SDK_ROOT=/opt/android-sdk-linux
WORKDIR /workspace
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip curl unzip osmium-tool && rm -rf /var/lib/apt/lists/*
RUN python3 -m pip install --break-system-packages --no-cache-dir pandas pyarrow shapely requests overturemaps==2.1.1
RUN yes | sdkmanager --licenses >/dev/null 2>&1 || true
RUN sdkmanager "platforms;android-35" "build-tools;35.0.0"
RUN curl -fsSL https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -o /tmp/gradle.zip && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip
ENV PATH="/opt/gradle-8.11.1/bin:${PATH}"
COPY . .
# Keep Gradle pointed at the SDK root provided by the Cirrus Labs base image.
RUN test -d "$ANDROID_HOME/platforms/android-35" && printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
RUN bash scripts/build_riyadh_database.sh
# Limit concurrent dependency requests to reduce pressure on public artifact repositories.
RUN gradle --no-daemon --max-workers=2 --build-cache :app:testDebugUnitTest :app:assembleDebug
RUN set -e; \
    mkdir -p /srv; \
    APK=app/build/outputs/apk/debug/app-debug.apk; \
    test -s "$APK"; \
    unzip -l "$APK" | grep -q 'assets/hamed_smart_delivery_riyadh.sqlite'; \
    "$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$APK" | grep -q "versionName='0.4.3'"; \
    "$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$APK" | grep -q "versionCode='8'"; \
    "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$APK" > /srv/apk-signature.txt 2>&1; \
    cp "$APK" /srv/hamed-smart-delivery-0.4.3-debug.apk; \
    cp data/output/riyadh-build-report.json /srv/riyadh-build-report.json; \
    APK_SIZE=$(stat -c '%s' "$APK"); \
    APK_SHA256=$(sha256sum "$APK" | cut -d' ' -f1); \
    printf '{"versionName":"0.4.3","versionCode":8,"variant":"debug","apkSizeBytes":%s,"apkSha256":"%s","riyadhDatabaseAsset":"verified","signature":"verified"}\n' "$APK_SIZE" "$APK_SHA256" > /srv/apk-build-report.json;
    cat /srv/apk-build-report.json
EXPOSE 10000
CMD ["python3", "/workspace/scripts/serve_apk.py"]
