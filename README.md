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
Assets から `AudioPolicyOverride-1.2.2.apk` をダウンロードしてインストールしてください。
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

DataStore に `originalForceUse`、`overrideActive`、`changePending`、`overrideOwner`、`restoreRequested` を保存します。
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

診断用 Logcat は、下記「診断ログを記録」がONの場合のみ `AudioPolicyDiagnostics` タグで出力します。
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

今回のビルド・単体テスト・実機確認の結果は [検証結果](verification/RESULTS.md) にまとめています。
切断・復元・所有権・並行処理に関する修正は [信頼性レビュー](verification/RELIABILITY_REVIEW.md) を参照してください。

## クイック設定タイル・通知

クイック設定の編集画面から **Audio Override** タイルを追加してください。
タイルには、手動／自動 Override 有効、無効、接続・状態未確認、操作中／復元待ちを表示します。
表示中は共有 Engine の更新を購読し、操作時は実際の値と保存記録を再確認します。
状態未確認は有効確定として表示せず、タップするとアプリでの確認を案内します。
ロック中は解除を求め、解除後にもう一度状態を確認します。

- タイルのONは手動 Override を要求します。自動 Override 中なら元の値を保持して手動所有へ引き継ぎます。
- タイルの手動OFFは通常の手動OFFと同じです。対象アプリが前面なら自動所有へ引き継ぎ、自動切替の設定は維持します。
- 常駐通知の **「復元して停止」** は、手動所有も含めて保存した元の値へ復元し、自動切替と監視を停止します。復元記録がなければ現在値の確認のみ行い、値を書き込みません。

復元失敗・再接続が必要・前回の変更が未確認・不正な復元記録は、専用チャネルの通知を同じIDで更新します。
タップするとアプリを開きます。復元成功を読み戻しで確認して記録を解消すると通知を消します。
正常な手動ONや接続切断だけを復元失敗とは判断しません。
明示的な復元要求は `restoreRequested` に保存し、切断・プロセス再生成後も再接続時に引き継ぎます。
接続できない間にOS状態を変更することはできません。アプリが再び起動するまで復元できない場合があります。

通知が無効な場合も復元処理を継続し、問題をアプリ内に表示します。
アプリの「通知を許可する」から説明を確認して権限を許可してください。
通知チャネルが個別に無効な場合は、Androidのアプリ通知設定で有効にしてください。
バックグラウンドから権限は要求しません。

タイルのActivity起動はAPI34以上でPendingIntent、API26〜33では従来のIntent APIを使います。
通知Actionは外部公開しないActivityを直接開き、Application所有の処理完了を待ちます。
[TileServiceの公式API](https://developer.android.com/reference/android/service/quicksettings/TileService)を参照しています。
実機でのタイル追加・ロック解除・通知Action・OEMのバックグラウンド制限は別途確認が必要です。

今回の検証結果: [タイル・通知の検証](verification/TILE_NOTIFICATIONS.md)。

## 診断設定

画面下部の「診断設定」で **「診断ログを記録」** をONにすると記録を開始します。
初期値はOFFです。設定と履歴はアプリ専用領域のファイルに永続化し、復元用DataStoreとは分離しています。
ONの間だけ、適用・復元・失敗の日時、操作元（UI／タイル／通知／自動切替／復旧）、
AudioSystemの戻り値、読み戻し結果、Shizuku接続状態の変化を直近100件まで記録します。
250msごとの監視結果、定期的な現在値の読み取り、前面アプリ名・対象外アプリの移動履歴は記録しません。
例外は種類のみを記録し、詳細メッセージやアプリ名は保存しません。

OFFにすると新しい履歴と診断Logcatを停止します。既存履歴は残るので、消す場合は **「ログを削除」** を押してください。
「診断情報をコピー」はOFFでも利用できます。明示的な操作時に端末・OS・接続状態・
AudioSystemの現在値・復元記録を確認し、保存された履歴がある場合だけ履歴も含めてクリップボードにコピーします。
取得できない項目は未確認として表示し、復元値を推測しません。ログの外部自動送信はありません。

OFFでも音声設定の変更、復元記録の保存、エラー表示、復元失敗通知は通常どおり動作します。
診断の保存失敗は診断設定欄に表示し、音声操作の経路を止めません。
OFFの保存に失敗しても、そのプロセスでは記録を停止します。ただし再起動後は以前の保存設定に戻る可能性があるため、エラー表示時は保存状態を確認してください。
診断書き込みは音声操作の排他処理から切り離し、診断用の別の排他制御で設定変更・記録・削除を直列化します。
アプリが急に終了した場合、最後の非同期診断記録が残らないことがあります。音声復元用の記録は従来の方式で別に保存します。

検証結果: [診断機能の検証](verification/DIAGNOSTICS.md)。

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

呼び出し削減の測定と検証結果: [停止・監視最適化の検証](verification/OPTIMIZATION.md)。
電池消費の改善は実測していません。
