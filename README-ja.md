# Fabric Mod Sync 1.2.0

Minecraft **1.20.1 / Fabric / Java 17** 向けの、サーバー指定Mod構成への同期Modです。同じMod JARをクライアントと専用サーバーに入れます。Fabric APIも必要です。

## 導入

1. クライアントとサーバーの `mods` に `fabric-mod-sync-1.2.0.jar` を入れます。
2. 専用サーバーを起動します。`config/fabric-mod-sync-server.json` が作成され、Modファイル一覧も自動登録されます。
3. 通常のMinecraft用TCPポートを公開します。同期通信も同じポートを使います。標準は **25565** で、同期専用ポートの開放は不要です。
4. クライアントの「設定」の左列最下段にある **Mod同期設定** を開き、ゲームと同じ `example.com:25565` のように入力します。
5. 「起動時の同期」を有効にして保存します。「保存して今すぐ同期」で手動確認もできます。差分がなければ確認結果の画面を表示します。

初期状態のクライアント同期は無効です。接続先を設定してから有効にしてください。サーバーとクライアントの **Fabric Loaderのバージョンも同一** にしてください。Loader自身はModフォルダへの転送では更新できないため、不一致時は画面で案内して中止します。

## 同期の流れ

タイトル画面に到達した最初のタイミングで一覧を問い合わせます。一致していればそのまま進みます。不一致がある場合、次の確認画面を表示します。

> サーバー側のModとこのクライアントのModに不整合があります。同期しますか？

導入するModと退避するModをチェックボックスで選択し、「選択したModを同期して再起動する」または「しないでそのまま起動する」を選べます。スクロール、一括選択・解除、取得・更新数、退避数、転送サイズを表示します。通常起動時に選択済みの変更がなければ、確認画面は表示しません。

初期状態は全選択です。ただし前回の履歴とサーバー専用Modの初期除外を反映します。チェックを外したModは保持し、選択した変更だけを適用します。用途はJAR内の `fabric.mod.json` の宣言から判定し、タグを色分けします。サーバー専用も手動で選択できます。`environment="*"` または未指定のModは双方用として判定します。

通常モードでは同じMod ID・提供IDの旧版と新版を連動させます。旧版の退避を外すと新版の導入も外れ、新版を選ぶと必要な旧版の退避も選択します。上級者向けモードではこの連動を解除します。同名・大文字小文字違いのファイルの上書き前バックアップは、両モードで必須です。この制御はMod IDの重複を扱うもので、すべての依存バージョンや実行時のMod競合を解決するFabric Loaderの依存解決処理を置き換えるものではありません。

履歴は `config/fabric-mod-sync-selection.json` に同期先と処理ごとに保存します。Mod IDを利用するため、更新でファイル名が変わった場合にも未選択を引き継げます。上級者向けモード自体は起動ごとに無効へ戻ります。

- ファイル名・サイズ・MD5・SHA-256が同じファイルは転送しません。
- 異なるファイルだけをJAR単位で転送します。
- ダウンロード中はファイル名、ファイル数、転送量、全体の進捗率を表示します。
- 全ファイルを一時領域へ保存し、MD5とSHA-256の両方を検証します。
- 転送終了時にもサーバー一覧を確認し、途中で構成が変わっていれば中止します。
- Minecraftを終了した後、独立したヘルパーが旧版・余分なModをバックアップへ移し、取得したModを配置します。
- 配置後は保持したModも含む全JARが、承認した選択後の構成と一致することを照合し、同じJava実行環境・起動引数で再起動します。

同期Mod自身の更新にも対応します。配布構成には **Fabric Mod Sync自身とFabric API、および必要な依存Mod** を含めてください。現在の同期Modを保持するか新版を導入し、選択後の構成に同期Modが残るようにします。

同期の拒否・キャンセルやダウンロード失敗時には、現在のMod構成で起動を続けられます。入れ替え処理が失敗した場合は元の構成へ復旧を試みます。サーバーへの参加を強制したり、同期拒否時の接続を禁止したりする機能はありません。

## サーバー設定

設定ファイルは `config/fabric-mod-sync-server.json` です。例:

