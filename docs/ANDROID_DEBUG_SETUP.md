# 🔧 Настройка отладки Android для Extra Health Garmin

## ⚠️ Проблема: ADB не найден

Система сообщает, что `adb` (Android Debug Bridge) не найден. Это означает, что Android SDK не установлен или не настроен PATH.

## 🛠️ Решение: Установка Android SDK

### Вариант 1: Через Android Studio (Рекомендуется)

1. **Установите Android Studio**
   - Скачайте с [developer.android.com/studio](https://developer.android.com/studio)
   - Установите Android Studio Arctic Fox или новее

2. **Установите Android SDK**
   - В Android Studio: **Tools → SDK Manager**
   - Установите:
     - ✅ Android SDK Platform (API 30+)
     - ✅ Android SDK Build-Tools
     - ✅ Android SDK Platform-Tools (содержит ADB)

3. **Проверьте установку**
   ```bash
   # В терминале Android Studio или после настройки PATH
   adb version
   # Должно показать версию ADB
   ```

### Вариант 2: Ручная установка Android SDK

1. **Скачайте SDK**
   - Скачайте Command Line Tools: [developer.android.com/studio/command-line](https://developer.android.com/studio/command-line)

2. **Распакуйте и настройте**
   ```bash
   # Создайте папку
   mkdir -p ~/Android/Sdk

   # Распакуйте архив в ~/Android/Sdk/cmdline-tools/latest/

   # Настройте PATH в ~/.zshrc или ~/.bash_profile
   export ANDROID_HOME=~/Android/Sdk
   export PATH=$PATH:$ANDROID_HOME/platform-tools
   export PATH=$PATH:$ANDROID_HOME/tools
   export PATH=$PATH:$ANDROID_HOME/tools/bin
   ```

3. **Установите платформы**
   ```bash
   # Установите platform tools
   sdkmanager "platform-tools"

   # Установите build tools
   sdkmanager "build-tools;34.0.0"

   # Установите платформу (compileSdk 36)
   sdkmanager "platforms;android-36"
   ```

## 🚀 После установки SDK

### 1. Проверьте ADB
```bash
adb version
# Должно показать: Android Debug Bridge version 1.0.41
```

### 2. Настройте эмулятор
1. В Android Studio: **Tools → Device Manager**
2. **Create Device**
   - Выберите **Pixel 4** или аналогичный
   - **System Image**: API 34 (Android 14) или новее
   - **Storage**: 2GB+
3. **Запустите эмулятор**

### 3. Запустите отладку
```bash
# В папке проекта
./debug_setup.sh
```

## 🔍 Альтернативные варианты отладки

### Вариант 1: Физическое устройство
1. Включите **USB Debugging** на устройстве
   - **Settings → Developer Options → USB Debugging**
2. Подключите устройство по USB
3. Разрешите отладку на устройстве

### Вариант 2: Android Studio без ADB
1. Откройте проект в Android Studio
2. **Run → Edit Configurations**
3. Создайте **Android App** конфигурацию
4. Выберите модуль **app**
5. **Run → Run 'app'** (Android Studio сам запустит эмулятор)

## 📱 Тестирование без ADB

Если ADB не доступен, можно протестировать через Android Studio:

1. **Откройте проект** в Android Studio
2. **Создайте эмулятор** через Device Manager
3. **Запустите приложение**: Run → Run 'app'
4. **Тестируйте вручную**:
   - Home → **Sync now** — синхронизация взвешиваний
   - Settings → Connections → **Add source** — добавление источника кровяного давления
   - Measurements → **Read now** — чтение показаний тонометра

## 🐛 Возможные проблемы

### Проблема: "No connected devices"
```
adb devices
# Показывает только "List of devices attached"
```

**Решение:**
- Убедитесь, что эмулятор запущен
- Или устройство подключено с включенной отладкой
- Проверьте: `adb kill-server && adb start-server`

### Проблема: "Permission denied"
**Решение:**
- На устройстве: Settings → Developer Options → USB Debugging
- Разрешите отладку при подключении

### Проблема: Эмулятор не запускается
**Решение:**
- Проверьте виртуализацию в BIOS
- Увеличьте RAM для эмулятора
- Попробуйте другой system image

## 📞 Следующие шаги

После настройки ADB:

1. **Запустите**: `./debug_setup.sh`
2. **Откройте Android Studio**
3. **Установите breakpoints** в коде экранов (`ui/screens`) или движка синхронизации (`health/SyncEngine`)
4. **Запустите отладку**: Run → Debug 'app'
5. **Тестируйте потоки**: синхронизация взвешиваний (Home), источник кровяного давления (Settings → Connections), чтение показаний (Measurements)
6. **Анализируйте логи** в Logcat

## 📋 Быстрая проверка

```bash
# Проверить ADB
which adb
adb version

# Проверить устройства
adb devices

# Проверить установку APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Проверить логи
adb logcat -s MainActivity MainViewModel SyncWorker
```