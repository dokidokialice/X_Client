# X List Client

指定した X のリストを取得し、新着投稿のみを Room(SQLite) に保存して表示する Android アプリです。

## 仕様

0. APIレベル
   - `compileSdk = 34` (Android 14)
   - `targetSdk = 34` (Android 14)
1. 設定ファイル `app/src/main/assets/x_config.properties` から以下を読み込み
   - `list_id`
   - `access_token` (Bearer Token)
   - `api_base_url`
   - `max_results`
2. 指定リストを X API `GET /2/lists/{id}/tweets` で取得
3. 初回起動（DB未作成/投稿0件時）は 99 件取得
4. 2回目以降は Room の最新投稿 ID を `since_id` に設定し、新着のみ差分同期
5. 保存先
   - 投稿本体: Room(SQLite)
   - 画像: アプリ内部ストレージ (`files/images`)
   - 画像/動画メタ情報: Room(SQLite)
6. メディア表示
   - 画像: ローカル保存画像を投稿カード内にインライン表示
   - 動画: 直接保存せず、投稿リンク (`https://x.com/i/web/status/{tweetId}`) のみ表示
7. 操作感
   - タイムライン表示
   - 下スワイプ更新（Pull to Refresh）
   - 起動時自動同期
8. しおり機能
   - 投稿ごとに「しおり追加/しおり解除」可能
   - しおり状態は `tweets.isBookmarked` に永続化

## ディレクトリ概要

- `app/src/main/java/com/example/xclient/config`: 設定ファイル読み込み
- `app/src/main/java/com/example/xclient/network`: X API 呼び出し
- `app/src/main/java/com/example/xclient/db`: Room 定義
- `app/src/main/java/com/example/xclient/repository`: 同期/保存ロジック
- `app/src/main/java/com/example/xclient/ui`: ViewModel
- `app/src/main/java/com/example/xclient/MainActivity.kt`: 画面

## 設定方法

1. `app/src/main/assets/x_config.properties` の値を設定
2. `list_id` に取得対象リスト ID を設定
3. `access_token` に Bearer Token を設定

## ビルド

```bash
./gradlew assembleDebug
```

## 補足

- 現バージョンでは `access_token` の暗号化は未対応（要件どおり）
- 動画ファイルそのものは保存しません
- X API のプラン/権限によりリスト取得エンドポイントが利用できない場合があります
- Room の DB ファイルはアプリ専用領域（`/data/data/<applicationId>/databases/timeline.db`）に保存されます
