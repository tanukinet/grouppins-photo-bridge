# GroupPins 写真取込 (Android 橋渡しアプリ)

写真の共有を受けて GPS と撮影時刻を抽出し、GroupPins (PWA) のマップ画面を
`https://grouppins.com/?photo_lat=..&photo_lng=..&photo_time=..` で開くだけの
UI なしミニアプリ。

## このアプリは通信しない

写真と位置情報という強い権限を要求するアプリなので、何ができないかを先に書く。

- このアプリがすることは 2 つだけ。**写真の EXIF から GPS と撮影時刻を読む**ことと、
  読んだ結果を **`https://grouppins.com/` を開く / 共有シートへ渡す**ことで、
  それ以外の出口を持たない
- **`INTERNET` 権限を宣言していない** ([AndroidManifest.xml](app/src/main/AndroidManifest.xml))。
  Android は未宣言のアプリからの通信を OS が拒否するため、写真も位置情報も
  どこかへ送信すること自体ができない
- ネットワーク系の API (`java.net` / `HttpURLConnection` / WebView 等) を
  1 つも import していない。Kotlin 5 ファイル・約 1,300 行で全部読める
- 直接の依存ライブラリは `androidx.exifinterface` (EXIF 読み取り) と `androidx.core`
  (FileProvider) の 2 つだけ。推移的に入るのも AndroidX と Kotlin の標準ライブラリのみで、
  解析ツール・広告 SDK・クラッシュレポーターの類は無い (`./gradlew :app:dependencies` で確認できる)
- 写真が渡る先は、ユーザーが選んだ共有先 (= GroupPins の PWA) だけ。
  端末外への送信はその共有先のアプリが行う
- 共有シートを出さずに写真を直接渡す経路 (経路 0) では、渡す相手が **Chrome の WebAPK
  minting サーバーの署名鍵で署名されている**ことを検証してから渡す
  (`PhotoBridge.WEBAPK_SIGNER_CERT_SHA256`)。`org.chromium.webapk.*` というパッケージ名と
  meta-data は誰でも名乗れるため、名前だけを信じて写真を渡さない。
  検証に通らなければ写真は渡さず、座標のみの URL 経路へ落ちる

