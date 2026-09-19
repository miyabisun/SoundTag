# 開発ガイド

SoundTagはKotlinとAndroid標準Viewで実装しています。ソースを取得し、リポジトリへ移動します。

```sh
git clone https://github.com/miyabisun/SoundTag.git
cd SoundTag
```

以降のコマンドは、このディレクトリで実行してください。
利用者向けの操作は[README](../README.md)、画面の設計は[DESIGN.md](../DESIGN.md)を参照してください。

## ビルド

JDK 17と[Android SDK Command-line Tools](https://developer.android.com/studio#command-tools)を用意します。
Gradle WrapperとAndroid Gradle Pluginの版はリポジトリの設定を使用します。
KotlinはAGP内蔵版です。SDKの場所に合わせて以下のパスを置き換えてください。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager --licenses
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

出力は`app/build/outputs/apk/debug/app-debug.apk`、package IDは`dev.miyabisun.soundtag`です。
debug APKはローカルの開発鍵で署名します。配布APKと同じ署名とは限りません。
署名の違うAPKは既存インストールへ上書きできません。鍵をGitへ登録しないでください。

## ADBでのインストール

PCへ[Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)を導入します。
端末の開発者向けオプションを有効にし、USBまたはWi-FiでPCと接続します。
USBでは「USBデバッグ」をONにし、接続時の確認画面でPCを許可してください。

Wi-FiではPCと端末を同じネットワークへ接続します。
端末の「ワイヤレスデバッグ」から「ペア設定コードによるデバイスのペア設定」を開きます。
表示されたIPアドレスとポートを使い、PCで次を実行してコードを入力してください。

```sh
adb pair IP:PAIR_PORT
adb devices -l
```

自動接続されない場合は、ワイヤレスデバッグのメイン画面にある接続ポートを指定します。
ペア設定用のポートとは別です。

```sh
adb connect IP:CONNECT_PORT
```

[リリース](https://github.com/miyabisun/SoundTag/releases/latest)からAPKと`SHA256SUMS`を
同じフォルダーへ保存します。以下の`X.Y.Z`は取得した版、`DEVICE_SERIAL`は
`adb devices -l`で確認した対象端末の識別子へ置き換えてください。

```sh
sha256sum -c SHA256SUMS
adb -s DEVICE_SERIAL install -r SoundTag-vX.Y.Z.apk
adb -s DEVICE_SERIAL shell am start -n dev.miyabisun.soundtag/.MainActivity
```

自分でビルドした場合はAPKのパスを`app/build/outputs/apk/debug/app-debug.apk`へ変更します。
`-r`は同じ署名の既存アプリをデータを保持して更新します。
署名の異なる自作版からは上書き更新できません。アプリの削除は機器の許可設定も消すため、先に設定を控えてください。
リリースの`release-info.json`には、ソースの版と署名証明書の情報も記載しています。
詳しい接続・導入方法は[Android公式ADBガイド](https://developer.android.com/tools/adb)を参照してください。

## モックと画面の検証

JVMテストはURI解析、機器の許可、接続順序、取消し、タイムアウト、NDEF生成をfakeで検証します。
Android instrumentationは実画面部品を操作し、Bluetoothとタグ書込みの境界をfakeに置き換えます。
通知、別アプリ表示、書込みの失敗・再試行、設定画面の明暗・長名・スクロールを扱います。

```sh
sdkmanager 'emulator' 'system-images;android-37.0;google_apis;x86_64'
avdmanager create avd -n soundtag-api37 -k 'system-images;android-37.0;google_apis;x86_64' -d pixel_9
"$ANDROID_HOME/emulator/emulator" -avd soundtag-api37
```

エミュレータの起動後、別のターミナルで実行します。
複数端末がある場合は`ANDROID_SERIAL`で対象を指定してください。

```sh
./gradlew :app:connectedDebugAndroidTest
```

小画面と文字拡大の確認には、対象エミュレータで次を実行できます。
`emulator-5554`は実際の識別子へ置き換えてください。

```sh
adb -s emulator-5554 shell wm size 840x1680
adb -s emulator-5554 shell wm density 420
adb -s emulator-5554 shell settings put system font_scale 2.0
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.miyabisun.soundtag.SettingsScreenTest
```

試験前の設定を控え、終了後はその値へ戻してください。既定値へ戻す場合は次のとおりです。

```sh
adb -s emulator-5554 shell wm size reset
adb -s emulator-5554 shell wm density reset
adb -s emulator-5554 shell settings put system font_scale 1.0
```

画像と部品寸法はエミュレータの`/data/local/tmp/`へ保存されます。
通知拒否のケースは、試験開始前に次のコマンドで権限を取り消します。

```sh
adb -s emulator-5554 shell pm revoke dev.miyabisun.soundtag android.permission.POST_NOTIFICATIONS
```

続けて以下のテストを単独実行します。

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.miyabisun.soundtag.NfcFlowTest#deniedNotificationPermissionStillConnectsWithoutOpeningPermissionUi
```

試験中の権限剥奪はinstrumentationのプロセスも終了するため、事前設定が必要です。

fakeの成功は、実タグの読書き、実Bluetooth接続、音声出力の成功を証明しません。
また、NFC入口は権限で保護されているため、通常のADB Intent注入では代用できません。

## タグ形式と接続処理

タグには単一のURIレコードを書き込みます。

| URI | 動作 |
| --- | --- |
| `soundtag://connect/00:11:22:33:44:AA` | 許可済みの対象機器へ切替 |
| `soundtag://phone` | 許可済みの機器を全切断 |
| `soundtag://disconnect/00:11:22:33:44:AA` | 指定機器の切断。旧タグの受信互換用 |

新規書込みは接続・全切断の2種類です。形式の正本は
[TagCommand](../app/src/main/java/dev/miyabisun/soundtag/TagCommand.kt)にあります。

[NfcActivity](../app/src/main/java/dev/miyabisun/soundtag/NfcActivity.kt)は画面なしでURIを受信し、
[NfcService](../app/src/main/java/dev/miyabisun/soundtag/NfcService.kt)へ渡して終了します。
[SwitchController](../app/src/main/java/dev/miyabisun/soundtag/SwitchController.kt)が許可と状態を確認し、
対象外の許可済み機器の切断、対象への接続を順に進めます。
APIの受付だけでは成功とせず、A2DP／LE Audioの接続状態とACLの終了を確認します。
