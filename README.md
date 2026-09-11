# GroupPins 写真取込 (Android 橋渡しアプリ)

GroupPins (PWA) のマップ画面のカメラボタンから呼ばれ、選ばれた写真を GPS 付きのまま
GroupPins へ渡して戻るだけの、UI なしミニアプリ。入口は 1 つ (`grouppins-photo://pick`)
だけで、GroupPins 以外から呼ばれる経路も、GroupPins 以外へ写真を渡す経路も持たない。

## このアプリは通信しない

写真と位置情報という強い権限を要求するアプリなので、何ができないかを先に書く。

- **`INTERNET` 権限を宣言していない** ([AndroidManifest.xml](app/src/main/AndroidManifest.xml))。
  Android は未宣言のアプリからの通信を OS が拒否するため、写真も位置情報も
  どこかへ送信すること自体ができない
- ネットワーク系の API (`java.net` / `HttpURLConnection` / WebView 等) を
  1 つも import していない。Kotlin 2 ファイル・約 850 行で全部読める
- 直接の依存ライブラリは `androidx.exifinterface` (EXIF 読み取り) と `androidx.core`
  (FileProvider) の 2 つだけ。推移的に入るのは AndroidX の基盤ライブラリ (annotation /
  collection / concurrent-futures / lifecycle / profileinstaller / startup / tracing ほか)、
  Kotlin 標準ライブラリと kotlinx-coroutines、`com.google.guava:listenablefuture`
  (インターフェース 1 つだけの stub)、`org.jetbrains:annotations` で、解析ツール・広告 SDK・
  クラッシュレポーターの類は無い
  (`gradle :app:dependencies --configuration releaseRuntimeClasspath` で全一覧が出る)
- **外部に公開しているコンポーネントは `PickActivity` 1 つだけ**で、受け付けるのは
  `grouppins-photo://pick` の起動だけ。共有シートにも、ファイル選択の提供元一覧にも現れない。
  写真の出口は [使い方](#使い方) の 2 つ (署名検証済みの GroupPins WebAPK へ直接共有するか、
  座標だけを URL でブラウザへ渡すか) しかなく、どちらもユーザーが自分で写真を選ばなければ
  何も出ない
- 共有シートを出さずに写真を直接渡すため、渡す相手が **Chrome の WebAPK minting サーバーの
  署名鍵で署名されている**ことと、その WebAPK が `grouppins.com` を名乗っていることを
  検証してから渡す (`PhotoBridge.WEBAPK_SIGNER_CERT_SHA256`)。`org.chromium.webapk.*` という
  パッケージ名と meta-data は誰でも名乗れるため、名前だけを信じて写真を渡さない。
  検証に通らなければ写真は渡さず、座標のみの URL 経路へ落ちる。
  座標のみの URL は通常のブラウザ起動 (暗黙の `ACTION_VIEW`) で、受け取り先の検証はしない。
  Android 12 以降は OS が検証済みアプリとブラウザにしか web intent を渡さないが、
  Android 10 / 11 では `https://grouppins.com` を宣言した任意のアプリが選択ダイアログに並び、
  そのアプリを既定にしていれば座標はそこへ渡る (写真そのものは渡らない)

