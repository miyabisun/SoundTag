# SoundTag

Android 17以降で、許可したBluetoothスピーカーの操作をNFCタグに割り当てるKotlinアプリ。
タグはNFC Toolsで書き込む。サーバー、アカウント、常駐サービスは不要。

設定・タグ用コード生成とBluetooth操作を実装済み。NFC起動は後続タスクで追加する。

## ビルド

JDK 17、Android SDK Command-line Tools、Platform 37.0、Build Tools 36.0.0を使う。
ビルド定義はAGP 9.3.2、Gradle Wrapper 9.5.0。AGP内蔵のKotlinを使用する。
検証環境のJDKはEclipse Temurin 17.0.20.1、Platform Toolsは37.0.1。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager --licenses
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk`。
package IDは `dev.miyabisun.soundtag`。開発用APKはAndroidのdebug鍵で署名される。
署名鍵を変更すると既存インストールへの更新はできない。鍵をGitへ入れない。

## 設定とタグ

1. Bluetooth設定でスピーカーをペアリングする。
2. SoundTagを起動して付近のデバイスへのアクセスを許可する。
3. 機器の「自動操作を許可」をONにし、Androidの確認画面で機器を関連付ける。
   この確認で機器が見つからない場合は、機器を近づけ、端末の位置情報サービスも確認する。
4. 許可した機器をタップし、接続／切断を選んでコピーする。
5. NFC ToolsでURIレコードへ貼り付ける。標準Bluetoothレコードは削除する。

自動操作の許可と現在の接続状態は別。許可OFFの機器は古いコードも利用できない。
「スマホに戻す」のコードは許可したスピーカー全体を対象にする。
接続のコード例は `soundtag://connect/00:11:22:33:44:AA`。
切断は `soundtag://disconnect/00:11:22:33:44:AA`、スマホは `soundtag://phone`。
実際のコードは機器を選んでコピーし、アドレスを手入力しない。

## 検証

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
sdkmanager 'emulator' 'system-images;android-37.0;google_apis;x86_64'
avdmanager create avd -n soundtag-api37 -k 'system-images;android-37.0;google_apis;x86_64' -d pixel_9
"$ANDROID_HOME/emulator/emulator" -avd soundtag-api37
./gradlew :app:connectedDebugAndroidTest
```

JVMテストは不正URI、権限・関連付け、許可解除、遅延した承認、設定復元とコピーをfakeで検証する。
Bluetooth操作は同一タグ反復、A↔B切替、対象切断、スマホ復帰、対象外維持、
競合要求、古い通知、許可取消し、API拒否、タイムアウトをfakeで検証する。
instrumentationはfake機器の許可ON→タグコード→コピー→許可OFFを実画面部品で操作する。
この結果は実NFC、実Bluetooth接続、音声出力を証明しない。Pixelでの確認は別途必要。

## Bluetooth操作の呼出し口

`AndroidBluetooth(context, settings, changed)` の `start()` で状態通知を購読する。
このadapterを `SwitchController` に渡す。
`submit(command, SystemClock.elapsedRealtime())` が要求を受け付け、拒否理由を返す。
未許可の新要求は進行中の操作を変更しない。
通知では `changed(now)` を呼び、`status` の進行・成功・失敗を表示する。
30秒の期限にも `changed(now)` を呼ぶ。API受付だけでは成功にせず、A2DP／LE Audioの
接続を確認する。切断はACLの終了まで待つ。新しい要求は実行中の操作が落ち着いてから扱う。
終了時はcontroller、adapterの順に `close()` する。未完了の接続は許可が残る場合だけ
切断を試みる。OSへの中止要求も非同期なので、電源OFFや到達不能時に取消し完了を保証しない。
タイムアウト後は取消し未確認の対象を保持する。実リンクの終了または切断通知が確認できるまで、
後続要求を実行・成功扱いにしない。アクティビティ終了時には監視を終了する。
Activityと期限・結果表示の結線はNFCタスクが所有する。

## 一次資料

- [Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
- [BluetoothDevice](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)
- [NFC起動条件](https://developer.android.com/develop/connectivity/nfc/nfc)
