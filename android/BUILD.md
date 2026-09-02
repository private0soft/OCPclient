# راهنمای بیلد OpenConnect +P (Android)

> **هدف این فایل:** هر کسی — انسان یا AI — بدون حدس زدن بتواند APK بسازد.  
> اگر چیزی در این فایل با `build.gradle` فرق دارد، **اول این فایل را به‌روز کن**، بعد بیلد بگیر.

---

## برای AIها و دستیارها (اول این را بخوان)

**Project root for Android:**

```
/data/Projects/MyOCApp/clients/android
```

**Do exactly this for a release build:**

```bash
cd /data/Projects/MyOCApp/clients/android
export JAVA_HOME=/home/masoud/.jdks/jbr-21.0.11
./gradlew :app:assembleRelease
```

**Do exactly this for a debug build:**

```bash
cd /data/Projects/MyOCApp/clients/android
export JAVA_HOME=/home/masoud/.jdks/jbr-21.0.11
./gradlew :app:assembleDebug
```

**Output APK paths:**

| Type | Path |
|------|------|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release.apk` |

**NEVER change these in `app/build.gradle` unless the human owner explicitly asks:**

| Field | Correct value | Why |
|-------|---------------|-----|
| `plugins { id ... }` | `com.android.application` | Anything else breaks Gradle |
| `namespace` | `net.openconnect_vpn.android` | Java code imports `net.openconnect_vpn.android.R` |
| `applicationId` | `net.openconnect_plus_p.android` | Install identity on phone (separate from old app) |

**Do NOT set `namespace` equal to `applicationId`.** That breaks all `R` imports.

**Do NOT invent plugin IDs** like `net.openconnect_plus.android`.

**Do NOT commit:** `app/release.properties`, `*.jks`, `local.properties`

**If native build fails or `jniLibs` missing:** run `make -C external` first (see below).

---

## بیلد سریع (کپی-پیست)

### Release (امضا شده — برای نصب روی گوشی)

```bash
cd /data/Projects/MyOCApp/clients/android
export JAVA_HOME=/home/masoud/.jdks/jbr-21.0.11
./gradlew :app:assembleRelease
cp -f app/build/outputs/apk/release/app-release.apk \
  /data/Projects/MyOCApp/OpenConnect-P-$(grep versionName app/build.gradle | head -1 | sed 's/.*"\(.*\)".*/\1/')-release.apk
```

### Debug (بدون keystore)

```bash
cd /data/Projects/MyOCApp/clients/android
export JAVA_HOME=/home/masoud/.jdks/jbr-21.0.11
./gradlew :app:assembleDebug
```

### نصب روی گوشی

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# یا debug:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## پیش‌نیازها

| چیز | نسخه / مسیر |
|-----|-------------|
| JDK | 21 — `JAVA_HOME=/home/masoud/.jdks/jbr-21.0.11` |
| Android SDK | `ANDROID_HOME` یا `local.properties` → `sdk.dir=...` |
| SDK packages | `platform-tools`, `build-tools;34.0.0`, `platforms;android-35` |
| NDK | r27c (برای بیلد native از سورس) |
| Gradle | از `./gradlew` استفاده کن — نصب جدا لازم نیست |

**بررسی سریع:**

```bash
java -version          # باید 17+ باشد (ترجیحاً 21)
echo $ANDROID_HOME     # یا cat local.properties
test -x ./gradlew && echo "gradlew OK"
test -d external/openconnect && echo "submodules OK"
```

---

## اولین بار / clone تازه

### ۱. Submoduleها

```bash
cd /data/Projects/MyOCApp/clients/android
git submodule update --init --recursive
```

### ۲. Native libraries (فقط اگر `jniLibs` یا `assets/raw` خالی است)

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27c   # یا NDK در Android SDK
make -C external
```

این دستور `.so` و `.jar` و باینری‌های لازم را می‌سازد و در `app/src/main/jniLibs` و `app/libs` می‌گذارد.

> اگر `make -C external` fail شد، احتمالاً NDK یا gcc روی سیستم نیست. روی ماشین masoud معمولاً قبلاً build شده و لازم نیست دوباره بزنی.

### ۳. `local.properties` (اگر نیست)

```properties
sdk.dir=/home/masoud/Android/Sdk
```

### ۴. Release signing (فقط برای `assembleRelease`)

```bash
cp app/release.properties.example app/release.properties
# سپس password واقعی را بنویس
```

فرمت `release.properties`:

```properties
path=/home/masoud/myoc-release.jks
alias=myoc
password=YOUR_KEYSTORE_PASSWORD
```

- فایل keystore: `/home/masoud/myoc-release.jks`
- alias: `myoc`
- **هر دو password (store و key) یکی هستند** در این پروژه

بدون `release.properties` → release build **unsigned** می‌شود (برای sideload معمولاً OK است).

---

## تنظیمات مهم `app/build.gradle`

