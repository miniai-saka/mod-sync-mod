# Fabric Mod Sync

Minecraft **1.20.1 / Fabric / Java 17** 用のModです。サーバーが指定するMod構成を起動時に確認し、チェックボックスで選択したJARだけを転送・退避します。MD5・SHA-256照合、バックアップ、自動再起動に対応しています。すべての変更を選択するとサーバーの配布構成へ完全同期できます。

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/d51be9f1-ed9c-46c3-9d9b-fba5651989ec" alt="image1"></td>
    <td><img src="https://github.com/user-attachments/assets/2d1a35f5-6705-46a1-8416-e2660b2ff648" alt="image2"></td>
    <td><img src="https://github.com/user-attachments/assets/6cdd378c-48e2-4fa0-beae-ff89dd771cb0" alt="image3"></td>
  </tr>
</table>



## ダウンロード

ReleasesのMod JARをクライアントと専用サーバーの両方に入れます。Fabric APIも必要です。

Minecraftと同期通信は同じTCPポートを使います。既定は25565です。

## 使い方

1. クライアントとサーバーの `mods` にMod JARを配置します。
2. サーバーを起動し、`config/fabric-mod-sync-server.json` で全JAR同期またはファイルごとの選択を設定します。
3. クライアントの「設定」の左列最下段にある「Mod同期設定」で同期先を入力し、「起動時の同期」を有効にします。
4. 不整合がある場合は、スクロールできる一覧で導入するModと退避するModを選び、「選択したModを同期して再起動する」を押します。

「しないでそのまま起動する」も選べます。チェックを外したModは変更せず残します。通常は全項目を選択しますが、前回の選択とサーバー専用Modの初期除外を反映します。

## 同期するModの選択

- 導入と退避を別々のチェックボックスで選択できます。「すべてチェックする」「すべてのチェックを外す」も使えます。
- 通常モードでは、旧版を残すと同じMod IDの新版も未選択になります。新版を選ぶと必要な旧版の退避も選択します。「上級者向けモード」でMod IDの連動を解除できます。同名ファイルを上書きする場合のバックアップは常に有効になります。
- 用途の宣言に応じて「サーバー専用」「クライアント専用」「クライアント・サーバー双方」を色分け表示します。サーバー専用は初期状態で未選択ですが、手動で選択することもできます。宣言がないModはFabricの仕様に合わせて双方用として扱います。
- 選択履歴は `config/fabric-mod-sync-selection.json` に同期先ごとに保存します。Mod IDが同じならファイル名やバージョンが変わった場合にも未選択の履歴を引き継ぎます。
- 「保存して今すぐ同期」で差分がない場合は確認結果を表示します。通常起動時に変更がなければ画面は表示しません。

## サーバーのキャッシュと新規ファイル

既定では起動時に各JARのMD5・SHA-256を計算し、以後はキャッシュした一覧を返します。`files` のTrue/False、トークン、転送速度は実行中でも変更できます。

| 設定 | 既定値・動作 |
|---|---|
| `newFilesDefaultSelected` | `true`。新規に見つけたJARを `files` に追加するときのTrue/False。既存の選択は変えません。 |
| `excludeServerOnlyByDefault` | `true`。サーバー専用Modのクライアント側の初期チェックを外します。配布を禁止する場合は `syncAllFiles=false` と該当ファイルの `false` を使用します。 |
| `hashCacheMode` | `startup`：起動時のみ計算。JARの追加・入れ替えにはサーバー再起動が必要です。`on_change`：ファイルの追加・サイズ・更新時刻などの変更時だけ計算。`disabled`：問い合わせごとに計算。 |
| `verifyHashesBeforeTransfer` | `false`。`true` なら送信前にもJARを再計算します。クライアント側の転送後照合は常に行います。 |

同期API以外のHTTPアクセスは、ステータスコードや本文を返さず接続を閉じます。通常のMinecraft通信は同じポートで使うことができます。

## IP制限とログ

サーバー設定の `allowedIps` で同期を許可するIP、`deniedIps` で拒否するIPを指定できます。IPv4・IPv6・CIDRに対応し、重なる場合は拒否を優先します。許可リストが空なら、拒否したIP以外を許可します。通常のMinecraft接続はこの制限の対象外です。

`logMode` は `off`（出力なし）、`normal`（通常）、`detailed`（詳細）から選べます。拒否・成功・JAR転送量の表示は `logRejectedRequests`・`logSuccessfulRequests`・`logTransfers` で個別に切り替えられます。出力先はMinecraft標準のコンソールとログです。設定変更に再起動は不要です。

指定方法とログの例は [IP制限・ログの設定](README-ja.md#ip制限とログ) を参照してください。

## 説明・設定

- [導入・設定・復旧・制限](README-ja.md)
- [動作フロー・通信プロトコル](PROTOCOL-ja.md)
- [設定例](config-examples/)
- [変更履歴](CHANGELOG.md)

Fabric Loaderはクライアントとサーバーで同一バージョンを使用してください。Java、Minecraft本体、Loader、設定ファイルやワールドは同期対象に含みません。通常の確認画面はタイトル画面到達後に表示します。依存関係の不備でそこまで起動できない場合に備え、任意の起動前エージェントも付属しています。

## ビルド

Java 17で実行します。

```powershell
.\gradlew.bat build
```

成果物は `build/libs/` に生成されます。テストのみを実行する場合は `.\gradlew.bat test` を使用します。

## ライセンス

[MIT](LICENSE)。同梱ライブラリのライセンスは [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt) を参照してください。
