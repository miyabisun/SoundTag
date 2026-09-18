# SoundTag

Android 17以降で、許可したBluetoothスピーカーの操作をNFCタグに割り当てるKotlinアプリ。
タグはSoundTagから直接書き込む。サーバー、アカウント、常駐サービスは不要。

APKは[GitHub Releases](https://github.com/miyabisun/SoundTag/releases/latest)から取得する。
取得したAPKと同じreleaseの `SHA256SUMS` で照合できる。

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
4. 許可した機器をタップして「特定の機器の接続」を選ぶか、「全て切断」を選ぶ。
5. 操作・機器を確認し、「タグに書き込む」を押してタグを端末の背面にかざす。
   タグの既存内容を単一のURIレコードで上書きする。NFC Toolsは不要。
6. 「書き込みました」と表示されたらタグを離して戻る。画面を閉じてタグをかざすと操作する。

書込み可能なNDEFタグと、AndroidがNDEF形式へフォーマットできるタグに対応する。
読取専用・非対応・容量不足・タグ離脱などはエラーを表示する。タグをロックする機能はない。
書込み中はタグを動かさない。画面を離れると中断し、戻っても自動再開しない。
開始済みの書込みを元に戻す保証はないため、中断したタグは内容を確認して書き直す。
書込み待ちから結果を閉じるまでは通常のタグ操作を起動しない。

初回インストール後と強制停止後は、先にSoundTagを手動起動する。
通常はアプリ画面を閉じていてもNFCで起動する。画面ロック中の読取りは前提にしない。
自動操作の許可と現在の接続状態は別。許可OFFの機器は古いタグも利用できない。
接続タグは最新の状態を確認し、許可した接続先以外の機器を切断してから対象に接続する。
対象が既に接続済みなら維持する。「全て切断」は許可済みの機器だけを切断して終了する。
許可対象外の時計等は操作しない。Bluetooth全体をOFFにはしない。

既存URIと互換で、接続は `soundtag://connect/00:11:22:33:44:AA`、全切断は `soundtag://phone`。
旧 `soundtag://disconnect/00:11:22:33:44:AA` タグも指定機器の切断として引き続き使える。
新規作成は接続・全切断の2種類で、アドレスの手入力は不要。

## 検証

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
sdkmanager 'emulator' 'system-images;android-37.0;google_apis;x86_64'
avdmanager create avd -n soundtag-api37 -k 'system-images;android-37.0;google_apis;x86_64' -d pixel_9
"$ANDROID_HOME/emulator/emulator" -avd soundtag-api37
./gradlew :app:connectedDebugAndroidTest
```

JVMテストは不正URI、権限・関連付け、許可解除、遅延した承認、設定復元とタグ内容の生成をfakeで検証する。
Bluetooth操作は同一タグ反復、A↔B切替、対象切断、スマホ復帰、対象外維持、
競合要求、古い通知、許可取消し、API拒否、タイムアウトをfakeで検証する。
書込み判断は明示開始、2択、許可解除、取消し後の古い通知、失敗・再試行、容量不足をfakeで検証する。
instrumentationは許可ON→操作選択→書込み待ち→fake NDEF書込み→結果→許可OFFを実画面部品で操作する。
生成したNDEF内のURIをNFC受信経路へ渡し、実行順序と結果表示を確認する。
再読取り、切替中の画面再生成、拒否、timeout、終了、書込み待ちの画面再生成とNFC OFFも確認する。
OSとの境界をfakeにしてアプリ内の処理を通す。通常のADBからのIntent注入はNFC入口の権限で拒否される。
この結果は実NFC書込み・読取り、実Bluetooth接続、音声出力を証明しない。Pixelでの確認は別途必要。

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
後続要求を実行・成功扱いにしない。
`NfcSession` が通知と期限、`NfcActivity` が結果表示を担当する。
画面再生成では同じ操作を保持し、プロセス再起動時は古いタグを自動再実行しない。
画面を閉じると未完了操作の中止を依頼する。取消しが未確認なら同一プロセス内で保持し、
次のNFC起動にも引き継ぐ。取消しを含む処理が落ち着いたらバックグラウンドの通知購読を終了する。
取消し待ちはOSの状態通知だけを受け、常駐サービスや定期監視は使わない。
プロセス強制終了後まで未完了操作を記録・監視するものではない。

## Pixel 9へインストール

配布APKは開発用debug署名。package IDは `dev.miyabisun.soundtag`。
versionName / versionCodeはAPKとreleaseの記録で確認する。
APKのSHA-256、source SHA、署名証明書のSHA-256はreleaseに添付する。
同じ署名鍵のAPKは `-r` で更新できる。別署名の既存アプリがあれば自動削除せず、設定を控えて対応する。

Android SDK Platform ToolsをPCに導入する。Pixelで開発者向けオプションを有効にし、
USBの場合は「USBデバッグ」をONにして接続し、端末でPCのRSA鍵を許可する。

USBなしでも、同一LANから「ワイヤレスデバッグ」→「ペア設定コードによるデバイスのペア設定」が使える。
表示中のIP・ペア設定ポートで実行し、尋ねられたコードを入力する。

```sh
adb pair IP:PAIR_PORT
# 自動接続されなければ、ワイヤレスデバッグのメイン画面にある別の接続ポートを指定
adb connect IP:CONNECT_PORT
adb devices -l
```

```sh
sha256sum -c SHA256SUMS
# devicesでPixelのserialを確認して指定する。エミュレータへ誤導入しない。
adb -s PIXEL_SERIAL install -r SoundTag-vX.Y.Z.apk
adb -s PIXEL_SERIAL shell am start -n dev.miyabisun.soundtag/.MainActivity
```

[Android公式ADB手順](https://developer.android.com/tools/adb)はWi-FiペアリングとAPKインストールを案内している。
Android 17でもADB導入は利用できる。
[開発者確認制度のFAQ](https://developer.android.com/developer-verification/guides/faq)でもADBは利用可能とされている。

## 実機で確認する項目

- Android 17のビルド番号、スピーカーA/Bの機種、再生アプリ、使用したタグを記録する。
- 初回権限→スピーカーだけ許可→接続／全切断のタグ書込みを実画面で行う。
- 既存タグの上書き中にBluetooth操作が起動しないこと、書込み後の再読取りを確認する。
- 読取専用・容量不足・タグ離脱・未フォーマット・NFC OFFと、失敗後の再試行を確認する。
- Aタグで接続し、MagSafeから外して置き直してもAの接続と音を維持する。
- 全切断タグと旧指定機器切断タグを確認する。他の出力機器がなければ本体出力へ戻る。
- BがあればA→B→Aで指定先から音が出ること、許可対象外の機器が維持されることを確認する。
- 通常終了後のタグ起動、ロック中、強制停止後、未設定、Bluetooth OFF、許可解除後の古いタグを確認する。
- 失敗時は手順と表示を記録する。音声の出力先変更と、再生アプリが一時停止する挙動を分ける。

音楽の自動再開は再生アプリに依存する。SoundTagは再生を強制しない。

## 一次資料

- [Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
- [BluetoothDevice](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)
- [NFC起動条件](https://developer.android.com/develop/connectivity/nfc/nfc)

- [NDEF書込み](https://developer.android.com/reference/android/nfc/tech/Ndef)
- [NFC reader mode](https://developer.android.com/reference/android/nfc/NfcAdapter#enableReaderMode(android.app.Activity,%20android.nfc.NfcAdapter.ReaderCallback,%20int,%20android.os.Bundle))
