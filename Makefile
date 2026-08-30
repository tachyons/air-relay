# Air Relay — build & test orchestration
#
# Android builds use the `android` CLI where applicable and Gradle for
# compilation; macOS builds use xcodebuild against the generated Xcode project.
#
# Common targets:
#   make build          Build both apps (debug)
#   make test           Run all unit tests
#   make android        Build Android debug APK
#   make macos          Build macOS app (Debug)
#   make run-macos      Build and launch the macOS app
#   make install-android  Build + install APK on the connected device (android CLI)
#   make release        Release builds for both platforms
#   make clean          Remove build artifacts

ANDROID_DIR := android
MACOS_DIR   := macos

# Prefer Android Studio's bundled JDK when present (Gradle-supported version).
STUDIO_JBR  := /Applications/Android Studio.app/Contents/jbr/Contents/Home
ifneq ($(wildcard $(STUDIO_JBR)),)
  export JAVA_HOME := $(STUDIO_JBR)
endif

GRADLE      := ./gradlew
ANDROID_CLI := android

DEVELOPER_DIR ?= /Applications/Xcode.app/Contents/Developer
XCODEBUILD  := DEVELOPER_DIR=$(DEVELOPER_DIR) xcodebuild
XCODEGEN    := xcodegen
XCPROJECT   := AirRelay.xcodeproj
XCSCHEME    := AirRelay
DERIVED     := .build/xcode

APK_DEBUG   := $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk
MAC_APP     := $(MACOS_DIR)/$(DERIVED)/Build/Products/Debug/AirRelay.app

.PHONY: build test release clean \
        android android-test android-lint android-release run-android \
        macos macos-test macos-release run-macos xcodeproj doctor

build: android macos

test: android-test macos-test

release: android-release macos-release

# ---------------------------------------------------------------- Android

android:
	cd $(ANDROID_DIR) && $(GRADLE) assembleDebug

android-test:
	cd $(ANDROID_DIR) && $(GRADLE) testDebugUnitTest

android-lint:
	cd $(ANDROID_DIR) && $(GRADLE) lintDebug

android-release:
	cd $(ANDROID_DIR) && $(GRADLE) assembleRelease

# Builds, deploys, and launches on the connected device via the android CLI.
run-android: android
	$(ANDROID_CLI) run --apks=$(APK_DEBUG) --activity=.MainActivity

# ------------------------------------------------------------------ macOS

# Regenerate the Xcode project from project.yml (needed after target/source changes).
xcodeproj:
	cd $(MACOS_DIR) && $(XCODEGEN) generate

$(MACOS_DIR)/$(XCPROJECT):
	cd $(MACOS_DIR) && $(XCODEGEN) generate

macos: $(MACOS_DIR)/$(XCPROJECT)
	cd $(MACOS_DIR) && $(XCODEBUILD) -project $(XCPROJECT) -scheme $(XCSCHEME) \
	  -configuration Debug -derivedDataPath $(DERIVED) build

macos-test:
	cd $(MACOS_DIR) && DEVELOPER_DIR=$(DEVELOPER_DIR) swift test --package-path AirRelayKit

macos-release: $(MACOS_DIR)/$(XCPROJECT)
	cd $(MACOS_DIR) && $(XCODEBUILD) -project $(XCPROJECT) -scheme $(XCSCHEME) \
	  -configuration Release -derivedDataPath $(DERIVED) build

run-macos: macos
	@if pgrep -fq "AirRelay.app.*DebugMode" ; then \
	  echo "AirRelay already running under the Xcode debugger; not launching a second copy."; \
	else \
	  pkill -x AirRelay 2>/dev/null || true; \
	  open $(MAC_APP); \
	fi

# ------------------------------------------------------------------- misc

doctor:
	@echo "android CLI: $$(command -v $(ANDROID_CLI) || echo MISSING)"
	@$(ANDROID_CLI) info 2>/dev/null | head -5 || true
	@echo "xcodegen:    $$(command -v $(XCODEGEN) || echo MISSING)"
	@echo "xcodebuild:  $$($(XCODEBUILD) -version 2>/dev/null | head -1 || echo 'MISSING (install Xcode)')"

clean:
	cd $(ANDROID_DIR) && $(GRADLE) clean
	rm -rf $(MACOS_DIR)/$(DERIVED) $(MACOS_DIR)/AirRelayKit/.build
