# Audio Policy Override

Shizukuを使い、Androidのシステム音声ポリシーを手動、または選択したアプリに合わせて切り替えるアプリです。

## ダウンロード

[最新のRelease](https://github.com/AQUALaforet/AudioPolicyOverride/releases/latest) の **Assets** からAPKをダウンロードしてください。配布APKはデバッグ署名です。

## 使い方

Android 8.0以上とShizuku v12以上が必要です。**rootは不要**です。

1. [Shizuku](https://shizuku.rikka.app/download/)をインストールし、ADBまたはrootで起動します（[起動ガイド](https://shizuku.rikka.app/guide/setup/)）。
2. 本アプリを開き、Shizukuの権限を許可します。
3. 手動OverrideをONにするか、対象アプリを複数選択して自動切替をONにします。
4. 元へ戻して自動切替も終了する場合は「復元して停止」を使います。

クイック設定に **Audio Override** タイルを追加できます。タイルの手動OFFは、対象アプリが前面なら自動管理へ引き継ぎます。通知の「復元して停止」は手動Overrideも解除し、自動監視を停止します。

Android・メーカーによって動作は異なり、シャッター音が消える保証はありません。アプリのデータ消去・アンインストール前に復元してください。

## 詳細ドキュメント

- [使い方・タイル・通知・制約](docs/USAGE.md)
- [技術仕様・状態管理](docs/TECHNICAL.md)
- [診断ログ・プライバシー](docs/DIAGNOSTICS.md)
- [開発・ビルド・検証](docs/BUILDING.md)

## ライセンス

[GNU General Public License v3.0](LICENSE)（SPDX: `GPL-3.0-only`）。
