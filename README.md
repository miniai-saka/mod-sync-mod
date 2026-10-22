# Fabric Mod Sync

Minecraft **1.20.1 / Fabric / Java 17** 用のModです。サーバーが指定するMod構成を起動時に確認し、ユーザーの承諾後に差分のあるJARだけをダウンロードします。MD5・SHA-256照合、旧版・余分なModのバックアップ、完全同期、自動再起動に対応します。

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/d51be9f1-ed9c-46c3-9d9b-fba5651989ec" alt="image1"></td>
    <td><img src="https://github.com/user-attachments/assets/2d1a35f5-6705-46a1-8416-e2660b2ff648" alt="image2"></td>
    <td><img src="https://github.com/user-attachments/assets/6cdd378c-48e2-4fa0-beae-ff89dd771cb0" alt="image3"></td>
  </tr>
</table>


## ダウンロード

| バージョン | 主な仕様 |
|---|---|
| [1.1.0](https://github.com/miniai-saka/mod-sync-mod/releases/tag/v1.1.0) | 最新版。ゲームと同期で同じTCPポートを使用。 |
| [1.0.0](https://github.com/miniai-saka/mod-sync-mod/releases/tag/v1.0.0) | 初回リリース。同期用ポートは既定で25566。 |

Releasesの `fabric-mod-sync-1.1.0.jar` をクライアントと専用サーバーの両方に入れます。Fabric APIも必要です。配布ZIPには設定例、説明書、任意で使う起動前ヘルパー、ソースを含めています。MD5・SHA-256一覧も各リリースに添付しています。

Minecraftと同期通信は同じTCPポートを使います。既定は25565です。

## 使い方

1. クライアントとサーバーの `mods` にMod JARを配置します。
2. サーバーを起動し、`config/fabric-mod-sync-server.json` で全JAR同期またはファイルごとの選択を設定します。
3. クライアントの「設定 → Mod同期設定」で同期サーバーのドメイン:ポートを入力し、「起動時の同期」を有効にします。
4. 不整合がある場合は確認画面で「同期して再起動する」を選びます。進捗表示、照合、終了後の入れ替えを経て再起動します。

「しないでそのまま起動する」も選べます。選択同期でも選ばれたJAR集合との完全一致を目指すため、それ以外のクライアントModはバックアップへ退避します。

## 説明・設定

- [導入・設定・復旧・制限](README-ja.md)
- [動作フロー・通信プロトコル](PROTOCOL-ja.md)
- [設定例](config-examples/)
- [動作確認結果](verification-report.md)
- [変更履歴](CHANGELOG.md)

Fabric Loaderはクライアントとサーバーで同一バージョンを使用してください。Java、Minecraft本体、Loader、設定ファイルやワールドは同期対象に含みません。通常の確認画面はタイトル画面到達後に表示します。依存関係の不備でそこまで起動できない場合に備え、任意の起動前エージェントも付属します。

## ビルド

Java 17で実行します。

```powershell
.\gradlew.bat build
```

成果物は `build/libs/` に生成されます。テストのみを実行する場合は `.\gradlew.bat test` を使用します。

## ライセンス

[MIT](LICENSE)。同梱ライブラリのライセンスは [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt) を参照してください。配布パッケージにはMinecraft本体や第三者のMod一式を含めていません。

作者・著作権者: `miniai-saka`。Javaパッケージ: `io.github.miniai_saka.modsync`（GitHub名のハイフンはJavaの識別子に合わせてアンダースコアにしています）。
