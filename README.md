# GroupPins 写真取込 (Android 橋渡しアプリ)

写真の共有を受けて GPS と撮影時刻を抽出し、GroupPins (PWA) のマップ画面を
`https://grouppins.com/?photo_lat=..&photo_lng=..&photo_time=..` で開くだけの
UI なしミニアプリ。

## このアプリは通信しない

写真と位置情報という強い権限を要求するアプリなので、何ができないかを先に書く。

- このアプリがすることは、**写真の EXIF から GPS と撮影時刻を読む**ことと、読んだ結果や
  写真そのものを **ユーザーがその場で選んだ相手へ渡す**ことだけで、それ以外の出口を持たない。
  写真が出ていく経路は [使い方](#使い方) の経路 0 / 1 / 2 の 3 つで、どれもユーザーの操作
  (写真を選ぶ・共有先を選ぶ・ファイル選択で本アプリを選ぶ) が無ければ何も出ない
- **`INTERNET` 権限を宣言していない** ([AndroidManifest.xml](app/src/main/AndroidManifest.xml))。
  Android は未宣言のアプリからの通信を OS が拒否するため、写真も位置情報も
  どこかへ送信すること自体ができない
- ネットワーク系の API (`java.net` / `HttpURLConnection` / WebView 等) を
  1 つも import していない。Kotlin 6 ファイル・約 1,400 行で全部読める
- 直接の依存ライブラリは `androidx.exifinterface` (EXIF 読み取り) と `androidx.core`
  (FileProvider) の 2 つだけ。推移的に入るのは AndroidX の基盤ライブラリ (annotation /
  collection / concurrent-futures / lifecycle / profileinstaller / startup / tracing ほか)、
  Kotlin 標準ライブラリと kotlinx-coroutines、`com.google.guava:listenablefuture`
  (インターフェース 1 つだけの stub)、`org.jetbrains:annotations` で、解析ツール・広告 SDK・
  クラッシュレポーターの類は無い
  (`gradle :app:dependencies --configuration releaseRuntimeClasspath` で全一覧が出る)
- 写真が渡る先は、ユーザーがその場で選んだ相手だけ。経路 0 は署名検証済みの GroupPins の
  WebAPK、経路 2 は共有シートで選んだアプリ、経路 1 は **ファイル選択で「GroupPins 写真取込」を
  選んだ任意のアプリ** で、経路 1 では本来 `ACCESS_MEDIA_LOCATION` を持たないアプリにも
  ユーザーの選択を条件に GPS EXIF 付きの原本が渡る。端末外への送信は受け取ったアプリが行う
- 共有シートから受け取った写真 (ShareActivity) は、共有元が読み取り許可 (URI grant) を
  付けて渡したものだけを開く。自アプリの写真アクセス権限で、他アプリが指定した任意の
  写真を開くことはしない
- 共有シートを出さずに写真を直接渡す経路 (経路 0) では、渡す相手が **Chrome の WebAPK
  minting サーバーの署名鍵で署名されている**ことを検証してから渡す
  (`PhotoBridge.WEBAPK_SIGNER_CERT_SHA256`)。`org.chromium.webapk.*` というパッケージ名と
  meta-data は誰でも名乗れるため、名前だけを信じて写真を渡さない。
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

ソースの manifest にある 3 つの Activity と 2 つの provider のほかに、以下の 2 つが
`androidx.core` → `androidx.lifecycle` → `androidx.profileinstaller` 経由でマージされる。

| コンポーネント | exported | 内容 |
|---|---|---|
| `androidx.startup.InitializationProvider` | false | 起動時に profileinstaller を初期化するだけの provider |
| `androidx.profileinstaller.ProfileInstallReceiver` | **true** | ART の実行プロファイルを受け取る receiver。`android.permission.DUMP` (システムと `adb shell` しか持てない signature 権限) で保護されており、通常のアプリからは呼べない |

## なぜ必要か

Android 10 以降、`ACCESS_MEDIA_LOCATION` 権限を持たないアプリ (= Chrome / Web アプリ全般) が
端末内の写真を読むと、OS が GPS EXIF を削除してから渡す。そのため PWA 側では
写真から位置情報を取得できない (フォトピッカー・ファイルアプリ・共有シートすべて対象)。
権限を持てるネイティブ側で `MediaStore.setRequireOriginal()` を通して読み取り、
URL パラメータで PWA へ渡すのがこのアプリの役割。

## 使い方

**初回セットアップ**: ランチャーから「GroupPins 写真取込」を一度起動し、権限
(メディアの位置情報 + 写真へのアクセス) を許可する。DocumentsProvider (経路 1) は
自分で権限を要求できないため、この初回起動が必須。写真へのアクセスは **「すべて許可」が
必要**で、Android 14 以降の「写真を選択」(一部のみ) は権限不足として扱い、経路 0 / 2 とも
ピッカーを開かずに終了する (部分許可のままでは経路 1 の一覧が選んだ写真だけになり、経路 0 / 2 でも
選択外の写真の GPS を読めないため)。ランチャーか経路 0 でもう一度起動すると選び直せる。

**経路 0 (推奨). マップ画面のカメラボタン → 自動で写真ごと戻る**:
1. GroupPins のカメラボタン → intent URL (`grouppins-photo://pick`) でこのアプリが開く
2. 写真を選ぶと、**GroupPins の WebAPK (インストール済み PWA) の share Activity へ
   写真ごと直接共有**して自動で戻る (共有シートは出ない)。受け取りは経路 2 と同じ
   Web Share Target (`/?shared_photos=`)。渡す写真の中身は経路 2 と同じ縮小コピー
3. WebAPK が見つからない環境 (PWA 未インストール / Chrome 以外) と、WebAPK の
   Web Share Target が実際に渡す枚数・形式 (単一 / 複数、JPEG / PNG) を受け付けない場合は、
   従来の座標のみ URL (`?photo_lat=` / `?photo_batch=`) へ自動フォールバック

**経路 1. ファイル選択で「GroupPins 写真取込」を選ぶ (DocumentsProvider)**:
1. ブラウザの任意のファイル選択 (写真を添付する箇所など) を開く
2. 提供元 (サイドバー / ブラウズ) から **「GroupPins 写真取込」** を選ぶ
3. 端末ローカルの写真一覧 (新しい順) から選択 → **GPS 付き原本**がブラウザに渡り、
   位置判定と写真添付が 1 回の選択で完結する
   (標準の「最近」やフォトピッカーから選ぶと OS が GPS を削除するので注意)
4. 権限が足りないときは黙って劣化させず失敗する。「写真へのアクセス」が無いと一覧の
   読み込みがエラーになり、「メディアの位置情報」が無いと (GPS を消した写真を代わりに
   渡さずに) 写真を開けない。どちらもランチャーからアプリを起動して権限を許可し直せば直る
   (「一部のみ」の部分許可も同じ扱いで、ランチャー起動時の権限ダイアログで「すべて許可」へ
   切り替えられる)

**経路 2. アプリを起動して GroupPins へ送る**:
1. ランチャーから「GroupPins 写真取込」を起動 → 写真を選択 (複数可)
2. 写真の縮小コピーを cache に作って共有シートが開くので **GroupPins** を選ぶ。
   コピーは長辺 4096px を上限に縮小した JPEG (品質 85。4096px 以下の写真は縮小せず再エンコードだけ)
   で、EXIF は GPS と日時タグ (`DateTimeOriginal` = 撮影日時、`DateTime` = 更新日時) を
   **原本にあるものだけ** 書き戻す (機種名・メーカーノート等は落ちる。原本に無い撮影日時を
   更新日時から作ることはしない)。向きは縮小した写真と透過 PNG ではピクセルに焼き込み、縮小しない
   JPEG では `Orientation` タグで伝える。透過を持つ PNG は PNG のまま縮小する。上限の 4096px は
   GroupPins の保存画質の最大段に合わせた値で、プランごとの縮小は PWA とサーバーが行う。
   デコードできない形式だけは原本をそのままコピーする (この場合 EXIF は全て残る)。
   EXIF を読めない・書き戻せない写真や、GPS 付きの原本を開けない写真は原本で代替せずに
   除外し、「N 枚中 M 枚」のトーストで知らせる
3. PWA の Web Share Target (POST /share-target → service worker) が写真を受け取り、
   位置判定 + 写真添付の既存フローへ流れる

**旧経路 (後方互換で残置)**:
- 共有シートから写真を「GroupPins 写真取込」へ共有 → 座標だけ URL で PWA へ (ShareActivity)

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

CI は APK を公開する前に、署名が有効で登録済みの鍵 (`RELEASE_CERT_SHA256`) によるもので
あることと、`INTERNET` 権限が含まれないことを検証しており、どれかが崩れるとリリース自体が
失敗する。署名者の証明書 SHA-256 は各リリースのノートに載せており、
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
- 元写真に GPS が無い場合は「写真に位置情報がありません」のトーストを出して終了する
- クラウドのみの写真 (端末に実体が無い) は、開くのに全体のダウンロードが必要で
  事前判別もできないため、開くのを 5 秒で打ち切って「クラウドのみの写真のため読み込めませんでした」
  (複数枚なら部分読み込みトースト) を出す。一度打ち切った写真は同じ処理の中で開き直さず、
  待ち時間の合計が 30 秒を超えたら以降の打ち切りは 1 秒に短縮する。この打ち切りは open
  (`openFileDescriptor`) に掛かるもので、open は即座に返して読み取りで止まる provider には
  効かない。読み込み中は透明な画面のまま (進捗表示は無い) で、Back で中断できる
- GPS 付きの原本 (`setRequireOriginal`) を開けなかった写真は、GPS を消した読み取りで代替せずに
  除外する (経路 1 の provider と同じ方針)。選んだ写真が全部これに当たると
  「GPS 付きの元写真を開けませんでした」を出す。写真へのアクセスが「一部のみ」のときに起きる
- 複数枚を選んで一部しか処理できなかったときは、どの経路でも「N 枚中 M 枚を読み込みました」の
  トーストを出す (黙って落とさない)
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
- Activity が復元される (`savedInstanceState` あり) のは、権限ダイアログや SAF の裏で
  プロセスが落ちた後や、読み込み中に他アプリへ移って破棄された後など。結果待ち中なら
  生かして結果を受け取り、処理中 (ワーカー実行中〜共有先の起動待ち) なら保存しておいた
  URI で処理をやり直し、どちらでもなければ即 `finish()` する (透明な画面が残るのを防ぐ)。
  待ち中に `finish()` すると届いた結果が捨てられ、処理中に `finish()` すると選んだ写真が
  無言で消えるため、待ち状態と処理中の URI を `onSaveInstanceState` で保存している
  (`BridgeActivity`)。やり直しの URI は自アプリが書いた Bundle 由来なので `ShareActivity` の
  grant 検査は掛け直さない (grant が失効していれば開けずに失敗として現れる)。やり直しは 1 回まで
  で、2 回目の復元では `err_interrupted` で終了する (LMK に殺され続ける写真で無限にやり直さない)
- 共有先の `startActivity` は Activity が resumed のときだけ行う。Android 10 以降、
  バックグラウンドからの起動は例外を投げずに無言で捨てられるため、読み込み中に
  他アプリへ切り替えられていたら `onResume` まで遅延する
- `ShareActivity` の `checkUriPermission(uri, myPid, myUid, FLAG_GRANT_READ_URI_PERMISSION)` は
  明示的な URI grant だけを見る (自アプリの `READ_MEDIA_IMAGES` は考慮されない)。共有元が
  grant を付けずに `content://media/...` を投げてきた場合にこれが拒否になる
- 権限ダイアログが中断されると `grantResults` が空で返る。これは拒否ではないので、設定画面へ
  誘導せず「許可されませんでした」で終了する
- `MatrixCursor.RowBuilder.add(列名, 値)` は、カーソルに無い列名を黙って無視する
  (javadoc に明記)。DocumentsProvider が呼び出し側の projection をそのまま列定義に使い、
  全列を `add` して良いのはこのため
- 権限結果の判定は `requiredPermissions()` の全件が付与されたかで行う (`grantResults` の
  配列は見ない)。Android 14 の部分許可は `READ_MEDIA_IMAGES` 未付与 = 権限不足として扱い、
  ランチャーと経路 0 の起動のたびに再要求する。部分許可からの拡張は `READ_MEDIA_IMAGES` の
  明示的な再要求でしか起きないため、`READ_MEDIA_VISUAL_USER_SELECTED` が付いていても要求を
  省かない。SAF で選んだ写真を GPS 付きで開くには `MediaStore.getMediaUri()` で MediaStore の
  URI に変換してから `setRequireOriginal()` で開く必要があり、そこで写真アクセス権限が要る。
  `ShareActivity` だけは共有元の URI grant で開けるので `ACCESS_MEDIA_LOCATION` のみを要求する
- `ShareActivity` の URI grant 検査は先頭 `MAX_PHOTOS` 件だけに掛ける。`PhotoBridge` も同じ
  定数で先頭から処理するため、検査していない URI を開くことはない (上限は両者で共有する)
- 経路 1 のフォルダ一覧は、MediaStore に「フォルダ」のテーブルが無いため写真行の `BUCKET_ID`
  から作るしかない。Android 11 以降は `QUERY_ARG_SQL_GROUP_BY` でフォルダ数ぶんの行だけを
  受け取り (各フォルダの更新日時は `LIMIT 1` の小クエリで別途取る)、`MediaStore.getGeneration()`
  の世代番号が変わるまで作り直さない。Android 10 はどちらも無いので、全行を走査して
  30 秒キャッシュする従来の形のまま。キャッシュのキーは (世代番号, 付与済みのメディア権限) で、
  世代番号は MediaStore の行が変わったときしか上がらず権限を許可し直しても動かないため、
  権限の状態をキーに含めて許可後に作り直す。空の一覧はキャッシュしない (権限不足やクエリ失敗の
  結果を固定しないため)。作り直しはロックの中で行い、同時に来た binder スレッドは待つ
- MediaProvider は写真アクセス権限が無いとき `SecurityException` を投げず、呼び出し元が所有する
  行だけ (= 本アプリでは 0 行) を返す。「権限が無ければ一覧がエラーになる」は provider 側で
  `checkSelfPermission` を見て自前で `SecurityException` を投げることで実現している
  (`PhotosDocumentsProvider.requireMediaAccess`)。Android 14 の部分許可は一覧を出す (選んだ
  写真のフォルダだけになる)
- 経路 0 の WebAPK 解決は、`org.chromium.webapk.*` の候補を署名と meta-data で検証したパッケージ集合を
  1 回の共有につき 1 度だけ作り、action / MIME ごとの解決はその集合に対する `queryIntentActivities`
  だけで行う。コピー後の実 MIME が複数ある (JPEG と PNG の混在) ときは、全ての MIME が同じ
  component で受かる場合だけ直接共有し、1 つでも受からなければ座標のみ URL へ落とす。`image/*` で
  探すと `image/jpeg` しか受けないフィルタにも一致してしまうため、ワイルドカードでは解決しない。
  画像以外 (デコード不能で原本コピーになった `.bin` など) が混ざった場合も直接共有しない
- 縮小コピーの作成は 1 枚につき原本を 1 度だけ開き (`PhotoBridge.PhotoSource`)、EXIF・bounds・
  デコードの前に `lseek(0)` で巻き戻して同じ fd を読み直す。パイプなど巻き戻せない fd の
  provider では従来どおり読み取りごとに開き直す (打ち切りとタイムアウト予算はその開き直しにも掛かる)
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
- EXIF の向きは、縮小する写真と透過 PNG では `createBitmap` でピクセルに焼き込み、縮小しない JPEG では
  同じ大きさの Bitmap をもう 1 枚作らずに `Orientation` タグを書き戻して伝える。Chrome の `<img>` と
  `createImageBitmap` (既定の `imageOrientation`) は JPEG の EXIF の向きを適用するが、PNG の `eXIf` は
  Chromium の libpng 経路も Skia の `SkPngCodec` も読まないため、PNG は焼き込みに限る。PWA 側は
  `exif.ts` で GPS と日時しか読まず自前の回転はしないので二重回転にならない
- `BitmapFactory` の `inScaled` / `inDensity` や `ImageDecoder.setTargetSize` では峰メモリは下がらない。
  どちらもサンプル後サイズの Bitmap を全画素確保してから別 Bitmap へ canvas で縮小する (AOSP
  `BitmapFactory.cpp` の `doDecode`、hwui `ImageDecoder.cpp` の `decode` で確認)。`ImageDecoder` は
  EXIF の向きを無効化できずに自動適用するため、自前の回転と二重になる。libjpeg の N/8 スケーリングも
  Android の公開 API からは 1/2・1/4・1/8 しか選べない
- 低 RAM 端末で `OutOfMemoryError` になった写真は 1 枚単位でスキップされ「N 枚中 M 枚」に現れる
- 経路 1 の一覧 Cursor は通知 URI を root の子一覧 URI に固定し、provider が MediaStore の画像
  URI を `ContentObserver` で監視して変更をその URI へ転送する。DocumentsUI は自 authority の
  URI なら監視できるため、写真の追加・削除で開いたままの一覧が更新される (端末未実測)
- Bundle 版 `queryChildDocuments` は `QUERY_ARG_SORT_COLUMNS` が 1 列で、その列を provider 側で
  並べ替えられるときだけ `EXTRA_HONORED_ARGS` を申告する。申告が無いと DocumentsUI は受け取った
  Cursor を毎回クライアント側で並べ替え直す (provider の並び順は `MAX_ITEMS` で切る範囲にしか効かない)。
  `QUERY_ARG_SQL_SORT_ORDER` の生文字列や collation 指定は解釈するが申告しない