```json
{
  "enabled": true,
  "sourceDirectory": "mods",
  "syncAllFiles": false,
  "syncSelectedFiles": true,
  "newFilesDefaultSelected": true,
  "excludeServerOnlyByDefault": true,
  "hashCacheMode": "startup",
  "verifyHashesBeforeTransfer": false,
  "accessToken": "",
  "maxBytesPerSecond": 0,
  "files": {
    "fabric-mod-sync-1.2.0.jar": true,
    "fabric-api-0.92.12+1.20.1.jar": true,
    "example-mod.jar": true,
    "server-only-tool.jar": false
  }
}
```

| 設定 | 動作 |
|---|---|
| `enabled` | 同期サービスの配布を有効／無効にします。無効時も問い合わせには無効であることを返します。 |
| `sourceDirectory` | 配布元。サーバーディレクトリ内の相対パスを指定します。初期値 `mods`。 |
| `syncAllFiles` | `true` で配布元の全JARを同期。個別のfalseより優先されます。 |
| `syncSelectedFiles` | `syncAllFiles=false` のとき、`files` でtrueになっているJARだけを配布します。 |
| `files` | 自動登録されたファイル名ごとのtrue／false。新規項目には `newFilesDefaultSelected` を使用し、既存項目は保持します。 |
| `newFilesDefaultSelected` | 新規JARを `files` へ追加するときの初期値。既定はtrue。 |
| `excludeServerOnlyByDefault` | サーバー専用Modのクライアントでの初期チェックを外します。手動選択は可能です。既定はtrue。 |
| `hashCacheMode` | `startup`：起動時のみ計算、`on_change`：追加・サイズ・更新時刻・ファイル識別子の変化時に再計算、`disabled`：要求ごとに再計算。 |
| `verifyHashesBeforeTransfer` | trueでファイル取得時に再計算します。既定はfalse。クライアントの転送後MD5・SHA-256照合は設定にかかわらず有効です。 |
| `accessToken` | 空なら認証なし。指定した場合、クライアントにも同じトークンを設定します。 |
| `maxBytesPerSecond` | 1ダウンロード当たりの転送速度上限。0は無制限。単位はバイト／秒。 |

`syncAllFiles=false` と `syncSelectedFiles=false` の組み合わせは同期無効です。有効な選択が0ファイルの場合も、誤って全Modを退避しないよう同期無効として扱います。

`files` はサーバーが配布を許可する集合です。その上でクライアントが導入・退避を選びます。全項目を選べば完全同期になり、退避を外せばクライアント専用の描画Modなどを残せます。サーバーの `files=false` で配布対象外となったJARは、クライアントから取得できません。`excludeServerOnlyByDefault` はチェックの初期値であり、配布権限の制限とは別です。

選択設定、トークン、転送速度は同期API要求時に再読み込みします。既定の `startup` では起動時のハッシュとメタデータをキャッシュし、要求時にはファイルを再ハッシュしません。新しい名前は設定へ自動登録しますが、追加・入れ替えたJARの配布には再起動が必要です。起動後に変わったファイルの取得は409で拒否します。稼働中のJAR追加・更新を反映させる運用では `on_change` を選んでください。

待ち受けはMinecraftの `server.properties` を共有します。アドレス・ポートの変更には再起動が必要です。`startup` での `sourceDirectory` 変更にも再起動が必要です。

専用サーバー用のMod構成とクライアント配布用の構成を分ける場合は、例えばサーバー直下に `client-mods` を作ってクライアントに必要なJAR一式を置き、`sourceDirectory` を `client-mods` にします。そのフォルダにも同期ModとFabric APIを含めてください。各Modの依存関係とMinecraft対応バージョンは管理者が正しい構成を用意してください。

## クライアント設定

ゲーム内の「設定 → Mod同期設定」から変更できます。設定ファイルは `config/fabric-mod-sync-client.json` です。

```json
{
  "enabled": false,
  "server": "localhost:25565",
  "accessToken": ""
}
```

`server` は `example.com:25565`、`127.0.0.1:25565`、`[::1]:25565` に対応します。実際のMinecraftポートを指定してください。直接接続ではHTTPとMinecraft通信が同じTCPポートを共有します。Minecraft専用プロキシはHTTPを通さないことがあるため、直接接続または両形式を通す経路が必要です。SRVレコードの自動検索は行いません。

