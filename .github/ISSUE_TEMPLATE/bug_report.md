name: 🐞 Bug report
description: Something is not working (sync, login, upload, UI)
title: "[Bug]: "
labels: ["bug"]
body:
  - type: markdown
    attributes:
      value: |
        Thanks for the report! Debug builds redact all secrets in logs
        (`Authorization` headers, password, MFA code, tokens → `<redacted>`), so a debug logcat is safe to attach.
  - type: input
    id: version
    attributes:
      label: App version
      description: "Shown on the main screen or in the release you installed (e.g. 2.0.0)"
      placeholder: "2.0.0"
    validations:
      required: true
  - type: input
    id: android_version
    attributes:
      label: Android version
      placeholder: "e.g. 15 (API 35)"
    validations:
      required: true
  - type: input
    id: device
    attributes:
      label: Device model
      placeholder: "e.g. Pixel 8"
    validations:
      required: false
  - type: dropdown
    id: bp_source
    attributes:
      label: Blood-pressure source (if involved)
      description: Which blood-pressure source type does this bug involve?
      options:
        - Not involved (weigh-in sync, Garmin sign-in or UI only)
        - Omron M7 Intelli IT (proprietary BLE)
        - Standard BLE Blood Pressure Service (0x1810) monitor
        - Health Connect
    validations:
      required: false
  - type: textarea
    id: steps
    attributes:
      label: Steps to reproduce
      placeholder: "1. ... 2. ... 3. ..."
    validations:
      required: true
  - type: textarea
    id: expected
    attributes:
      label: Expected behavior
    validations:
      required: true
  - type: textarea
    id: actual
    attributes:
      label: Actual behavior
      description: "What happened instead? Include any error text shown in the app."
    validations:
      required: true
  - type: textarea
    id: logs
    attributes:
      label: Logcat (debug builds only)
      description: "adb logcat -s MainActivity MainViewModel SyncWorker GarminAuth GarminHttp - secrets are already redacted by RedactingHttpLogger"
      render: shell
    validations:
      required: false
