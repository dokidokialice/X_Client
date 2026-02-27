# X List Client

指定した X のリストを取得し、新着投稿のみを Room(SQLite) に保存して表示する Android アプリです。

## 仕様

0. APIレベル
   - `compileSdk = 34` (Android 14)
   - `targetSdk = 34` (Android 14)
1. 設定ファイル `app/src/main/assets/x_config.properties` から以下を読み込み
   - `list_id`
   - `access_token` (Bearer Token, 任意: OAuthを使わない場合)
   - `refresh_token` (任意: access token 自動再発行用)
   - `client_id` (任意: 初回OAuthログイン / `refresh_token` 利用時は必須)
   - `auth_redirect_uri` (OAuthコールバックURI。既定: `http://127.0.0.1:8080/callback`)
   - `auth_scopes` (OAuthスコープ)
   - `api_base_url`
   - `max_results`
   - `offline_mode` (`true` の場合はX APIを呼ばずローカルDBのみ表示)
2. 指定リストを X API `GET /2/lists/{id}/tweets` で取得
3. 初回起動（DB未作成/投稿0件時）は 99 件取得
4. 2回目以降は Room の最新投稿 ID を基準に、新着のみ差分同期
   - API には `since_id` を送らず、`next_token` でページングしながらローカル最新IDに到達した時点で停止
5. 保存投稿数は最新 4,999 件までを保持し、超過分は古い順に削除
6. オンライン同期時の HTTP タイムアウトは 180 秒
7. API が `401` を返した場合、`refresh_token` と `client_id` が設定されていれば access token を自動再発行して1回だけ再試行
8. `access_token` が未設定でも `client_id` が設定されていれば、初回起動時に OAuth 認証でトークンを取得して端末内に保存
9. 保存先
   - 投稿本体: Room(SQLite)
   - 画像: アプリ内部ストレージ (`files/images`)（総サイズ 1GB 超過時は古い順で削除）
   - 画像/動画メタ情報: Room(SQLite)
10. メディア表示
   - 画像: ローカル保存画像を投稿カード内にインライン表示
   - 動画: 直接保存せず、投稿リンク (`https://x.com/i/web/status/{tweetId}`) のみ表示
11. 操作感
   - タイムライン表示
   - 下スワイプ更新（Pull to Refresh）
   - 起動時自動同期
12. しおり機能
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

1. `app/src/main/assets/x_config.properties.example` をコピーして `app/src/main/assets/x_config.properties` を作成
2. `x_config.properties` の `list_id` に取得対象リスト ID を設定
3. `x_config.properties` の `access_token` に Bearer Token を設定
4. 初回OAuthログインを使う場合は `client_id`（必要に応じて `auth_redirect_uri` / `auth_scopes`）を設定
   - X Developer Portal の Callback URL に `auth_redirect_uri` と同じ値を登録
5. access token を自動再発行したい場合は `refresh_token` と `client_id` を設定
6. `x_config.properties` は `.gitignore` 対象のため、ローカルでのみ管理
7. 必要に応じて `offline_mode=true` に設定（オフライン閲覧）

## ビルド

```bash
./gradlew assembleDebug
```

## 補足

- 現バージョンでは `access_token` の暗号化は未対応（要件どおり）
- 実トークン入り APK を作る場合は、`x_config.properties` を配置したローカル環境（またはシークレット管理済みCI）でビルドしてください
- `app/src/main/assets/x_config.properties` は Git 管理外、`app/src/main/assets/x_config.properties.example` のみ Git 管理します
- 動画ファイルそのものは保存しません
- X API のプラン/権限によりリスト取得エンドポイントが利用できない場合があります
- Room の DB ファイルはアプリ専用領域（`/data/data/<applicationId>/databases/timeline.db`）に保存されます

## License

MIT License (`LICENSE`)