要求する権限が強い (`ACCESS_MEDIA_LOCATION` = 写真の位置情報) のは、
それが無いと OS が GPS を消してしまうため。理由は[なぜ必要か](#なぜ必要か)に書いた。

## 自分で確かめる

ビルドした APK の権限一覧はソースを信じなくても直接見られる。

```bash
./gradlew assembleDebug
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump permissions \
  app/build/outputs/apk/debug/app-debug.apk
```

出力は以下がすべてで、`android.permission.INTERNET` は現れない。

```
package: com.grouppins.photobridge
uses-permission: name='android.permission.ACCESS_MEDIA_LOCATION'
uses-permission: name='android.permission.READ_MEDIA_IMAGES'
uses-permission: name='android.permission.READ_MEDIA_VISUAL_USER_SELECTED'
uses-permission: name='android.permission.READ_EXTERNAL_STORAGE' maxSdkVersion='32'
permission: com.grouppins.photobridge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.grouppins.photobridge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

末尾 2 行はソースの AndroidManifest.xml には無く、`androidx.core` の manifest から
マージされる。自アプリが動的登録するレシーバーを非公開にするための
**自アプリ名前空間の signature レベル権限**で、他アプリや OS の機能への
アクセス権ではない。

## なぜ必要か

Android 10 以降、`ACCESS_MEDIA_LOCATION` 権限を持たないアプリ (= Chrome / Web アプリ全般) が
端末内の写真を読むと、OS が GPS EXIF を削除してから渡す。そのため PWA 側では
写真から位置情報を取得できない (フォトピッカー・ファイルアプリ・共有シートすべて対象)。
権限を持てるネイティブ側で `MediaStore.setRequireOriginal()` を通して読み取り、
URL パラメータで PWA へ渡すのがこのアプリの役割。

## 使い方

**初回セットアップ**: ランチャーから「GroupPins 写真取込」を一度起動し、権限
(メディアの位置情報 + 写真へのアクセス) を許可する。DocumentsProvider (経路 1) は
自分で権限を要求できないため、この初回起動が必須。

**経路 0 (推奨). マップ画面のカメラボタン → 自動で写真ごと戻る**:
1. GroupPins のカメラボタン → intent URL (`grouppins-photo://pick`) でこのアプリが開く
2. 写真を選ぶと、**GroupPins の WebAPK (インストール済み PWA) の share Activity へ
   写真ごと直接共有**して自動で戻る (共有シートは出ない)。受け取りは経路 2 と同じ
   Web Share Target (`/?shared_photos=`)
3. WebAPK が見つからない環境 (PWA 未インストール / Chrome 以外) では従来の
   座標のみ URL (`?photo_lat=` / `?photo_batch=`) へ自動フォールバック

**経路 1. ファイル選択で「GroupPins 写真取込」を選ぶ (DocumentsProvider)**:
1. ブラウザの任意のファイル選択 (写真を添付する箇所など) を開く
2. 提供元 (サイドバー / ブラウズ) から **「GroupPins 写真取込」** を選ぶ
3. 端末ローカルの写真一覧 (新しい順) から選択 → **GPS 付き原本**がブラウザに渡り、
   位置判定と写真添付が 1 回の選択で完結する
   (標準の「最近」やフォトピッカーから選ぶと OS が GPS を削除するので注意)

**経路 2. アプリを起動して GroupPins へ送る**:
1. ランチャーから「GroupPins 写真取込」を起動 → 写真を選択 (複数可)
2. GPS 付き原本を cache へコピーして共有シートが開くので **GroupPins** を選ぶ
3. PWA の Web Share Target (POST /share-target → service worker) が写真を受け取り、
   位置判定 + 写真添付の既存フローへ流れる

**旧経路 (後方互換で残置)**:
- 共有シートから写真を「GroupPins 写真取込」へ共有 → 座標だけ URL で PWA へ (ShareActivity)
- `grouppins-photo://pick` の intent 起動 (PickActivity。現行 PWA からは使わない)

## 入手

[Releases](../../releases) の APK を端末に入れる。

```bash
adb install -r grouppins-photo-bridge-<version>.apk
```

APK は **GitHub Actions が本 repo のソースからビルドして署名**している
([.github/workflows/release.yml](.github/workflows/release.yml))。
手元のバイナリが Releases のものと同一かは同梱の `.sha256` で確認できる。

```bash
sha256sum -c grouppins-photo-bridge-<version>.apk.sha256
```

CI は APK を公開する前に、署名が有効であることと `INTERNET` 権限が含まれないことを
検証しており、どちらかが崩れるとリリース自体が失敗する。ビルドの実行ログは
各リリースのノートからたどれる。

### リリースを出す (メンテナ向け)

署名鍵を repo secrets に入れておき、タグを打つ。

```bash
keytool -genkeypair -v -keystore release.jks -alias release \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD

git tag v1.0.0 && git push origin v1.0.0
```

`release.jks` は repo に入れない。これを失うと同じ署名で更新できなくなるため
別途保管する。`versionName` はタグ (先頭の `v` を除いたもの)、`versionCode` は
Actions の実行番号が入る。

## ビルドとインストール

自分でビルドする場合。Android Studio でこのディレクトリを開いて `app` を実行
(端末を USB 接続)、または以下。

### 環境構築 (Ubuntu / WSL2。初回のみ)

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk-headless unzip curl
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

export ANDROID_HOME=$HOME/Android/Sdk
mkdir -p $ANDROID_HOME/cmdline-tools
curl -sSLo /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

curl -sSLo /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
unzip -q /tmp/gradle.zip -d $HOME/tools
export PATH=$HOME/tools/gradle-8.7/bin:$PATH
```

`JAVA_HOME` / `ANDROID_HOME` / `PATH` の 4 行は毎回のシェルで必要なので
`~/.bashrc` に入れておく。

### ビルド

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

wrapper はコミットしていないので `gradle wrapper` は初回のみ。
APK は `app/build/outputs/apk/debug/app-debug.apk` に出る。

`-r` は更新インストール用 (インストール済み端末で `-r` なしだと
`INSTALL_FAILED_ALREADY_EXISTS` で失敗する。初回インストールでも付けて問題ない)。
debug ビルドの `versionCode` / `versionName` は 1 / 1.0 固定なので、どの版が入って
いるかは APK のビルド日時で判断する。

`assembleRelease` は署名鍵 (`KEYSTORE_PATH` ほか) が無いと失敗する。未署名の APK を
配ってしまわないための意図的な失敗で、手元での動作確認には `assembleDebug` を使う。

Play 配布は想定していない (Releases の APK を直接入れる)。

## 注意

- 接続先 URL は `PhotoBridge.kt` の `TARGET_URL` にハードコードしている
- 元写真に GPS が無い場合は「写真に位置情報がありません」のトーストを出して終了する
- クラウドのみの写真 (端末に実体が無い) は、開くのに全体のダウンロードが必要で
  事前判別もできないため、5 秒でスキップして「クラウドのみの写真のため読み込めませんでした」
  (複数枚なら部分読み込みトースト) を出す。読み込み自体はワーカースレッドで行うため
  待ち中もアプリは応答する
- GroupPins (PWA) 側の受け口は URL パラメータ (`photo_lat` / `photo_batch` /
  `shared_photos`) と Web Share Target (POST `/share-target`)
- 一度に取り込める写真の上限 (`PhotoBridge.kt` の `MAX_PHOTOS`) は PWA 側の上限と
  揃える必要がある。片方だけ変えると超過分が黙って落ちる
- 経路 2 の cache コピー (`cache/shared/`) は次回起動時に 24h 超過分を自動削除する

## 実装メモ (触るとき用)

コードにコメントは置いていないので、コードから読み取れない前提だけここに残す。

- `WEBAPK_SIGNER_CERT_SHA256` の値は Chromium の `ChromeWebApkHostSignature.java` にある
  `EXPECTED_SIGNATURE` (DER 証明書: C=US, O=Google, OU=Chrome WebAPK, CN=CA) の SHA-256。
  Chrome が正規に mint した WebAPK はすべてこの固定鍵で署名される
- Activity に `android:noHistory` を付けてはいけない。権限ダイアログや SAF が前面に出た
  時点で Activity が破棄され、`onRequestPermissionsResult` / `onActivityResult` が
  届かなくなる (全経路が自前の `finish()` で終了する)
- `BitmapFactory.decodeStream` は `inJustDecodeBounds = true` のとき仕様上必ず null を
  返す。戻り値で成否を判定してはならず、直後の `outWidth` / `outHeight` で判定する
