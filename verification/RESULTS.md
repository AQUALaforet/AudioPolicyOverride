# 検証結果（v1.1.0：複数アプリの自動切替）

検証日: 2026-10-08（日本時間）。

| 確認 | 結果 |
| --- | --- |
| Gradle 構成・依存関係の同期 | 成功（debugRuntimeClasspath 解決） |
| Debug APK | ビルド成功 |
| Release APK | R8 / resource shrink を含めビルド成功（未署名） |
| 単体テスト | 26 件成功、失敗 0 件 |
| Debug lint | エラー 0、警告 11 |
| Release lint | エラー 0、警告 11 |
| 実機への Debug APK インストール・起動 | 成功（Android 16 / API 36） |
| Shizuku Binder / 権限 / UserService 接続 | 成功 |
| UserService のプロセス権限 | shell（ADB 起動。root ではない） |
| 実機の getForceUse(4) | 11（FORCE_SYSTEM_ENFORCED） |
| アプリ再起動後の再接続・現在値取得 | 成功 |
| Compose の画面表示 | 状態・値・スイッチ・ボタンを確認、表示崩れなし |
| 複数選択・検索・保存 | カメラと設定の 2 件を保存できることを確認 |
| Foreground service | 通知付きの常駐監視を確認 |
| 対象アプリ起動時の自動変更 | 11 → 0、setForceUse 戻り値 0 を確認 |
| 対象アプリ間の移動 | 設定 → カメラで 0 を維持 |
| ホームへ戻った後の自動復元 | 0 → 11、戻り値 0 と読み戻し一致を確認 |
| 更新・再起動後の自動設定 | 対象選択と自動 ON を保持し、監視を再開 |

残る lint 警告は、仕様上意図した hidden API reflection 4 件と、
固定している Gradle / AndroidX / coroutines の更新案内 7 件です。
lint の無効化や baseline による隠蔽はしていません。

単体テストで入力制限、復元記録の保持、保存失敗時の変更防止、非 0 戻り値、
Binder 応答喪失、実値と保存状態の不一致、復元の読み戻し不一致、解除の待ち時間、
対象間の切り替え、手動優先・自動から手動への引き継ぎ、外部変更時の保護を確認しました。
自動設定の実機検証は接続された Find X9（Android 16）で実施しました。
検証後は設定アプリが対象から外れており、カメラのみを対象とする自動 ON 状態です。
実機で写真撮影は行っていません。Find X9 / Xperia 1 V での音の無効化はユーザーの確認報告です。
Xperia 1 V での今回の自動判定、Android 8/9、Activity 回転、Shizuku 停止中の実機試験は未実施です。

実行した最終ビルド:

```powershell
.\gradlew.bat :app:build :app:lintRelease --console plain
```

Debug APK SHA-256:
`9D5EFAAB664873B1B18C8EED28E5F813FD8318FCBB6300F5C033E3E1FE8C21F1`

詳細は `../automation-build.log`、`../automation-device.log`、`../gradle-sync.log`、
`../app/build/reports/tests/testDebugUnitTest/index.html`、
`../app/build/reports/lint-results-debug.html`、
`../app/build/reports/lint-results-release.html` を参照してください。
