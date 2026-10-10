# 開発・ビルド・検証

[READMEに戻る](../README.md)

## ビルド

Android Studio でこのフォルダを開き、JDK 17、Android SDK Platform 37 と Build Tools 36.0.0 を
インストールして Gradle Sync を実行します。`local.properties` の SDK パスは環境に合わせてください。
AGP 9.4 に対応した Android Studio を使用してください。
UserService の更新を確実に反映するため、Run/Debug 設定の
「Always install with package manager」を有効にします。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
リリース版には自分の署名設定が必要です。R8 用に UserService と AIDL を保持するルールを付けています。

## GitHub Actions の自動ビルド

`Android Build` ワークフローは push、Pull Request、Actions 画面からの手動実行で動作します。
JDK 17 と必要な Android SDK を用意し、単体テスト・lint・Debug / Release ビルドを実行します。
Gradle Wrapper とアクションの参照は固定し、ビルド結果と検査レポートを30日間保存します。

配布 APK は上記の GitHub Releases で公開します。

## 確認とログ

診断用 Logcat は、[診断設定](DIAGNOSTICS.md)の「診断ログを記録」がONの場合のみ `AudioPolicyDiagnostics` タグで出力します。
OFF時に残すアプリ独自の診断Logcatはありません。UserServiceからの常時出力も行いません。
AndroidやShizukuライブラリ自体の出力は、この設定の制御対象外です。

単体テストは UserService の入力制限、正常な有効化・再起動後の復元、二重 ON、非 0 戻り値、UNKNOWN、
保存失敗、Binder 応答喪失、保存状態との不一致、読み戻し不一致、不正な復元値、
対象間の切り替え・解除の待ち時間・手動優先・所有者の引き継ぎ・自動所有記録の再起動復元を確認します。
実機では ADB 起動、拒否 / 許可、Shizuku 停止・再起動、画面回転、アプリ再起動、
ON / OFF と復元、および使用するカメラアプリの音を確認してください。

参照: [公式 Shizuku API](https://github.com/RikkaApps/Shizuku-API)、
[公式 UserService sample](https://github.com/RikkaApps/Shizuku-API/tree/master/demo)、
[Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk)。

過去のビルド・単体テスト・実機確認の結果は [検証結果](../verification/RESULTS.md) にまとめています。
切断・復元・所有権・並行処理に関する修正は [信頼性レビュー](../verification/RELIABILITY_REVIEW.md) を参照してください。
