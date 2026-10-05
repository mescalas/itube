#!/bin/bash
# Installs the Android SDK and warms the Gradle cache so that Claude Code cloud
# sessions can compile the app (./gradlew :app:compileDebugKotlin) before pushing.
# Everything Android (SDK, Android Gradle Plugin, AndroidX) is served from
# dl.google.com: that host must be allowed in the environment's network settings.
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

SDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}"
PACKAGES=("platforms;android-35" "build-tools;35.0.0")
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"

warn() { echo "[session-start] $*" >&2; }

REPO_XML=$(curl -sf --max-time 20 https://dl.google.com/android/repository/repository2-3.xml)
if [ -z "$REPO_XML" ]; then
  warn "dl.google.com is unreachable: the Android build cannot run in this session."
  warn "Allow dl.google.com in the environment's network settings, or rely on GitHub Actions (see CLAUDE.md)."
  exit 0
fi

if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  CMDLINE_TOOLS_ZIP=$(grep -o 'commandlinetools-linux-[0-9]*_latest\.zip' <<< "$REPO_XML" | sort -t- -k3 -n | tail -1)
  CMDLINE_TOOLS_ZIP="${CMDLINE_TOOLS_ZIP:-commandlinetools-linux-13114758_latest.zip}"
  tmp=$(mktemp -d)
  curl -sfL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" \
    && unzip -q "$tmp/tools.zip" -d "$tmp" \
    && mkdir -p "$SDK_DIR/cmdline-tools" \
    && rm -rf "$SDK_DIR/cmdline-tools/latest" \
    && mv "$tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest" \
    || { warn "Failed to install Android cmdline-tools"; rm -rf "$tmp"; exit 0; }
  rm -rf "$tmp"
fi

sdkmanager="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
yes | "$sdkmanager" --sdk_root="$SDK_DIR" --licenses > /dev/null 2>&1 || true
"$sdkmanager" --sdk_root="$SDK_DIR" --install "${PACKAGES[@]}" > /dev/null \
  || { warn "sdkmanager failed to install ${PACKAGES[*]}"; exit 0; }

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=\"$SDK_DIR\"" >> "$CLAUDE_ENV_FILE"
  echo "export ANDROID_SDK_ROOT=\"$SDK_DIR\"" >> "$CLAUDE_ENV_FILE"
fi
echo "sdk.dir=$SDK_DIR" > "$PROJECT_DIR/local.properties"

# Download the Gradle distribution, plugins and dependencies once; the container
# snapshot taken after this hook keeps them for later sessions.
cd "$PROJECT_DIR" && ANDROID_HOME="$SDK_DIR" ./gradlew --no-daemon -q :app:dependencies > /dev/null \
  || warn "Gradle dependency warm-up failed; the first build will download them."

exit 0