```gradle
plugins {
    id 'com.android.application'          // ✅ فقط این
}

android {
    namespace "net.openconnect_vpn.android" // ✅ دست نزن

    defaultConfig {
        applicationId "net.openconnect_plus_p.android"  // ✅ هویت نصب
        versionCode 1
        versionName "1.0.0"
        // ...
    }
}
```

### تفاوت `namespace` و `applicationId`

| | namespace | applicationId |
|---|-----------|---------------|
| **چیست** | package کلاس `R` و merge manifest | نام package روی گوشی |
| **عوض کنی؟** | ❌ نه | ✅ فقط اگر بخواهی اپ جدا نصب شود |
| **مثال** | `net.openconnect_vpn.android` | `net.openconnect_plus_p.android` |

### بالا بردن نسخه

```gradle
versionCode 2        // هر بار release: +1 (عدد صحیح)
versionName "1.0.1"  // نمایش در UI
```

---

## Manifest — چیزهایی که با applicationId هماهنگ‌اند

در `app/src/main/AndroidManifest.xml` این‌ها باید `${applicationId}` باشند (الان درست است):

- `android:authorities="${applicationId}.FileProvider"`
- `android:permission="${applicationId}.REMOTE_API"`
- `<permission android:name="${applicationId}.REMOTE_API" ... />`

**دستی hardcode نکن** مثل `net.openconnect_vpn.android.FileProvider`.

---

## خطاهای رایج

| خطا | علت | راه‌حل |
|-----|-----|--------|
| `Plugin ... not found` | پلاگین اشتباه در `build.gradle` | `id 'com.android.application'` |
| `cannot find symbol: class R` | `namespace` عوض شده | برگردان به `net.openconnect_vpn.android` |
| `string/permission_description not found` | permission REMOTE_API فعال ولی string نیست | در `strings.xml` باشد (الان هست) |
| `lint-gradle:31.x.x` 404 | مشکل lint در release | `lint { checkReleaseBuilds false }` (الان هست) |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | debug و release امضای متفاوت | uninstall اپ قدیمی یا applicationId جدید |
| `App not installed` / conflict | همان applicationId، keystore متفاوت | uninstall یا applicationId عوض |
| native / `.so` missing | `external` build نشده | `make -C external` |
| JDK error | Java قدیمی | `export JAVA_HOME=...jbr-21...` |

---

## چیزهایی که **نکن**

1. ❌ `plugins { id 'net.openconnect_plus.android' }` — وجود ندارد
2. ❌ `namespace "net.openconnect_plus_p.android"` — همه importهای `R` می‌شکنند
3. ❌ commit کردن `release.properties` یا `.jks`
4. ❌ عوض کردن `namespace` برای rename اپ — فقط `applicationId`
5. ❌ حذف `lint { checkReleaseBuilds false }` بدون fix lint
6. ❌ استفاده از `gradle assemble` به‌جای `./gradlew` (wrapper version mismatch)

---

## اطلاعات اپ فعلی

| Item | Value |
|------|-------|
| App name (UI) | OpenConnect +P |
| applicationId | `net.openconnect_plus_p.android` |
| namespace | `net.openconnect_vpn.android` |
| versionName | `1.0.0` |
| versionCode | `1` |
| minSdk | 23 |
| targetSdk | 34 |
| compileSdk | 35 |
| ABI | arm64-v8a only |
| AGP | 8.7.2 |

---

## چک‌لیست قبل از تحویل APK

```bash
# 1. بیلد
cd /data/Projects/MyOCApp/clients/android
export JAVA_HOME=/home/masoud/.jdks/jbr-21.0.11
./gradlew :app:assembleRelease

# 2. package name درست است؟
$ANDROID_HOME/build-tools/34.0.0/aapt dump badging \
  app/build/outputs/apk/release/app-release.apk | head -1
# باید ببینی: package: name='net.openconnect_plus_p.android' versionName='...'

# 3. کپی به root پروژه (اختیاری)
cp app/build/outputs/apk/release/app-release.apk \
  /data/Projects/MyOCApp/OpenConnect-P-1.0.0-release.apk
```

---

## Prompt آماده برای AI احمق

اگر AI گیج شد، این را verbatim بده:

```
Build the Android app at /data/Projects/MyOCApp/clients/android.

Rules:
- cd to that directory first
- export JAVA_HOME=/home/masoud/.jdks/jbr-21.0.11
- run: ./gradlew :app:assembleRelease
- DO NOT modify namespace (keep net.openconnect_vpn.android)
- DO NOT change plugins (keep com.android.application)
- applicationId must stay net.openconnect_plus_p.android
- APK output: app/build/outputs/apk/release/app-release.apk
- Read BUILD.md in the same folder if anything fails
```

---

*آخرین بیلد موفق: OpenConnect-P-1.0.0-release.apk — package `net.openconnect_plus_p.android`*
