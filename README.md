# GroupPins 写真取込 (Android 橋渡しアプリ)

写真の共有を受けて GPS と撮影時刻を抽出し、GroupPins (PWA) のマップ画面を
`https://grouppins.com/?photo_lat=..&photo_lng=..&photo_time=..` で開くだけの
UI なしミニアプリ。

## このアプリは通信しない

写真と位置情報という強い権限を要求するアプリなので、何ができないかを先に書く。

- **`INTERNET` 権限を宣言していない** ([AndroidManifest.xml](app/src/main/AndroidManifest.xml))。
  Android は未宣言のアプリからの通信を OS が拒否するため、写真も位置情報も
  どこかへ送信すること自体ができない
- ネットワーク系の API (`java.net` / `HttpURLConnection` / WebView 等) を
  1 つも import していない。Kotlin 5 ファイル・約 1,300 行で全部読める
- 依存ライブラリは `androidx.exifinterface` (EXIF 読み取り) と `androidx.core`
  (FileProvider) の 2 つだけ。解析ツール・広告 SDK の類は入っていない
- 写真が渡る先は、ユーザーが選んだ共有先 (= GroupPins の PWA) だけ。
  端末外への送信はその共有先のアプリが行う
- 共有シートを出さずに写真を直接渡す経路 (経路 0) では、渡す相手が **Chrome の WebAPK
  minting サーバーの署名鍵で署名されている**ことを検証してから渡す
  (`PhotoBridge.WEBAPK_SIGNER_CERT_SHA256`)。`org.chromium.webapk.*` というパッケージ名と
  meta-data は誰でも名乗れるため、名前だけを信じて写真を渡さない。
  検証に通らなければ写真は渡さず、座標のみの URL 経路へ落ちる

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

## ビルドとインストール

Android Studio でこのディレクトリを開いて `app` を実行 (端末を USB 接続)、または:

```bash
gradle wrapper            # 初回のみ (wrapper はコミットしていない)
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` は更新インストール用 (インストール済み端末で `-r` なしだと
`INSTALL_FAILED_ALREADY_EXISTS` で失敗する。初回インストールでも付けて問題ない)。
versionCode は 1 固定のため、どの版が入っているかは APK のビルド日時で判断する。

個人利用前提の野良 APK (debug ビルド) で十分。Play 配布は想定していない。

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
