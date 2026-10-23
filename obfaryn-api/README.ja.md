# Obfaryn API：はじめての拡張開発

[English](README.md) · [プロジェクトガイド](../README.ja.md)

`obfaryn-api` は、実行エンジンと拡張の間で共有する契約を定義するモジュールです。ASM のツリーノードを公開しますが、このモジュールだけでは **JAR の読み込みやパイプラインの実行はできません**。バイトコードやリソースを変更するなら `Transformer`、フレームワークの検出・保持ルール・生成物の追加なら `Platform` から始めてください。

## 1. 依存関係とファイル配置

このリポジトリのビルドには JDK 21 と同梱の Gradle Wrapper を使います。利用側も互換性のある Kotlin コンパイラを使用してください（本プロジェクトの言語/API バージョンは 2.1）。ライブラリのバイトコードは Java 8、Gradle プラグインは Java 17 を対象とします。

このチェックアウトの `settings.gradle.kts` に追加した拡張モジュールでは、次の依存関係を指定します。

```kotlin
plugins {
  kotlin("jvm")
}

dependencies {
  implementation(project(":obfaryn-api"))
}
```

ASM の core/tree 型は API から推移的に利用できます。Kotlin ファイルは `src/main/kotlin/com/example/extension/` 以下に配置します。後述の例を実行する別の **ランチャーモジュール** には `implementation(project(":obfaryn-core"))` と拡張モジュールへの依存も追加してください。インターフェースを実装するだけの拡張に core を依存させる必要はありません。

別のローカルビルドから利用する場合は、このリポジトリで `./gradlew :obfaryn-api:publishToMavenLocal` を実行した後、利用側に `mavenLocal()` と `mavenCentral()`、`implementation("com.peco2282.obfaryn:obfaryn-api:0.0.0-SNAPSHOT")` を指定します。これは現在のローカル公開用の座標であり、Maven Central での公開を保証するものではありません。API の公開だけではエンジンは公開されません。

## 2. Transformer を実装する

文字列リテラル `Hello` だけを `Welcome` に置き換える小さな例です。これは動作を変更するサンプルであり、**暗号化ではありません**。次を `GreetingTransformer.kt` として保存します。

```kotlin
package com.example.extension

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import org.objectweb.asm.tree.LdcInsnNode

class GreetingTransformer : Transformer {
  override val id = "example-greeting"
  override val order = -100
  private var replacements = 0L

  override fun transform(context: ObfuscationContext) {
    replacements = 0
    for (owner in context.classes.values) {
      for (method in owner.methods) {
        if (!context.transformationPolicy.canTransformBody(owner, method)) continue
        for (instruction in method.instructions.toArray()) {
          if (instruction is LdcInsnNode && instruction.cst == "Hello") {
            instruction.cst = "Welcome"
            replacements++
          }
        }
      }
    }
  }

  override fun report() = TransformerReport(
    id = id,
    metrics = mapOf("replacements" to replacements),
  )
}
```

ポリシーの確認により、ホストの除外設定やアノテーションによる保護を尊重します。ASM ノードの変更時に自動チェックされるわけではありません。`toArray()` は、命令の追加・削除を行う場合にも安定した走査対象を作ります。この置換ではスタックの型・段数や制御フローは変わりません。より高度な変換では、スタック型、ローカル変数、例外処理範囲、スタックマップフレームの整合性も保つ必要があります。

`order = -100` は標準の文字列暗号化より早く実行するための優先度です。必須の依存は `requires = setOf("other-pass-id")` で指定してください。数値の優先度より依存関係が優先され、未登録の依存や循環があれば実行前に失敗します。同じ優先度では登録順ではなく ID 順になります。

## 3. ランチャーから登録・実行する

拡張への依存を追加したランチャーモジュールに、次の `Main.kt` を作成します。

```kotlin
package com.example.launcher

import com.example.extension.GreetingTransformer
import com.peco2282.obfaryn.api.transformer.PipelineConfigurer
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.Obfuscator
import java.nio.file.Paths

fun main() {
  val extension = PipelineConfigurer {
    add(GreetingTransformer())
  }
  val result = Obfuscator(
    config = ObfuscationConfig.lightweight(),
    pipelineConfigurers = listOf(extension),
  ).obfuscate(Paths.get("input.jar"), Paths.get("output.jar"))
  println(result.transformerReports)
}
```

IntelliJ IDEA で、ランチャーモジュールの実行時クラスパスを使ってこの `main` を実行してください。作業ディレクトリには `input.jar` を置き、入出力を別のパスにします。`lightweight()` は標準の文字列・数値の本体変換を無効にしますが、名前変更とメタデータ削除は維持します。`obfuscate` の正常終了だけでなく、出力アプリケーションの動作も確認してください。