要求する権限が強い (`ACCESS_MEDIA_LOCATION` = 写真の位置情報) のは、
それが無いと OS が GPS を消してしまうため。理由は[なぜ必要か](#なぜ必要か)に書いた。

## 自分で確かめる

ビルドした APK の権限一覧はソースを信じなくても直接見られる
(ビルド環境は [環境構築](#環境構築-ubuntu--wsl2初回のみ) を参照。Gradle wrapper は
コミットしていないので、clone 直後は `./gradlew` ではなく `gradle` を使う)。

```bash
gradle assembleDebug
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

権限と同様に、依存ライブラリの manifest からマージされるコンポーネントも APK から直接見られる。

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump xmltree --file AndroidManifest.xml \
  app/build/outputs/apk/debug/app-debug.apk \
  | grep -E 'E: (activity|provider|receiver|service)|android:(name|exported|permission)\('
```

ソースの manifest にあるのは `PickActivity` (exported=true) と `FileProvider`
(exported=false) の 2 つだけで、ほかに以下の 2 つが
`androidx.core` → `androidx.lifecycle` → `androidx.profileinstaller` 経由でマージされる。

| コンポーネント | exported | 内容 |
|---|---|---|
| `androidx.startup.InitializationProvider` | false | 起動時に profileinstaller を初期化するだけの provider |
| `androidx.profileinstaller.ProfileInstallReceiver` | **true** | ART の実行プロファイルを受け取る receiver。`android.permission.DUMP` (システムと `adb shell` しか持てない signature 権限) で保護されており、通常のアプリからは呼べない |

## なぜ必要か

Android 10 以降、`ACCESS_MEDIA_LOCATION` 権限を持たないアプリ (= Chrome / Web アプリ全般) が
端末内の写真を読むと、OS が GPS EXIF を削除してから渡す。そのため PWA 側では
写真から位置情報を取得できない (フォトピッカー・ファイルアプリ・共有シートすべて対象)。
権限を持てるネイティブ側で `MediaStore.setRequireOriginal()` を通して原本を読み、
GPS と撮影日時を書き戻したコピーを PWA へ渡すのがこのアプリの役割。

## 使い方

インストール後の操作はすべて GroupPins 側から始まる。**ランチャーにアイコンは出ない**
(`MAIN` / `LAUNCHER` の入口を持たない)。アンインストールは端末の設定 → アプリから行う。

1. GroupPins のマップ画面でカメラボタンを押すと、intent URL (`grouppins-photo://pick`) で
   このアプリが開く
2. 初回はここで権限 (メディアの位置情報 + 写真へのアクセス) を要求する。写真へのアクセスは
   **「すべて許可」が必要**で、Android 14 以降の「写真を選択」(一部のみ) は権限不足として扱い、
   ピッカーを開かない (部分許可では選択外の写真の GPS を読めないため)。この場合は権限の
   変更手順を載せた警告ダイアログを出し、OK を押すと終了する。手順は下の
   [写真へのアクセスを「すべて許可」に変える](#写真へのアクセスをすべて許可に変える) と同じ内容
3. 手順どおりに権限を変えたら、カメラボタンをもう一度押す
4. ファイル選択 (SAF) で写真を選ぶ (複数可、上限 50 枚)
5. 写真の縮小コピーを cache に作り、**GroupPins の WebAPK (インストール済み PWA) の
   share Activity へ写真ごと直接共有**して自動で戻る (共有シートは出ない)。
   受け取りは PWA の Web Share Target (`/?shared_photos=`)
6. WebAPK が見つからない環境 (PWA 未インストール / Chrome 以外) と、WebAPK が実際に渡す
   枚数・形式を受け付けない場合は、座標のみの URL (`?photo_batch=`。1 枚でも同じ形) を
   ブラウザで開く経路へ自動フォールバックする

### 写真へのアクセスを「すべて許可」に変える

ランチャーにアイコンが出ないため、権限の変更は端末の設定アプリから行う。

1. 端末の「設定」→「アプリ」→「**GroupPins 写真取込**」
2. 「権限」→「写真と動画」
3. 「**常にすべて許可**」を選ぶ
4. 「メディアの位置情報」も許可する

項目名は Android のバージョンと端末によって少し違う (「すべて許可」「写真と動画へのフル
アクセス」など)。「選択した写真のみ許可」のままだと、選んでいない写真の GPS を OS が
読ませないため、このアプリは取り込みを始めずに終了する。

`adb` が使えるなら、付与状況は次で確認できる。

```bash
adb shell dumpsys package com.grouppins.photobridge | grep -E "READ_MEDIA_IMAGES|ACCESS_MEDIA_LOCATION|READ_MEDIA_VISUAL_USER_SELECTED"
```

`READ_MEDIA_IMAGES` が `granted=true` なら「すべて許可」になっている。
`READ_MEDIA_VISUAL_USER_SELECTED` だけが `granted=true` なら部分許可の状態。

渡すコピーは長辺 4096px を上限に縮小した **JPEG (品質 85)** で、4096px 以下の写真は縮小せず
再エンコードだけ行う。EXIF は GPS と日時タグ (`DateTimeOriginal` = 撮影日時、`DateTime` =
更新日時) を **原本にあるものだけ** 書き戻す (機種名・メーカーノート等は落ちる。原本に無い
撮影日時を更新日時から作ることはしない)。向きは `createBitmap` で縮める写真ではピクセルに
焼き込み、そのまま書き出す写真では `Orientation` タグで伝える (`inSampleSize` だけで 4096px
以下に収まった写真は後者になる)。上限の 4096px は GroupPins の `photo_quality` の最大段に
合わせた値で、プランごとの縮小は PWA とサーバーが行う。透過を持つ画像も JPEG になる
(PWA の EXIF 読み取りが JPEG しか対応していないため。透過部分は黒くなる)。
デコードできない形式だけは原本をそのままコピーする (この場合 EXIF は全て残るが、画像以外が
混ざったバッチは直接共有せず座標のみ URL へ落ちる)。EXIF を読めない・書き戻せない写真や、
GPS 付きの原本を開けない写真は原本で代替せずに除外し、「N 枚中 M 枚」のトーストで知らせる。

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

CI は APK を公開する前に、単体テストと lint が通ることと、署名が有効で登録済みの鍵
(`RELEASE_CERT_SHA256`) によるものであることと、`INTERNET` 権限が含まれないことを検証しており、
どれかが崩れるとリリース自体が失敗する。`INTERNET` の検査は pull request と main への push で
走る CI (debug APK) でも同じスクリプトで走る。署名者の証明書 SHA-256 は各リリースのノートに載せており、
`apksigner verify --print-certs` で手元の APK と突き合わせられる。ビルドの実行ログも
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
keytool -list -v -keystore release.jks -alias release \
  | sed -n 's/.*SHA256: //p' | tr -d ':\n' | tr 'A-F' 'a-f' | gh variable set RELEASE_CERT_SHA256

git tag v1.0.0 && git push origin v1.0.0
```

`release.jks` は repo に入れない。これを失うと同じ署名で更新できなくなるため
別途保管する。`RELEASE_CERT_SHA256` (repo variable) は署名鍵の証明書の SHA-256 で、
CI が APK の署名者と突き合わせる。未設定や不一致 (鍵の差し替え) ではリリースが失敗する。
`versionName` はタグ (先頭の `v` を除いたもの)、`versionCode` はタグの
`X.Y.Z` から `X×1,000,000 + Y×1,000 + Z` で導出する (`X` は 1〜2099、`Y` / `Z` は 999 まで。
形式が合わないと workflow が失敗する)。タグ以外での実行 (`workflow_dispatch` の dev ビルド) は `versionCode` に
Actions の実行番号が入り、正式版より常に小さくなるので、正式版が入った端末には
上書きインストールできない (先にアンインストールする)。

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
echo "d313adb7aedccf6cf0cfca51ec180f0059f5f8f8  /tmp/cmdline-tools.zip" | sha1sum -c -
unzip -q /tmp/cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

curl -sSLo /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
echo "544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d  /tmp/gradle.zip" | sha256sum -c -
unzip -q /tmp/gradle.zip -d $HOME/tools
export PATH=$HOME/tools/gradle-8.7/bin:$PATH
```

チェックサムは release workflow と同じ値。Gradle は公式の `gradle-8.7-bin.zip.sha256`、
cmdline-tools は Google の SDK リポジトリ定義 (`dl.google.com/android/repository/repository2-3.xml`)
に載っている値で、こちらは SHA-1 しか公開されていない。

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
- 元写真に GPS が無い場合、座標のみ URL の経路では「写真に位置情報がありません」を出して終了する。
  写真を渡す経路ではコピー自体は作られ、GPS が無い写真は PWA 側の一括記録から除外される
- クラウドのみの写真 (端末に実体が無い) は、開くのに全体のダウンロードが必要で
  事前判別もできないため、開くのを 5 秒で打ち切って「クラウドのみの写真のため読み込めませんでした」
  (複数枚なら部分読み込みトースト) を出す。一度打ち切った写真は同じ処理の中で開き直さず、
  待ち時間の合計が 30 秒を超えたら以降の打ち切りは 1 秒に短縮する。予算は 1 回の処理を通して
  引き継ぐので、写真経路から座標のみ経路へ落ちても同じ写真をもう一度待つことはない。この
  打ち切りは open (`openFileDescriptor`) に掛かるもので、open は即座に返して読み取りで止まる
  provider には効かない。`MediaStore.getMediaUri()` は `CancellationSignal` を受け取らないため
  中断できず、そこで待った時間は open の締切と予算から差し引くだけになる。読み込み中は透明な
  画面のまま (進捗表示は無い) で、Back で中断できる
- GPS 付きの原本 (`setRequireOriginal`) を開けなかった写真は、GPS を消した読み取りで代替せずに
  除外する。選んだ写真が全部これに当たると「GPS 付きの元写真を開けませんでした」を出す。
  写真へのアクセスが「一部のみ」のときに起きる
- 複数枚を選んで一部しか処理できなかったときは「N 枚中 M 枚」のトーストを出す (黙って落とさない)。
  文言は経路ごとに別で、写真を渡す経路 (`msg_partial_load`) が除外するのは読み取れない写真・
  クラウドのみの写真・上限超過分だけ、座標のみ経路 (`msg_partial_coords`) はそれに GPS の無い
  写真を加える。写真経路は GPS の無い写真もコピーして渡し、除外するのは PWA 側なので分けている
- GroupPins (PWA) 側の受け口は URL パラメータ (`photo_batch` / `photo_lat` /
  `shared_photos`) と Web Share Target (POST `/share-target`)。このアプリが使うのは
  `photo_batch` と Web Share Target の 2 つ。`photo_lat` は使わなくなったが、**PWA 側の受け口は
  消せない**: v1.0.0 の APK は 1 枚のとき `photo_lat` を送るため、更新していない端末が残っている
  間は受け続ける必要がある (加えて PWA 側で iOS ショートカット用の入口にもなっている)
- 一度に取り込める写真の上限 (`PhotoBridge.kt` の `MAX_PHOTOS`) は PWA 側の上限
  (`map.tsx` の `MAX_BATCH_PHOTOS`) と揃える必要がある。片方だけ変えると超過分が黙って落ちる
- cache コピー (`cache/shared/`) は次回起動時に 24h 超過分を自動削除する

## 実装メモ (触るとき用)

コードにコメントは置いていないので、コードから読み取れない前提だけここに残す。

- `WEBAPK_SIGNER_CERT_SHA256` の値は Chromium の `ChromeWebApkHostSignature.java` にある
  `EXPECTED_SIGNATURE` (DER 証明書: C=US, O=Google, OU=Chrome WebAPK, CN=CA) の SHA-256。
  Chrome が正規に mint した WebAPK はすべてこの固定鍵で署名される
- Activity に `android:noHistory` を付けてはいけない。権限ダイアログや SAF が前面に出た
  時点で Activity が破棄され、`onRequestPermissionsResult` / `onActivityResult` が
  届かなくなる (自前の `finish()` で終了するため)
- `android:launchMode="singleTop"` は、処理中に Home で離脱したユーザーが GroupPins から
  もう一度カメラボタンを押したときに、新しいインスタンスを積まずに `onNewIntent` へ回すため。
  積むと、放棄されたバッチの共有 intent が新しいバッチの後から発火して二重に取り込まれる。
  `onNewIntent` は保留中の配送 (`whenResumed`) を捨ててから選び直しを始めるが、それだけでは
  足りない。ワーカーは取り消せないので、`onNewIntent` の時点でまだ走っているバッチの結果が
  後から届く。`whenResumed` は 1 つしか持てないため、その古い結果が選び直しの `startPicking`
  を上書きし、放棄したバッチが新しい選択の代わりに配送されてしまう。そのため
  `processingGeneration` を持ち、`whenResumed` に入れる前に世代番号で古い結果を捨てる
- `BitmapFactory.decodeStream` は `inJustDecodeBounds = true` のとき仕様上必ず null を
  返す。戻り値で成否を判定してはならず、直後の `outWidth` / `outHeight` で判定する
- Activity が復元される (`savedInstanceState` あり) のは、権限ダイアログや SAF の裏で
  プロセスが落ちた後や、読み込み中に他アプリへ移って破棄された後など。結果待ち中なら
  生かして結果を受け取り、処理中 (ワーカー実行中〜共有先の起動待ち) なら保存しておいた
  URI で処理をやり直し、どちらでもなければ即 `finish()` する (透明な画面が残るのを防ぐ)。
  待ち中に `finish()` すると届いた結果が捨てられ、処理中に `finish()` すると選んだ写真が
  無言で消えるため、待ち状態と処理中の URI を `onSaveInstanceState` で保存している。
  やり直しは 1 回までで、2 回目の復元では `err_interrupted` で終了する
  (LMK に殺され続ける写真で無限にやり直さない)
- 共有先の `startActivity` は Activity が resumed のときだけ行う。Android 10 以降、
  バックグラウンドからの起動は例外を投げずに無言で捨てられるため、読み込み中に
  他アプリへ切り替えられていたら `onResume` まで遅延する
- 権限ダイアログが中断されると `grantResults` が空で返る。これは拒否ではないので、設定画面へ
  誘導せず「許可されませんでした」で終了する。ただしこの判定は**付与状況を見た後**に行う。
  中断されても必要な権限が全て付与されていれば (同一グループの同時付与など) そのまま
  ピッカーへ進む
- 権限結果の判定は `requiredPermissions()` の全件が付与されたかで行う (`grantResults` の
  配列は「拒否か中断か」を分けるためだけに見る)。部分許可のときだけはトーストではなく
  `AlertDialog` を出す (`PickActivity.fail` が文字列で振り分ける)。権限の変更手順は数行あり、
  トーストでは読み切る前に消えるため。OK・Back・外側タップのどれでも `finish()` して、
  透明な画面が残らないようにする。Android 14 の部分許可は `READ_MEDIA_IMAGES` 未付与 = 権限不足として扱い、
  起動のたびに再要求する。部分許可からの拡張は `READ_MEDIA_IMAGES` の明示的な再要求でしか
  起きないため、`READ_MEDIA_VISUAL_USER_SELECTED` が付いていても要求を省かない
  (宣言自体を外すと Android 14 は compatibility mode になり、セッション限りの一時付与と
  ダイアログの再表示が繰り返されるので宣言は残す)。SAF で選んだ写真を GPS 付きで開くには
  `MediaStore.getMediaUri()` で MediaStore の URI に変換してから `setRequireOriginal()` で
  開く必要があり、そこで写真アクセス権限が要る
- GPS は `ExifInterface.getLatLong()` を使わず `PhotoBridge.coordinatesOf` で自前に読む。
  androidx 1.3.7 の `getLatLong()` は GPSLatitudeRef / GPSLongitudeRef が欠けていると null を
  返し、ref の比較も `equals("N")` の大文字固定なので、小文字 ref や ref 無しの写真の GPS を
  落とす。一方で値の有限性も範囲も検査しないため `0/0` は NaN のまま返る。PWA 側
  (`utils/exif.ts`) は ref 欠落を正、大文字化して比較、`(0,0)` と範囲外を除外するので、
  ref と `(0,0)` と範囲の扱いはそちらに揃えてある。**分母 0 だけは意図して PWA より厳しい**:
  PWA の `readGpsCoord` は分母 0 の成分を 0 として計算を続けるが、`35/1,30/0,0/1` のような値で
  分を 0 にすると最大 30 分角 (約 55km) ずれた座標を地図に置くことになるため、橋渡し側は
  座標全体を棄却して「GPS 無し」として扱う (`aSingleZeroDenominatorRejectsTheWholeCoordinate`)
- EXIF の撮影日時は `Date` を経由せず文字列として `"yyyy:MM:dd HH:mm:ss"` →
  `"yyyy-MM-ddTHH:mm:ss"` に変換する (`PhotoBridge.isoTimeOf`)。EXIF の日時は TZ を持たない
  壁時計値で、PWA も naive として扱う。`SimpleDateFormat` で parse / format すると端末 TZ の
  夏時間ギャップに当たる時刻が 1 時間ずれる (`isLenient = false` はフィールドの範囲しか
  検査せず、存在しない時刻を繰り上げる)。ただし暦の実在検証は `LocalDate.of` で別に行う。
  月と日を独立に範囲で見るだけでは `2024:02:31` が通り、親の `map.tsx` は
  `new Date("2024-02-31T00:00:00")` を 3/2 として有効値にしてしまうため、存在しない日付が
  滞在記録の時刻候補になる (`datesThatDoNotExistOnTheCalendarAreRejected`)
- 縮小コピーは常に JPEG で書く。PWA の `utils/exif.ts` は JPEG (SOI) のみ対応で、PNG は
  GPS の有無に関わらず「読み取れない」として一括記録から除外されるため、透過を保つ意味が無い
- 縮小コピーの長辺上限 `SHARE_MAX_DIM` (4096) は GroupPins の `photo_quality` の最大段 (4096px) に
  合わせている。PWA は受け取った写真をプランの長辺へ canvas で再エンコードしてからアップロードする
  (小さく送ると画質が戻らないが、大きく送っても正本はサーバー側の縮小) ため、橋渡し側の上限は
  画質の上限にしかならない
- デコードは `inSampleSize` を「サンプル後の長辺が `SHARE_SAMPLE_FLOOR` (4000px) を下回らない最大の
  整数」に取る (Android 10 以降の `BitmapFactory` は 2 のべき乗以外も尊重する。javadoc の「2 のべき乗に
  丸める」は古い記述で、Skia の `SkSampledCodec` が任意の整数でサンプリングする)。8000x6000 なら 2 で
  4000x3000、12000x9000 なら 3 で 4000x3000 になり、4096 に対する 2.3% の不足は許容する。
  長辺 4000〜7999px の写真は整数サンプルで 4000 以上に留められないため全画素でデコードし、4096 を
  超える分だけ `createBitmap` で縮める (最悪 7999x6000 で約 183MiB + 48MiB)。Bitmap の画素は native
  メモリで Java ヒープの上限 (`largeHeap`) とは無関係なので、上限は端末の物理メモリと LMK (低メモリ時の
  プロセス強制終了) で決まる。native の確保に失敗すると `decodeStream` は例外ではなく null を返すため、
  bounds が読めた (対応形式の) 写真で本デコードが null なら原本コピーへ落とさず除外する (`IOException`)。
  `createBitmap` 側の失敗は `OutOfMemoryError` として写真単位で捕捉する
- EXIF の向きは、`createBitmap` で縮める写真 (`scale < 1f`) ではピクセルに焼き込み、そのまま
  書き出す写真では同じ大きさの Bitmap をもう 1 枚作らずに `Orientation` タグを書き戻して伝える。
  分岐は「`inSampleSize` の後にまだ 4096px を超えているか」で決まるので、8000x6000 のように
  サンプルだけで 4000px に収まった写真はタグ側になる。Chrome の `<img>` と
  `createImageBitmap` (既定の `imageOrientation` = `from-image`) は JPEG の EXIF の向きを適用する。
  PWA 側は `exif.ts` で GPS と日時しか読まず自前の回転はしないので二重回転にならない
- `BitmapFactory` の `inScaled` / `inDensity` や `ImageDecoder.setTargetSize` では峰メモリは下がらない。
  どちらもサンプル後サイズの Bitmap を全画素確保してから別 Bitmap へ canvas で縮小する (AOSP
  `BitmapFactory.cpp` の `doDecode`、hwui `ImageDecoder.cpp` の `decode` で確認)。`ImageDecoder` は
  EXIF の向きを無効化できずに自動適用するため、自前の回転と二重になる。libjpeg の N/8 スケーリングも
  Android の公開 API からは 1/2・1/4・1/8 しか選べない
- 低 RAM 端末で `OutOfMemoryError` になった写真は 1 枚単位でスキップされ「N 枚中 M 枚」に現れる
- WebAPK の解決は、`org.chromium.webapk.*` の候補を署名と meta-data で検証したパッケージ集合を
  1 回の共有につき 1 度だけ作り、action / MIME ごとの解決はその集合に対する `queryIntentActivities`
  だけで行う。`image/*` で探すと `image/jpeg` しか受けないフィルタにも一致してしまうため、
  ワイルドカードでは解決しない。画像以外 (デコード不能で原本コピーになった `.bin` など) が
  混ざった場合は直接共有せず座標のみ URL へ落とす
- **コピー前の早期ゲートが `SEND` と `SEND_MULTIPLE` の候補を 1 つの集合に混ぜているのは正しい。**
  「片方の action しか宣言しない WebAPK では、全枚数コピーした後で `find` が null になって
  無駄になる」という指摘が繰り返し出るが、その状態は作れない。Chromium の WebAPK テンプレート
  (`chrome/android/webapk/shell_apk/AndroidManifest.xml`、`{{#share_template}}` の節) は、
  share target がファイルを受け取り files パラメータが空でないとき、**同一の intent-filter の中に**
  `SEND` と `SEND_MULTIPLE` を並べ、`<data android:mimeType>` もその filter で共有する。
  つまり片方の action だけが `image/*` に一致する状態にならない。GroupPins の
  `manifest.webmanifest` は `files: [{ name: "photos", accept: ["image/*"] }]` を宣言しているので
  この分岐に入る。仮に PWA 側を text 専用の share target に変えると `text/plain` だけの filter に
  なるが、その場合はゲート自身が `image/*` で探して空集合になり、コピー前に座標のみ URL へ落ちる。
  action ごとにゲートを分けるコードを足しても到達しない。Chrome がテンプレートを変えた場合の
  検知は `deliverAsync` の `Log.w` (「WebAPK does not accept …」) が担う
- 縮小コピーの作成は 1 枚につき原本を 1 度だけ開き (`PhotoBridge.PhotoSource`)、EXIF・bounds・
  デコードの前に `lseek(0)` で巻き戻して同じ fd を読み直す。パイプなど巻き戻せない fd の
  provider では従来どおり読み取りごとに開き直す (打ち切りとタイムアウト予算はその開き直しにも掛かる)
- 単体テスト (`app/src/test`) の対象は Android 実行時に依存しない純粋関数だけ
  (`coordinatesOf` / `isoTimeOf`)。`gradle testDebugUnitTest` で回り、CI (`.github/workflows/ci.yml`)
  が pull request のたびと main への push のたびに `assembleDebug` / `testDebugUnitTest` /
  `lintDebug` を実行する。`push` にブランチ絞りを入れているのは、PR を開いているブランチで
  1 回の push につき `push` と `pull_request` の 2 回走るのと、タグ push で release と同じ検査が
  二重に走るのを避けるため。代わりに PR を開いていないブランチへの push では走らない
- `INTERNET` 権限の不在の検査は `.github/scripts/verify-apk.sh` に置き、ci (debug APK) と
  release (署名済み APK) の両方から呼ぶ。release だけで検査すると、主張が崩れたことに気付くのが
  タグを打った後になる。release は署名鍵を復号する前にテストと lint も通す。タグ push では ci が
  走らず、走ったとしても release はそれに依存しないので、ここで通さないと赤いコミットの APK が
  公開される
