# Android Project Setup for Tefal2Garmin Sync Utility

> **Historical note:** this walkthrough creates the original v1 CSV-converter scaffold
> (`com.example.tefal2garmin`). The shipped app is `com.zamotkin.extrahealthgarmin`
> (see [CLAUDE.md](../CLAUDE.md)); kept as a record of the project's origin.

## Project Structure

```
tefal2garmin/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/tefal2garmin/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── utils/
│   │   │   │   │   ├── CsvParser.kt
│   │   │   │   │   ├── DateUtils.kt
│   │   │   │   │   └── FileUtils.kt
│   │   │   │   ├── data/
│   │   │   │   │   └── WeightRecord.kt
│   │   │   │   └── ui/
│   │   │   │       ├── MainActivity.kt
│   │   │   │       └── components/
│   │   │   │           └── ProgressIndicator.kt
│   │   │   └── res/
│   │   │       ├── layout/
│   │   │       │   └── activity_main.xml
│   │   │       ├── values/
│   │   │       │   ├── strings.xml
│   │   │       │   ├── colors.xml
│   │   │       │   └── themes.xml
│   │   │       └── drawable/
│   │   ├── test/
│   │   │   └── java/com/example/tefal2garmin/
│   │   │       ├── CsvParserTest.kt
│   │   │       └── MainActivityTest.kt
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── build.gradle
├── gradle.properties
├── settings.gradle
└── gradle/
    └── wrapper/
        ├── gradle-wrapper.jar
        └── gradle-wrapper.properties
```

## Android Studio Setup Instructions

### 1. Create New Project
1. Open Android Studio
2. Select "New Project"
3. Choose "Empty Activity" template
4. Configure project details:
   - Name: `Tefal2Garmin`
   - Package: `com.example.tefal2garmin`
   - Language: `Kotlin`
   - Minimum SDK: `API 26` (Android 8.0)
   - Build configuration: `Groovy DSL`
   - Save location: current directory

### 2. Project Configuration

#### build.gradle (Project level)
```gradle
buildscript {
    ext.kotlin_version = '1.9.10'
    dependencies {
        classpath "com.android.tools.build:gradle:8.1.0"
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

plugins {
    id 'com.android.application' version '8.1.0' apply false
    id 'org.jetbrains.kotlin.android' version '$kotlin_version' apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
```

#### build.gradle (App level)
```gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.example.tefal2garmin'
    compileSdk 34
    
    defaultConfig {
        applicationId "com.example.tefal2garmin"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    
    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = '1.8'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.10.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
```

#### gradle.properties
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

### 3. Initial Files Setup

#### AndroidManifest.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Tefal2Garmin"
        tools:targetApi="26">
        
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Tefal2Garmin.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

#### strings.xml
```xml
<resources>
    <string name="app_name">Tefal2Garmin Sync</string>
</resources>
```

## 4. Verification Steps

### Build Verification
1. Open Android Studio
2. Select "Build" -> "Make Project" (Ctrl+F9)
3. Check for compilation errors
4. Run "Build" -> "Clean Project"

### Run Verification
1. Create Android Virtual Device (AVD) with API 26+
2. Select "Run" -> "Run 'app'" (Shift+F10)
3. Verify app launches without crash
4. Test on physical device if available

### Success Criteria
- [x] Project compiles without errors
- [x] Gradle sync succeeds
- [x] App launches on emulator
- [x] App launches on physical device
- [x] All default files are present:
  - MainActivity.kt
  - activity_main.xml
  - AndroidManifest.xml
  - strings.xml

## Next Steps

After successful project creation:
1. Create basic directory structure (utils, data, ui components)
2. Implement share intent filters for CSV files
3. Set up basic UI with Material Design components
4. Configure build variants and signing for future releases