パスのインスタンスはコールバックの **内部** で作ります。実行ごとに新しいレジストリが用意されますが、コールバック自体は再利用される場合があります。`add` の ID は空白不可・重複不可です。`replace(id, pass)` は対象の存在と同じ ID を要求します。`disable(id)` は存在するパスに対して使い、依存元を自動的には無効化しません。任意のパスを置換・無効化する場合は `this[id]` で存在を確認してください。`rename` の置換は、マッピングや参照更新の報告が共通 API 化されていないため現在未サポートです。

`Transformer` を実装するだけで CLI が自動検出することはありません。`PipelineConfigurer` を通してホストに明示的に渡してください。これは PlatformProvider のサービス検出とは別の仕組みです。

### 予算管理された本体変換の拡張

クラス単位の本体変換は `Transformer.isolatedBodyFeature` で予算管理に参加できます。ホストは候補クラス一つ、読み取り用の依存検索、補助クラス追加のバッファを渡します。各メソッドの変更前に `context.transformationPolicy.canTransformBody(owner, method, feature)` を確認してください。参加宣言だけで任意の変更に機能除外が自動適用されるわけではありません。

このモードでは、検索で得た依存ノードの変更、リソースの変更、クラスの削除を行わないでください。ホストは候補と注入ヘルパーをまとめて確定・巻き戻しし、累積増加とコード長を検査して、ヘルパー内のメソッドも報告します。別のヘルパークラスは自身のポリシー識別子と実行全体の既定予算を使い、生成元メソッドの予算を自動継承しません。異なる意味が必要なグローバルパスでは `isolatedBodyFeature` を null のままにします。

元名での診断には `context.transformationInputs.methodIdentity(owner, method)` を使います。`skipReason` はノードを変更せず、乱数も消費しないでください。予算管理されたパスは候補クラスごとに呼び出されるため、件数などは毎回の `transform` でリセットします。依存ノードへのアクセスは協調的な読み取り契約であり、セキュリティ用のサンドボックスではありません。

優先順位・予算・ヘルパー報告・事前解決の初期化への影響は[保護設定](../docs/protection.ja.md)、検証済み範囲と拡張制約は[実装状況](../docs/implementation-status.ja.md)を参照してください。

## 4. コンテキスト・名前・リソースの扱い

| API | 用途 |
| --- | --- |
| `ObfuscationContext` | パスからクラスノードを調査・変更し、クラスやリソースを追加・置換・削除する。 |
| `PlatformContext` | プラットフォームの検出・設定のために入力を調査する。追加には contributor の sink を使う。 |
| `TransformationInputs` | アプリケーション範囲、定数の保持、Kotlin メタデータの削除対象を問い合わせる。 |
| `TransformationPolicy` | メソッド本体・メタデータ・定数フィールド変換の許可を個別に確認する。 |
| `ResourceEntry` | JAR 内の相対パスとバイト列を保持する。配列はコピーされず共有される。 |

名前の形式を混同しないようにしてください。

| 意味 | 例 |
| --- | --- |
| JVM 内部クラス名 | `com/example/Main` |
| ドット区切りのクラス名 | `com.example.Main` |
| アノテーション・型の記述子 | `Lcom/example/Keep;` |
| メソッド記述子 | `(Ljava/lang/String;I)V` は String と int を受け取り void を返す |
| リソースのパス | `META-INF/services/com.example.Service` |

コンテキストのクラスキーに `.class` は付けません。メソッド記述子はオーバーロードを識別するもので、メソッド名とは別です。keep ルールの owner はドット区切りと内部名を受け付けますが、コンテキストでの検索には内部名を使います。

`transform` の内部で、core に依存せずリソースを追加できます。

```kotlin
context.putResource(
  com.peco2282.obfaryn.api.context.ResourceEntry(
    "example/build-info.txt",
    "Generated by my extension\n".toByteArray(Charsets.UTF_8),
  ),
)
```

`putResource` は同じパスを置き換えます。取得は `getResource`（存在しなければ `null`）、削除は `removeResource` です。テキストの変更では文字コードを明示してください。独立した配列が必要なら `entry.copy(data = entry.data.copyOf())` を使います。単なる `copy()` では配列を共有します。走査中にクラスを追加・削除するなら `classes.values.toList()` などで走査対象を固定してください。`ClassNode.name` の変更だけではマップのキーや他の参照は更新されず、クラスの名前変更には参照全体の再マッピングが必要です。

## 5. Platform でフレームワークに対応する

役割を次のように分けて実装します。