Modは実行コードです。自分で信頼できる同期先を設定してください。MD5・SHA-256は内容の一致を検証するもので、配布者の認証を行うものではありません。直接提供するHTTPは平文で、Minecraftのログイン暗号化は同期接続に適用されません。必要に応じてアクセストークンと保護されたネットワーク経路を利用してください。クライアントのHTTPS指定は利用可能ですが、Mod自身にTLS終端機能はありません。

## 起動前の補助機能

FabricはModの依存関係を、Minecraftの画面を作る前に検証します。依存Mod不足や致命的な競合で起動が止まる場合、通常のModだけでは確認画面を表示できません。

この場合に備え、任意で使う `fabric-mod-sync-1.2.0-helper.jar` に起動前同期用のJavaエージェントを含めています。このファイルは `mods` の外に置き、ランチャーのJava引数に次の形式で追記します。

```text
"-javaagent:C:\MinecraftTools\fabric-mod-sync-1.2.0-helper.jar=C:\Minecraft\Client"
```

右側は実際のゲームディレクトリに置き換えてください。通常のMod JARとクライアント設定も導入しておきます。エージェントはMod解決より前に同じサーバーへ問い合わせ、必要なら確認画面と進捗を表示します。この画面はMinecraft起動前なので **Windowsの通常のダイアログ** です。入れ替え後、そのまま新しい構成でMinecraftを起動できるため再起動は不要です。エージェントで確認済みの起動では、Mod側の起動時問い合わせを重ねて実行しません。

通常のゲーム内同期には、このエージェント引数は必要ありません。エージェント自体の更新は、ランチャーを閉じて手動で行ってください。

## バックアップ・復旧

- 退避先: `<ゲームディレクトリ>/.modsync/transactions/<ID>/backup/`
- ダウンロードと同期計画: 同じIDの `downloads/` と `plan.json`
- 入れ替え記録: 同じIDの `journal.json`
- 最終結果: `.modsync/last-result.json`
- ヘルパーログ: `logs/mod-sync-updater.log`
- 再起動したクライアントの標準出力: `logs/mod-sync-restarted-client.log`

書き換えるのは `mods` 直下のJARだけです。ワールド、設定、リソースパックの同期はしません。JARを含むサブフォルダやリンク先ファイルがある場合は、同期を中止します。配布ファイル名の大文字・小文字だけが異なる重複も拒否します。

バックアップは自動削除しません。復元する場合はMinecraftを終了し、`plan.json` と `journal.json` を確認してください。新たに配置されたファイルを `mods` から外し、`backup` のJARを `mods` に戻します。処理途中の `PREPARED` 記録は次のヘルパー起動時に復旧対象になります。

Minecraft終了後120秒以内に元プロセスが停止しなければ、ファイルの入れ替えを中止します。起動引数が取得できない環境や `@引数ファイル` を使うランチャーは、通常の自動再起動に対応しておらず、変更前に画面で案内します。標準のJava起動引数を使うランチャーか、上記の起動前エージェントを利用してください。認証が必要なオンライン接続には通常どおり有効なランチャーのログインが必要です。

## ビルド・試験

Java 17を用意し、プロジェクト内で実行します。

```powershell
.\gradlew.bat build
```

生成物は `build/libs/fabric-mod-sync-1.2.0.jar`。`-sources.jar` はソース、`-helper.jar` は補助プログラムです。補助プログラムは通常Modの中にも埋め込まれているため、通常運用で別途配る必要はありません。

```powershell
.\gradlew.bat test
```


## 参照

詳細な動作フロー、同じポートでの判別方式、HTTP要求・応答、JSONの各項目、認証、revision計算、制限事項は [PROTOCOL-ja.md](PROTOCOL-ja.md) を参照してください。

- [Fabric 1.20 / 1.20.1](https://fabricmc.net/2023/05/25/120.html)
- [Fabric開発環境とJavaバージョン](https://wiki.fabricmc.net/tutorial:setup)
- [Minecraft 1.20.1 Yarn API](https://maven.fabricmc.net/docs/yarn-1.20.1+build.10/)

このプロジェクトのコードはMITライセンスです。Gsonを同梱する補助JARにはGsonのライセンス情報も含まれます。他のModの配布条件は各Modの条件を確認してください。
