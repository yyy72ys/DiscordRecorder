# APKビルド方法 — 3パターン

このPC（今の環境）には Android SDK / Java が入っていないため、ここでは直接 `./gradlew assembleDebug` は実行できません。
下記のどれかでビルドしてください。**一番かんたんなのは 3. GitHub Actions です。**

---

## 1. Android Studioでビルド（おすすめ: Windows/MacにStudioがある人）

1. Android Studioを起動 → `Open` → `DiscordRecorder` フォルダを選択
2. 初回は `Sync` が自動で走る（失敗したら `File > Sync Project with Gradle Files`）
3. メニュー `Build > Make Project` または `Build > Build APK`
4. できあがり: `app/build/outputs/apk/debug/app-debug.apk`

## 2. コマンドだけでビルド（Studio不要、SDKだけ必要）

JDK 17 と Android SDK（commandlinetools）が入っていれば、Studioなしでできる。

```powershell
# Windows PowerShell
cd D:\yohey2026\opencode\explore\DiscordRecorder
./gradlew assembleDebug
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

SDKがない場合は `https://developer.android.com/studio#command-tools` から commandlinetools をDLし、環境変数 `ANDROID_HOME` を設定。

## 3. GitHub Actionsでビルド（SDK不要・一番かんたん）

PCに何も入れなくても、GitHubがクラウドでAPKを作ってくれる。

1. GitHubで空のリポジトリを作る（例: `DiscordRecorder`）
2. この `DiscordRecorder` フォルダの中身を全部 push
   ```bash
   git init
   git add .
   git commit -m "init"
   git remote add origin https://github.com/<あなた>/DiscordRecorder.git
   git push -u origin main
   ```
3. ブラウザで `Actions` タブを開く → `Build APK` が自動で走る（2〜3分）
4. 完了したら `Artifacts` → `app-debug` をダウンロード → 中に `app-debug.apk` が入っている

> `workflow_dispatch` なので `Run workflow` ボタンで手動再実行も可能。

---

## トラブルシュート

- `SDK location not found` → `local.properties` に `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk` を書くか、環境変数 `ANDROID_HOME` を設定
- `Unsupported Java version` → JDK 17 を使う（JDK 8 / 21 ではAGP 8.3は失敗する）
- `Namespace not specified` → `app/build.gradle.kts` の `namespace` が正しいか確認（現在 `com.example.discordrecorder`）