1. `PlatformProvider`：メタデータを公開し、プラットフォームを生成します。引数なしで生成できる公開実装クラスを `src/main/resources/META-INF/services/com.peco2282.obfaryn.api.PlatformProvider` に完全修飾名で 1 行ずつ登録します。provider の JAR は **ホストの実行時クラスパス** に必要です。
2. `Platform.detect(context)`：特徴的なリソースやクラスを調べ、確信度と理由を返します。検出中に入力を書き換えないでください。
3. `entrypoints(context, options)`：メソッド名ではなく内部クラス名を返します。エントリポイントはアプリケーションのパッケージ範囲の推定にも使われます。
4. `configure(context, options, target)`：保持ルールを追加します。たとえば、このコールバックの中で次のように指定します。

```kotlin
target.keepClassNameOnly("com.example.Plugin")
target.keepMethod("com.example.Plugin", "start", "()V")
target.keepResource("example-plugin.properties")
```

`keepClass` はクラス名とメンバー名を保持し、core の `keepClassNameOnly` はクラス名のみを保持します。通常、どちらもメソッド本体の変換は止めません。`excludeMethodFromVirtualization` は仮想化のみを除外します。本体の変換許可を制御したい場合はアノテーションポリシーを使ってください。

5. `extensions()`：対応する拡張である `AnnotationPolicyProvider`、`ResourceContributor`、`ClassContributor`、`ArchiveContributor` を返します。マーカーの `PlatformExtension` だけを実装しても動作は追加されません。contributor は変換前に適用され、追加されたクラスにもポリシーが適用されます。調査用マップを直接変更せず、渡された sink を使用してください。

実際の実装は [PaperPlatformProvider](../platforms/paper/src/main/kotlin/com/peco2282/obfaryn/platform/paper/PaperPlatformProvider.kt) と [PaperPlatform](../platforms/paper/src/main/kotlin/com/peco2282/obfaryn/platform/paper/PaperPlatform.kt) を参照してください。API の KDoc にも provider、検出、保持ルール、contributor の例があります。

## 6. 文字列・数値の独自アルゴリズム

- `NumberObfuscationSelector.shouldTransform` は、本体の変換許可が確認された後に対象を選択します。ノードを変更せず調査し、再現可能な抽選には渡された `Random` を使います。
- `NumberObfuscationStrategy.generateInt/generateLong` は、新しい空でない `InsnList`、または置換しない場合に `null` を返します。既存のスタックとローカル変数を壊さず、元の JVM 型の値を 1 つ残してください。float/double の既定実装は、生ビットを整数・long の生成処理に渡して保存します。
- `StringEncryptionCodec.encrypt` は `StringEncryptionResult(payload, key)` を返します。`generateDecryptor` は元の文字列を正確に復元する static な `(Ljava/lang/String;I)Ljava/lang/String;` メソッドを生成します。ローカル変数スロット 0/1 は payload/key です。渡されたシグネチャ・アクセス属性を維持し、スタック・ローカル変数の上限と必要なフレームを設定してください。

方式の実装は API のみへの依存で書けますが、標準パスで採用するにはホスト側で `obfaryn-transformer` が必要です。[標準ビルダーの例](../README.ja.md#transformer-拡張-api) も参照してください。生成したコードから参照するクラスは出力や実行環境に存在する必要があります。方式の実装クラス自体が自動で同梱されるわけではありません。同じ JAR に格納する payload と鍵は、暗号学的な秘密性を保証しません。

## 7. 検証と API リファレンス

独自パスを実運用する前に、次を確認してください。

1. 小さな入力 JAR で、変更される場合と変更されない場合を確認する（除外・保護アノテーションを付けたメソッドも含む）。
2. ホストの設定 `verifyOutput = true`（CLI では `--verify-output`）でバイトコードを検証する。階層やフレームの解析に必要な依存ライブラリも指定する。
3. 出力を実行して動作を比較する。リテラル方式では空文字列、Unicode、数値の境界値、NaN、符号付きゼロも確認する。
4. 同じシードの新しいインスタンスで 2 回実行し、再現性を確認する。報告値は実行ごとにリセットし、変更されるコレクションはスナップショットを返す。

各 API の詳細はソースの KDoc を参照してください。設定済みの Javadoc ドキュメントは、リポジトリのルートから生成できます。

```shell
./gradlew :obfaryn-api:dokkaGeneratePublicationJavadoc
./gradlew :obfaryn-api:dokkaJavadocJar
```

2 つ目のタスクは生成したドキュメントを `obfaryn-api/build/libs/` 以下の JAR にまとめます。これらはドキュメント生成用であり、拡張の実行時動作を検証するコマンドではありません。
