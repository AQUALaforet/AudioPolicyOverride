# 技術仕様と状態管理

[READMEに戻る](../README.md)

## 技術構成

Shizuku UserServiceから `android.media.AudioSystem` を読み取り・変更します。

```ini
FOR_SYSTEM = 4
FORCE_NONE = 0
FORCE_SYSTEM_ENFORCED = 11
```

Kotlin / Gradle Kotlin DSL / Compose Material 3 / ViewModel / AIDL / Preferences DataStore。
compileSdk / targetSdk は Android 17（API 37）。AGP 9.4.1 内蔵 Kotlin 2.2.10、
Compose compiler plugin 2.2.10、Gradle Wrapper 9.7.1、JDK 17 を使用します。

```text
app/src/main/
  java/dev/aqua/audiopolicy/
    MainActivity.kt
    AudioPolicyApplication.kt
    ForceUse.kt
    ui/MainScreen.kt
    ui/MainViewModel.kt
    shizuku/ShizukuManager.kt
    shizuku/AudioPolicyUserService.kt
    data/SettingsRepository.kt
    data/OverrideController.kt
    data/AppCatalog.kt
    automation/AudioPolicyEngine.kt
    automation/AutomaticPolicy.kt
    automation/ForegroundGate.kt
    automation/AutomationService.kt
  aidl/dev/aqua/audiopolicy/aidl/IAudioPolicyService.aidl
```

通常アプリは hidden API を直接呼び出しません。UserService 内だけで
`Class.forName("android.media.AudioSystem")` と reflection により
`getForceUse(int)` / `setForceUse(int, int)` を取得・実行します。
`settings put`、`appops`、`service call`、その他の shell command は使用していません。
UserService は通常の Android Service ではなく AIDL Stub です。
`bindUserService()`、`processNameSuffix("audio_policy")`、`daemon(false)`、`version(2)` を使用します。
AIDL の `destroy() = 16777114` は Shizuku の専用トランザクションです。
前面アプリ取得用の `String getForegroundPackage()` も AIDL に追加しています。
Android 10 以上では `ActivityTaskManager.getInstance().getTasks(1)` を使用し、
Android 8/9 では `IActivityManager.getTasks()` の各バージョンのシグネチャに対応します。
これらの hidden API の利用も UserService 内に限定しています。

Application 内の Engine が Manager・状態・排他制御を一元管理し、ViewModel は UI の操作を委譲します。
Activity は保持せず、Activity 再生成時も接続・監視を維持します。画面とサービスで別々の Binder 接続は作りません。
Binder death / disconnect 時は値と接続を無効化します。15 秒の bind タイムアウトと
手動再接続を備え、権限と Binder 生存状態は各呼び出しでも確認します。
Binder 操作は IO スレッド、変更操作は排他制御します。

DataStore に `originalForceUse`、`overrideActive`、`changePending`、`overrideOwner`、`restoreRequested` を保存します。
所有者は MANUAL または AUTOMATIC です。v1.0 の既存復元記録は MANUAL として引き継ぎます。
`automationEnabled`、`targetPackages` に自動設定を保存します。
元の値を永続保存できなければ AudioSystem を変更しません。
成功応答（戻り値 0）と読み戻しの一致を確認した後にのみ `overrideActive` を更新します。
変更途中のプロセス終了や応答喪失に備えた `changePending` は、完了不明の警告を表示します。
復元成功後の読み戻しで一致を確認したときに記録を消去します。
複数回 ON にしても元の 11 を現在の 0 で上書きしません。

## 停止と監視の最適化（v1.2.2）

「復元して停止」を受けた時点で、保存成否に関係なく現在のプロセスの自動適用を停止します。
復元要求の保存に失敗しても、自動OFFの保存を独立して試みます。
OFF保存にも失敗した場合、設定の再通知、画面復帰、再読み込み、再接続で保存済みONに戻しません。
停止の解除は、状態を確認し保存に成功したユーザーの明示的な自動ON操作に限定します。
保存失敗は画面に表示し、復元記録が残れば復元通知を保持します。安全な元の値や未完了記録を保存できない場合は値を推測して書き込みません。
**永続化に失敗した場合、プロセス再生成後まで停止は保証できません。** 再起動時は保存済みの設定を読み込むためです。

実値と記録で確認された手動Override中は、前面アプリを毎周期取得せず、約2秒ごとの音声状態確認を維持します。
手動OFF時は最新の画面・前面状態を確認して、自動所有への引き継ぎか復元を判断します。
観測を省略中は、前面アプリの古い表示を消します。通常の自動監視は250ms周期、退出待ちは0.8秒のままです。

画面OFF・ロック中でも、復元情報が残る間は既存の復元処理を継続します。
復元完了と記録解消を確認した後だけ、前面取得と定期的な音声読み取りを休止します。
画面ON・ロック解除の通知で状態を再確認し、監視を再開します。
画面通知は監視開始時に1度登録し、監視終了時に解除します。登録できない環境では周期的な画面確認へ戻します。
手動Overrideは画面OFF・ロックを理由に復元しません。

診断コピーは整合性が必要な音声状態・復元記録・接続状態だけをEngineのロック内で取得し、
診断ファイルの読み込みと履歴取得・文字列生成はロック外で行います。
取得済みの値を不変のスナップショットとして扱い、コピー待ちが復元や手動操作を止めないようにします。
診断の初期OFF、100件上限、非同期記録、プライバシーの仕様は維持しています。

呼び出し削減の測定と検証結果: [停止・監視最適化の検証](../verification/OPTIMIZATION.md)。
電池消費の改善は実測していません。
