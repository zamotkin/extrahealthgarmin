# 🔍 Отладка Extra Health Garmin в Android Studio

> Актуально для v3 (единый Compose-интерфейс, источники кровяного давления).
> Предыдущая версия руководства относилась к CSV-конвертеру v1 и удалена вместе с этим потоком.

## 📋 Предварительные требования

### 1. Android Studio
- Установите Android Studio Ladybug или новее
- Убедитесь, что SDK установлен (API 36)

### 2. Эмулятор или устройство
- Создайте AVD (Android Virtual Device) в Android Studio
- Или подключите физическое Android-устройство с включенной отладкой
  (для Bluetooth-источников нужен реальный телефон — у эмулятора нет BLE)

### 3. Debug APK
```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## 🚀 Настройка отладки

### Шаг 1: Открыть проект в Android Studio
```bash
# File → Open → выберите папку проекта
```

### Шаг 2: Собрать и запустить
```bash
# Сборка + установка + запуск на подключенном устройстве
./debug_setup.sh

# Или вручную
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.zamotkin.extrahealthgarmin/.MainActivity
```

### Шаг 3: Мониторинг логов

#### В Android Studio (Logcat)
1. Откройте вкладку **Logcat**
2. Фильтр: `package:com.zamotkin.extrahealthgarmin`
3. Каждому классу соответствует свой TAG (см. `companion object`)

#### В терминале
```bash
# Основные TAGи синхронизации взвешиваний
adb logcat -s MainActivity MainViewModel SyncWorker

# Garmin-аутентификация и HTTP
adb logcat -s MainActivity GarminAuth GarminHttp
```

## 🗺️ Где что искать в приложении

Приложение — одно activity на Jetpack Compose с нижней навигацией
(Home / Measurements / Settings):

| Экран / поток | Путь в UI | Код |
| :--- | :--- | :--- |
| Синхронизация взвешиваний | Home → **Sync now** | `ui/screens/home`, `health/SyncEngine` |
| Фоновая синхронизация (~3 ч) | автоматическая | `health/SyncWorker` |
| Вход в Garmin (email + MFA) | Settings → Garmin Connect | `garmin/GarminAuthClient`, `ui/navigation` |
| Разрешения Health Connect | Settings → Health Connect | `health/HealthPermissions` |
| Добавление источника АД | Settings → Connections → **Add source** | `ui/screens/devices`, `devices/` |
| Чтение показаний | Measurements → **Read now** | `devices/SourceReadUseCase`, `omron/ble` |
| Настройки источника (режим, пользователь, удаление) | Settings → Connections → источник | `devices/store/ConnectedSourceStore` |

## 🔍 Возможные проблемы и решения

### Проблема 1: Источник Omron не читается
**Решение:**
- Монитор должен быть разбужен: сделайте измерение или нажмите кнопку на корпусе
- M7 Intelli IT сопрягается с одним приложением: отвяжите его от Omron Connect
- Смотрите логи BLE: `adb logcat -s OmronGattConnection OmronBleClient`
- Протокол обмена описан в [omron-protocol.md](omron-protocol.md)

### Проблема 2: Стандартный BLE-монитор (0x1810) не находится
**Решение:**
- Первый read проходит через системное Bluetooth-окно Android (bonding) — подтвердите его
- Профиль и проверенные семейства устройств: [ble-1810-protocol.md](ble-1810-protocol.md)

### Проблема 3: Загрузка в Garmin не проходит
**Решение:**
- Settings → Garmin Connect: проверьте статус аккаунта; при необходимости войдите заново
- Debug-сборки логируют HTTP с вырезанными секретами (`RedactingHttpLogger`) — смотрите `GarminHttp`
- Неудачные записи остаются в приложении и повторяются при следующем проходе

### Проблема 4: Health Connect не отдает данные
**Решение:**
- Settings → Health Connect → **Manage access**: проверьте разрешения чтения
- Фоновое чтение на некоторых провайдерах недоступно — синхронизируйте вручную
- Первый запуск делает backfill за 30 дней; дальше — только новые записи

## 🧪 Отладка Garmin-аутентификации (только debug-сборки)

Debug-сборки содержат скрытый триггер «спайка» — один живой вход DI OAuth2 с отчетом в logcat:
положите `GARMINSPIKE_EMAIL` / `GARMINSPIKE_PASSWORD` / `GARMINSPIKE_MFA_CODE` в
`~/.gradle/gradle.properties`, соберите debug-APK и сделайте **долгое нажатие на строку статуса
Garmin Connect** в Settings. Наблюдайте `adb logcat -s MainActivity GarminAuth GarminHttp`.
В release-сборках триггера нет.

## 📞 Полезные команды

```bash
# Переустановить приложение
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Очистить данные приложения
adb shell pm clear com.zamotkin.extrahealthgarmin

# Перезапустить приложение
adb shell am force-stop com.zamotkin.extrahealthgarmin
adb shell am start -n com.zamotkin.extrahealthgarmin/.MainActivity

# Получить информацию о приложении
adb shell dumpsys package com.zamotkin.extrahealthgarmin

# Запустить тесты
./gradlew testDebugUnitTest

# Проверка lint
./gradlew lintDebug
```
