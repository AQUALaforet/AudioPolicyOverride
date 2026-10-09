# Audio Policy Override

Shizuku の UserService を使い、Android の `android.media.AudioSystem` の
`FOR_SYSTEM` の Force Use 設定を読み取り・変更する独自実装です。

```ini
FOR_SYSTEM = 4
FORCE_NONE = 0
FORCE_SYSTEM_ENFORCED = 11
```

## ダウンロード

[GitHub Releases](https://github.com/AQUALaforet/AudioPolicyOverride/releases/latest) の
Assets から `AudioPolicyOverride-1.1.2.apk` をダウンロードしてインストールしてください。
現在の配布 APK はデバッグ署名です。

## 動作

- 手動 ON: 現在値を取得し、変更前の値を保存して `setForceUse(4, 0)` を実行します。
- 手動 OFF: 保存した元の値で復元します。自動切替の対象アプリが前面にある場合は、自動管理へ引き継いで 0 を維持します。
- 復元ボタン: 自動切替も停止し、保存した元の値へ復元します。
- 書き込める値は 0 と 11 だけです。想定外の現在値は UNKNOWN と警告を表示し、操作を停止します。
- `setForceUse()` の戻り値が 0 以外の場合はエラーとし、有効化済みにはしません。
- アプリ起動、UserService 接続、画面復帰、再読み込み、変更前後に実際の値を読み取ります。
  Shizuku が使えない間は値を「未取得」と表示し、操作を無効にします。
- 保存状態と実際の値が違う場合は実際の値を表示し、警告します。自動切替は一時停止して確認を求めます。
  自動切替 OFF 時に起動しただけで自動無効化することはありません。

## 複数アプリの自動切替

1. 「対象アプリを選択（複数選択）」を開きます。
2. アプリ名またはパッケージ名で検索し、対象を複数チェックして保存します。
3. 「アプリごとの自動切替」を ON にします。Android 13 以上では通知の許可を求めます。
4. 対象のいずれかが前面になると、現在値を保存して FORCE_NONE に変更します。
5. ホーム・対象外アプリへ移動して約0.8秒経つと元へ戻します。対象アプリ同士の移動では維持します。

一覧には現在のユーザーのランチャーから起動できるアプリを表示します。
自分自身は選択対象から除外します。対象一覧と自動 ON/OFF は DataStore に永続保存します。
一覧に見つからなくなった選択済みアプリも表示し、チェック解除できます。
検索で表示されない選択項目も保持されます。「キャンセル」は変更を保存しません。
0 件に変更すると自動監視を停止し、自動処理の分だけ復元します。

手動 ON は自動処理より優先します。対象アプリを閉じたり自動切替を OFF にしたりしても、
手動 ON は解除しません。自動中に手動 ON にすると、元の値を保持したまま手動管理へ移します。
通知の「自動切替を停止」からも自動処理を停止できます。

監視は通知付きの foreground service で実行し、アプリの画面を閉じても継続します。
Shizuku UserService 内で reflection によりタスク情報を読み、約250ms間隔で前面アプリを確認します。
画面消灯中は1秒間隔とし、対象から離れたものとして自動復元します。
この検知はカメラ使用の検知ではなく、選択したアプリが前面にあることの検知です。
カメラ使用権限・使用状況アクセス権限・ユーザー補助サービスは不要です。

OS やメーカーの制限により常駐サービスが終了した場合、必ず復元できる保証はありません。
復元記録を保持し、Shizuku に再接続して実際の値を確認します。自動監視中は5秒間隔で再接続を試します。
想定外の値、完了不明の操作、reflection エラーは一時停止して表示します。
「再読み込み / 再接続」で再試行するか、復元ボタンで停止・復元してください。
自動 ON を保存した状態でアプリを再度開くと監視を再開します。端末起動直後の自動開始は実装していません。

画面には実際の値、手動 ON/OFF、自動監視状態を分けて表示します。現在値が 0 でも、
このアプリが変更していなければ手動 Override disabled と表示されます。
変更前から 0 の場合、ON にしても音声ポリシーは変化せず、OFF でも 0 に戻ります。

## 必要な環境と使い方

Android 8.0 以上（minSdk 26）、Shizuku v12 以上が必要です。
[公式 Shizuku](https://shizuku.rikka.app/download/) をインストールし、ADB または root で起動してください。
**root は必須ではありません。ADB 起動の Shizuku（shell UID 2000）を想定しています。**
Android 11 以上では無線デバッグによる起動もできます。
起動方法は [Shizuku の公式ガイド](https://shizuku.rikka.app/guide/setup/) を参照してください。

1. Shizuku を起動します。
2. 本アプリを開き、「Shizuku 権限を許可」から権限を付与します。
3. UserService 接続後に現在値を確認し、手動 ON または対象選択後の自動 ON を使用します。
4. OFF または「保存した元の値へ復元」で戻します。
5. Shizuku の再起動後は「再読み込み / 再接続」を押してください。

Shizuku が停止中・未許可の場合は音声設定を読み書きできません。
「再度確認しない」で拒否した場合は、Shizuku の管理画面から許可してください。
復元情報はアプリ内 DataStore に保存されます。アプリの終了・再起動では残りますが、
データ消去・アンインストールでは失われます。必要な復元はその前に実行してください。
手動 ON は終了・切断だけでは自動復元しません。自動監視サービスが通常終了した場合は自動所有の変更だけ復元を試みます。
プロセス強制終了や Shizuku 停止時には復元できないことがあります。UserService の終了と音声設定の復元は別処理です。

## 技術構成

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

DataStore に `originalForceUse`、`overrideActive`、`changePending`、`overrideOwner` を保存します。
所有者は MANUAL または AUTOMATIC です。v1.0 の既存復元記録は MANUAL として引き継ぎます。
`automationEnabled`、`targetPackages` に自動設定を保存します。
元の値を永続保存できなければ AudioSystem を変更しません。
成功応答（戻り値 0）と読み戻しの一致を確認した後にのみ `overrideActive` を更新します。
変更途中のプロセス終了や応答喪失に備えた `changePending` は、完了不明の警告を表示します。
復元成功後の読み戻しで一致を確認したときに記録を消去します。
複数回 ON にしても元の 11 を現在の 0 で上書きしません。

## 制約

`AudioSystem` は **hidden API** で、Android / OEM によって存在、アクセス権、動作が変わり、
動作しない可能性があります。`FOR_SYSTEM` の具体的な挙動はメーカー実装に依存します。
ADB の権限や SELinux により拒否される場合もあります。
戻り値 0 と読み戻しの一致は音声ポリシー設定の確認であり、すべてのカメラアプリの
シャッター音が消える保証ではありません。OS や他アプリが後から値を変更する場合があります。
自動検知には約250msの周期と通信時間があるため、起動直後の非常に速い撮影には間に合わない場合があります。
通常の全画面アプリ利用を想定しています。分割画面・PiP・別ディスプレイ・別ユーザー・ロック画面の
特殊なカメラ起動は OEM によって判定が異なることがあります。写真確認用の別アプリも対象に追加すると、その間も維持できます。
自動処理は値が元へ戻っていれば記録を消すだけにし、外部による別の値を勝手に上書きしません。

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

Logcat タグ: `ShizukuManager`、`AudioPolicyUserService`、`AudioPolicyEngine`。
Shizuku / UserService 接続、get 結果、set 引数・戻り値、reflection / Binder 例外、復元要求を記録します。
認証情報などの秘密情報は記録しません。

単体テストは UserService の入力制限、正常な有効化・再起動後の復元、二重 ON、非 0 戻り値、UNKNOWN、
保存失敗、Binder 応答喪失、保存状態との不一致、読み戻し不一致、不正な復元値、
対象間の切り替え・解除の待ち時間・手動優先・所有者の引き継ぎ・自動所有記録の再起動復元を確認します。
実機では ADB 起動、拒否 / 許可、Shizuku 停止・再起動、画面回転、アプリ再起動、
ON / OFF と復元、および使用するカメラアプリの音を確認してください。

参照: [公式 Shizuku API](https://github.com/RikkaApps/Shizuku-API)、
[公式 UserService sample](https://github.com/RikkaApps/Shizuku-API/tree/master/demo)、
[Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk)。

今回のビルド・単体テスト・実機確認の結果は [検証結果](verification/RESULTS.md) にまとめています。
切断・復元・所有権・並行処理に関する修正は [信頼性レビュー](verification/RELIABILITY_REVIEW.md) を参照してください。
