# Obfaryn

## 配布とリリース

全モジュールのバージョンは既定で `0.0.0-SNAPSHOT` に統一され、
`-PreleaseVersion=0.1.0` で変更できます（例示でありリリース告知ではありません）。
JDK 21 で、ローカル Maven リポジトリと単体実行用 CLI 配布物を生成します。

```shell
./gradlew publish :obfaryn-cli:distZip :obfaryn-cli:distTar
```

`publish` の既定の出力先は `build/repository` で、外部への送信は行いません。
ライブラリにはソース、Dokka Javadoc、MPL-2.0 ライセンス本文、POM メタデータが付属します。
CLI 配布物は `obfaryn-cli/build/distributions` に生成されます。全体を展開して
`bin/obfaryn`（Windows は `bin/obfaryn.bat`）を実行してください。
CLI の JAR 単体では依存関係が不足します。任意の Paper プロバイダーは同梱しません。

別のビルドで利用する設定例です。絶対パスを置き換えてください。

```kotlin
repositories {
  maven { url = uri("/absolute/path/to/Obfaryn/build/repository") }
  mavenCentral()
}
dependencies {
  implementation("com.peco2282.obfaryn:obfaryn-core:0.0.0-SNAPSHOT")
  // 拡張の実装のみなら obfaryn-api を使用します。
  // 任意: implementation("com.peco2282.obfaryn:obfaryn-platform-paper:0.0.0-SNAPSHOT")
}
```

Gradle プラグインでは、利用側の `settings.gradle.kts` の
`pluginManagement.repositories` に同じリポジトリ、`mavenCentral()`、
`gradlePluginPortal()` を追加し、ビルドの plugins ブロックで
`id("com.peco2282.obfaryn") version "0.0.0-SNAPSHOT"` を指定します。
プラグインマーカーも実装と一緒に公開されます。

外部公開前に `./gradlew test`、ローカル配布物の確認、および別プロジェクトからの
利用確認を行ってください。`python3 gradle/verify-distribution.py` でローカル公開物を検証できます。
バージョンを変更した場合は引数に指定します。Gradle の Maven アップロードに対応した認証付き公開先には、次を使います。

```shell
./gradlew publish -PreleaseVersion=0.1.0 -PpublishRepositoryUrl=https://your-host.example/releases
```

環境変数 `MAVEN_USERNAME` と `MAVEN_PASSWORD` で認証情報を渡します。
署名が必要なら `SIGNING_KEY`（ASCII armor 形式の PGP 秘密鍵）と
`SIGNING_PASSWORD` も設定してください。プラグインマーカーを含む全 Maven 公開物を署名します。
秘密情報をコミットしないでください。`SIGNING_KEY` がなければ署名は行いません。
`-SNAPSHOT` はスナップショット用、正式版は変更不可のリリース用リポジトリへ公開し、
公開済みバージョンを上書きしない運用としてください。

これは **Maven Central や Gradle Plugin Portal への公開済みという意味ではありません**。
Central には名前空間の確認、専用のステージング／バンドル送信と検証が必要です。
Portal も別途登録と公開が必要です。公開依存座標を案内する前に、実際の公開サービスと認証情報を準備してください。

[English](README.md)

Obfaryn は Kotlin/JVM および Java の JAR を対象とした ASM ベースの難読化ツールです。デバッグ情報の削除、文字列リテラルの暗号化、数値定数の変形、保守的なシンボル名の変更を行い、オプションとして制御フローの変換、メソッドの仮想化（VM化）、およびオフラインでの JAR 整合性検証を適用できます。

## 要件

ビルドには JDK 21 および Kotlin Gradle Plugin 2.4.21 を使用します。`obfaryn-api`、`obfaryn-annotations`、`obfaryn-core`、`obfaryn-transformer`、および CLI モジュールは Kotlin 言語/API バージョン 2.1 と Java 8 バイトコードを使用します。Gradle プラグインは Java 17 以降を必要とします。難読化対象の JAR に必要な Java バージョンは、入力ファイルと有効化された変換内容に依存します。

| モジュール | 用途 |
| --- | --- |
| `obfaryn-api` | プラットフォームおよびトランスフォーマーのインターフェース |
| `obfaryn-annotations` | ソースレベルの変換ポリシーアノテーション |
| `obfaryn-core` | 難読化エンジン本体 |
| `obfaryn-transformer` | 標準トランスフォーマー（文字列、数値、制御フロー、メタデータ） |
| `obfaryn-cli` | コマンドラインアプリケーション |
| `obfaryn-gradle-plugin` | Gradle 統合プラグイン |
| `platforms/paper` | 外部 Paper プラットフォームプロバイダー (`obfaryn-platform-paper`) |

## ライセンス

Obfaryn の独自ソースコード（API、アノテーション、core、transformer、CLI、Gradle プラグインを含む）は [Mozilla Public License 2.0](LICENSE) の下で提供されます。Obfaryn のソースファイルを改変して配布する場合は、そのファイルのソースを MPL 2.0 の条件で公開する必要があります。Obfaryn を単に利用する別ファイルには独自のライセンスを適用できます。Gradle Wrapper およびサードパーティ依存関係は、それぞれのライセンスを保持します。

## CLI

Windows の場合、アプリケーション配布物をビルドして実行します:

```powershell
.\gradlew.bat :obfaryn-cli:installDist
.\obfaryn-cli\build\install\obfaryn\bin\obfaryn.bat input.jar -o output.jar --platform paper --seed 123 --keep com.example.PublicApi
```

その他のプラットフォームでは `./gradlew` と生成された `bin/obfaryn` スクリプトを使用します。`--platform` は `auto`（既定値）、`generic`、および導入されたプラットフォームプロバイダーが提供する ID（`paper` など）を受け付けます。Paper の例では実行時クラスパスに `obfaryn-platform-paper` が必要です。

インストールされた配布物の `bin` ディレクトリを `PATH` に追加している場合:

```shell
obfaryn input.jar                         # 既定では入力と同じ場所に input-obfuscated.jar を出力
obfaryn --seed=42 input.jar --keep-debug-info
obfaryn obfuscate input.jar -o output.jar  # 明示的な obfuscate コマンドは省略可能
obfaryn @obfaryn.args
obfaryn --help
```

オプションは入力 JAR の前後に指定可能で、`--option=value` 形式もサポートしています。長い設定は引数ファイルで再利用できます。例 (`obfaryn.args`):

```text
input.jar
--output "build/protected jars/output.jar"
--seed 42
--keep 'com.example.api.**'
--keep-debug-info
```

空白や `#` を含む値はクォートで囲んでください。`--keep-debug-info` はソース/デバッグ情報を保持します。`--no-string-constant-fields` および `--no-number-constant-fields` はメソッド内のリテラル変換を無効化することなく、定数フィールドの展開処理のみを個別に無効化します。

### シェルの補完

シェルで補完スクリプトを生成して読み込むことで、コマンド、オプション、選択肢、ファイルパスの補完が可能になります:

```bash
obfaryn completion bash > obfaryn-completion.bash
source ./obfaryn-completion.bash
```

Zsh の場合（生成スクリプトが `compinit` / `bashcompinit` を初期化します）:

```zsh
obfaryn completion zsh > obfaryn-completion.zsh
source ./obfaryn-completion.zsh
```

生成したファイルを保存し、以降のセッションのために `.bashrc` / `.zshrc` から読み込んでください。CLI アップグレード後は再生成してください。

| オプション | 効果 |
| --- | --- |
| `--mapping path` | マッピングファイルの出力パスを指定。既定では名前変更時に入力/出力 JAR の隣に `*.mapping.txt` を生成。 |
| `--report path` | 変換件数と警告を JSON 形式で出力。 |
| `--dry-run` | JAR やマッピングを書き込まずに、計画された件数とマッピングのみを確認。 |
| `--verify-output` | ASM の `CheckClassAdapter` を使用して出力クラスを検証。 |
| `--classpath dependency.jar` | 外部依存関係を追加（複数指定可能）。 |
| `--recompute-frames` | StackMap フレームを再計算。 |
| `--application-package package` | アプリケーションパッケージルートを追加（複数指定可能）。 |
| `--strip-generic-signatures`, `--keep-method-parameters` | シグネチャおよびメソッドパラメータのメタデータを制御。 |
| `--no-string-encryption`, `--no-number-obfuscation`, `--no-rename` | 各難読化パスを個別に無効化。 |

`--keep` は完全一致クラス名、単一パッケージ用の `com.example.*`、再帰パッケージ用の `com.example.api.**`、メソッド用の `com.example.Class#method()V`、フィールド用の `com.example.Class#field` を受け付けます。メソッドシグネチャには JVM 記述子（descriptor）を使用します。`@KeepName` は宣言の名前のみを保持します。

保存されたマッピングを使用してスタックトレースを復元する場合:

```shell
obfaryn deobfuscate-stacktrace --mapping output.mapping.txt stacktrace.txt
```

クラス名および一意に識別可能なメソッド名を復元できます。削除された行番号は復元できません。

### 互換性ルール

Kotlin メタデータモードの既定値は `AUTO` です。選択されたプラットフォームプロバイダーが報告するエントリポイント、またはマニフェストのエントリポイントによってアプリケーションパッケージルートが確定します。それらのルート配下にあるアプリケーションクラスからはメタデータが削除されますが、他パッケージに含まれる同梱クラスは名前が保持されます。`--application-package` でルートを追加できます。`--preserve-kotlin-metadata` はメタデータを保持し、対象となる Kotlin クラスの名前変更を抑止します。`--strip-kotlin-metadata` は JAR 全体でメタデータを削除します。メタデータの削除は Kotlin リフレクションや `kotlinx.serialization` を破損させる可能性があるため、対象ランタイムでテストしてください。

仮想メソッドは JAR 内のオーバーライドファミリー単位でまとめて名前変更されます。外部 API（`Runnable.run` など）をオーバーライドするファミリーや、未解決の外部スーパークラスに依存するファミリーは保持されます。`--classpath` で依存関係を指定することで、階層およびアクセス解析の精度が向上します。フレーム再計算時に未解決の外部型が必要になった場合、Obfaryn は推測せずに失敗します。それ以外の場合は既存のフレームが保持されます。

enum クラス名は通常の keep ルールに従いますが、列挙定数名は `name()` や `valueOf()` のために保持されます。アノテーション型名は変更される可能性がありますが、アノテーション要素名は保持されます。外部コードや設定ファイルから参照される宣言は keep 指定してください。

アプリケーションパッケージ内の対象となる `static final` および Kotlin の `const val` 文字列・数値フィールドは、リテラル変換パスで処理できるように `ConstantValue` 属性からクラス初期化処理へ移動されます。明示的に keep されたフィールドは除外されます。これらのフィールドは Java のコンパイル時定数ではなくなるため、外部 API として公開する定数は keep してください。

## Gradle プラグイン

プラグイン ID は `com.peco2282.obfaryn` です。ローカルチェックアウトを利用する場合は、利用側プロジェクトの `settings.gradle.kts` に追加してください:

```kotlin
pluginManagement { includeBuild("../Obfaryn") }
```

利用側プロジェクトの `build.gradle.kts` で設定します:

```kotlin
plugins {
  java
  id("com.peco2282.obfaryn")
}

dependencies {
  compileOnly("com.peco2282.obfaryn:obfaryn-annotations:1.0-SNAPSHOT")
}

obfaryn {
  platform = "paper"
  seed = 123L
  verifyOutput = true
  recomputeFrames = true
  string {
    enabled = true
    includeConstantFields = true
    // strategy = XorStringEncryptionCodec() // 暗号化と復号コード生成を対で指定
  }
  number { enabled = true; includeConstantFields = true }
  metadata {
    stripDebugInfo = true
    kotlinMetadataMode = "auto" // auto, preserve, strip
    stripGenericSignatures = false
    stripMethodParameters = true
  }
  output {
    directory = "build/obfaryn"
    fileName = "app-obfuscated.jar"
    mapping = "build/mappings/app.txt"
  }
  applicationPackages = listOf("com.example.app")
  keep = listOf("com.example.api.*")
  rename {
    classes = true
    methods = true
    fields = true
  }
}
```

`obfuscateJar` は `shadowJar` が存在すればそれを、なければ `jar` を入力として使用します。出力設定を省略した場合は `build/libs/*-obfuscated.jar` とマッピングを生成します。プラグインはメソッドの名前変更、フレームの再計算、およびアノテーションで選択された仮想化のために、`runtimeClasspath` と `compileClasspath` を自動で供給します。

ネストされた `string` / `number` の `enabled` フラグおよび `metadata` のプロパティは、既存のフラットプロパティ（`stringEncryption`、`numberObfuscation`、`stripDebugInfo` など）と値を共有し、後に代入された設定が優先されます。Groovy DSL でも同じブロックが機能します。`includeConstantFields = false` はその定数種別に対する展開処理のみを無効化します。両方のリテラルブロックは任意の `random` ジェネレーターを受け付けます。省略した場合は既存の `seed` に従います。個別にカスタマイズする場合は `import java.util.Random` を追加し、`random = Random(10)` を設定します。`string.strategy` は暗号化と復号コード生成を対にする `StringEncryptionCodec` を受け付けます。数値には `number.strategy` と `number.selector` を指定できます。

`output.directory` / `fileName` で出力先フォルダーと名前をカスタマイズできます。明示的な `output.file = "dist/protected.jar"` は両方の設定に優先します。`mapping` を省略した場合は出力 JAR の隣に配置されます。パスはプロジェクト相対または絶対パスです。既定のディレクトリは Gradle の `buildDirectory` に従い、既存の `outputSuffix` も既定名に適用されます。入力 JAR、出力 JAR、およびマッピングのパスはすべて異なる必要があります。カスタム戦略や乱数ジェネレーターの状態を確実にフィンガープリントできないため、`obfuscateJar` は実行ごとに毎回実行されます。

## Core および Platform API

`Obfuscator(ObfuscationConfig(...)).obfuscate(input, output)` がコアのエントリポイントです:

```kotlin
val config = ObfuscationConfig(
  platform = "paper",
  platformConfiguration = {
    keepClass("com.example.PublicApi")
    keepMethod("com.example.Plugin", "publicEntry", "()V")
    keepResource("config.yml")
  },
)
Obfuscator(config).obfuscate(input, output)
```

Platform SPI はフレームワーク非依存です。Paper、Velocity、プライベートランタイム、プロジェクト固有の規約などはすべて同じ `Platform` インターフェースを実装します。`PlatformProvider` はメタデータを提供し、実行ごとに新しいプラットフォームインスタンスを生成します。`META-INF/services/com.peco2282.obfaryn.api.PlatformProvider` に登録してください。`detect` は信頼度スコアと理由を返し、`configure` は keep、リソース、リフレクション、および仮想化性能ポリシーを提供します。`entrypoints` は外部から呼び出されるライフサイクルクラスを報告します。ID は一意かつ空でない文字列である必要があり、`auto` は予約済みです。また、プロバイダーの API バージョンは `PlatformApi.VERSION` と一致している必要があります。

Paper サポートは core の外部の `platforms/paper` に配置されており、`com.peco2282.obfaryn:obfaryn-platform-paper` として公開されます。このプロバイダーが存在しない場合、core は `plugin.yml` を通常のリソースとして扱います。

プラットフォームの検出場所は意図的に SPI の一部として定義されていません。将来のローダーは CLI から任意の数の JAR ファイルやディレクトリを受け取る可能性があるため、実装は `platforms` という名前のディレクトリを前提にしてはなりません。プラットフォーム固有のフロントエンド設定には `PlatformOptions` を使用し、CLI からは繰り返し可能な `--platform-option key=value` 引数で、Gradle からは `platformOptions` で指定します。

```kotlin
class CustomPlatformProvider : PlatformProvider {
  override val metadata: PlatformMetadata = DefaultPlatformMetadata(
    id = "custom",
    displayName = "Custom Runtime",
    implementationVersion = "1.0.0",
  )

  override fun create(): Platform = CustomPlatform(metadata)
}

class CustomPlatform(
  override val metadata: PlatformMetadata,
) : Platform {
  override fun detect(context: PlatformContext): PlatformDetection =
    if ("custom-runtime.json" in context.resources)
      DefaultPlatformDetection(100, listOf("custom-runtime.json found"))
    else DefaultPlatformDetection(0)

  override fun entrypoints(context: PlatformContext, options: PlatformOptions): Set<String> =
    setOf("example/Bootstrap")

  override fun configure(
    context: PlatformContext,
    options: PlatformOptions,
    target: PlatformConfiguration,
  ) {
    target.keepResource("custom-runtime.json")
    target.keepClassNameOnly("example/Bootstrap")
    target.excludeMethodFromVirtualization("example/Bootstrap", "tick", "()V", "custom hot loop")
  }
}
```

プラットフォームは `Platform.extensions()` から制限付き拡張を返すことができます。`AnnotationPolicyProvider` はサードパーティのバイナリアノテーションを `obfaryn-annotations` に追加することなく、`NO_OBFUSCATION`、`RENAME_ONLY`、`KEEP_NAME`、`VIRTUALIZE`、または `HOT_PATH` にマッピングします。`ResourceContributor` は非クラス JAR エントリを追加し、`ClassContributor` はコンパイル済みの完全なクラスファイルを追加します。追加されたクラスは通常の Obfaryn パイプラインに入り、名前変更、パッケージ平坦化、本体変換、フレーム計算、および検証に参加します。

`ArchiveContributor` は `MERGE` と `EMBED_AS_RESOURCE` をサポートします。Merge は重複エントリ、署名付きアーカイブ、`module-info.class`、マルチリリースエントリを拒否し、マニフェストはコピーされません。埋め込みアーカイブは明示的なリソースパスを必要とし、データとして扱われます。ソースコードのコンパイルや任意の ASM トランスフォーマーの注入は、意図的にプラットフォーム拡張 API に含めていません。

## Transformer 拡張 API

初めて拡張を書く方向けの実装・実行例は [API 開発ガイド](obfaryn-api/README.ja.md) を参照してください。依存関係、コンテキスト、プラットフォームの登録、リテラル変換、検証手順を説明しています。

外部拡張は `obfaryn-api` の `Transformer` と `PipelineConfigurer` を実装し、ランチャーがこれらを core に渡します。文字列暗号化、数値難読化、制御フロー変換、定数フィールド変換、およびメタデータ削除は `obfaryn-transformer` に分離されています。このモジュールの唯一のプロジェクト依存関係は API です（バイトコード解析に ASM も使用します）。既存のパッケージ名は維持され、core がこのモジュールを推移的に公開します。

名前変更、仮想化、および標準パイプラインの組み立ては、設定、名前保持、および解析結果に関する API 契約が整うまで core に残ります。これは段階的な分離であり、まだ完全に独立した実行エンジンではありません。

```kotlin
import com.peco2282.obfaryn.api.transformer.PipelineConfigurer

val extension = PipelineConfigurer {
  // 実行間での状態共有を避けるため、ステートフルなパスはここで生成します。
  val customTransformer = CustomStringTransformer() // id = "string-encryption"
  if (this[customTransformer.id] != null) {
    replace(customTransformer.id, customTransformer)
  } else {
    this += customTransformer
  }
}

Obfuscator(config = config, pipelineConfigurers = listOf(extension))
  .obfuscate(input, output)
```

`registry["id"]` は登録済みのパスまたは `null` を返します。`add` / `+=` は重複 ID を拒否し、`disable` は未知の ID を拒否し、`replace` は既存の ID と一致する置換 ID を要求します。無効化された標準パスは登録されません。コンフィグラーはレガシーな `configurePipeline` コールバックの後にリスト順で実行され、その後に依存関係の欠落や循環が検証されます。依存関係は数値順よりも優先され、実行可能なパスは `order`、次いで ID の順に選択されます。同一 ID に対する複数回の置換は後勝ちです。

カスタム本体変換は、標準の本体変換やメソッドメタデータ削除と同様に、`context.transformationPolicy.canTransformBody(owner, method)` を参照する必要があります。クラス/フィールドのメタデータ削除は `canTransformMetadata(owner)` を参照し、定数変換は `canTransformConstant(owner, field)` を参照します。追加された 2 つのメソッドは既定で変更を許可し、SAM 構築を維持します。カスタムコンテキストでは既定ですべての変換が許可されます。ポリシーは推奨事項の確認用であり、ASM による変更を強制的に防ぐガードではありません。`@HotPath` を含む既存のアノテーションセマンティクスは変更されていません。

### 独自の数値変換方式

`obfaryn-api` のインターフェース `NumberObfuscationStrategy` を実装します。`generateInt(value, random)` と `generateLong(value, random)` は式の生成だけを担当します。`generateFloat` / `generateDouble` の既定実装は整数式とrawビット変換を使い、符号付きゼロとNaNペイロードを保持します。浮動小数点専用の式が必要な場合だけオーバーライドしてください。除外する場合は `null`、変換する場合は元のJVM型の値を積む新しい空でない `InsnList` を返します。既存のスタック・ローカル変数を維持し、分岐には有効なフレームを用意してください。実行時依存は自動同梱されません。

対象判定は別の `NumberObfuscationSelector.shouldTransform(site, random)` が担当します。コンテキスト・クラス・メソッド・命令・値を参照し、自作アノテーションや乱数で選択できます。渡されたASMノードは変更しない契約です。Transformerは本体変換ポリシー確認、対象判定、型の振り分け、命令置換を担当します。`DefaultNumberObfuscationSelector` は整数・長整数の -1〜1 を除外し、`DefaultNumberObfuscationStrategy` は従来の3種類の式だけを生成します。小さい整数も対象にする場合は `selector = NumberObfuscationSelector { _, _ -> true }` を指定します。

```kotlin
val passes = transformers {
  number {
    strategy = MyNumberStrategy()
    selector = NumberObfuscationSelector { site, random ->
      site.method.name != "hotLoop" && random.nextInt(100) < 70
    }
  }
  string { codec = MyStringCodec() }
}
```

`NumberObfuscationSelector` は `com.peco2282.obfaryn.api.transformer` からインポートします。コア設定には `numberStrategy` / `numberSelector`、Gradleには `number { strategy = ...; selector = ... }` を用意しています。フィールド定数は先行パスで命令へ変換されるため、フィールド単位の除外には定数変換ポリシーを使用してください。

### 独自の文字列暗号化方式

`obfaryn-api` の `StringEncryptionCodec` は、`encrypt(value, key): StringEncryptionResult` と `generateDecryptor(owner, method)` を対で実装する拡張口です。方式の実装自体は API モジュールだけに依存できます。標準パスのビルダーでは `string { codec = MyCodec() }`、直接置換する場合は `StringEncryptionTransformer(java.util.Random(10), MyCodec())` を `PipelineConfigurer` から登録してください。

復号メソッドはクラスごとに必要な場合のみ生成され、引数は暗号化結果の `payload: String` と `key: Int`、戻り値は元の文字列です。渡された `MethodNode` の名前・記述子・アクセスフラグを維持し、命令・ローカル変数数・スタック上限と、分岐に必要なスタックマップフレームを生成してください（ホストでフレーム再計算を有効にする場合を除く）。復号コードから参照する外部ライブラリは出力先の実行環境にも必要で、方式の実装クラスが自動で同梱されるわけではありません。

`codec` の既定値は `XorStringEncryptionCodec()` です。標準XORも独自方式も同じインターフェース・実行経路を使い、暗号化専用の `StringEncryptionStrategy` は削除しました。コアの `stringEncryptionStrategy` とGradleの `string.strategy` も `StringEncryptionCodec` を受け取ります。`encrypt` は難読化時の加工、`generateDecryptor` は出力プログラムで動く復元コードの生成です。アノテーションによる本体変換ポリシーは独自方式でも優先されます。

### 標準パスのビルダー

`obfaryn-transformer` は `TransformersBuilder` と、公開の `StringBuilder` / `NumberBuilder` / `MetadataBuilder` インターフェース（および内部実装）を提供します:

```kotlin
import com.peco2282.obfaryn.transformer.builder.transformers

val passes = transformers {
  string {
    codec = com.peco2282.obfaryn.transformer.XorStringEncryptionCodec()
    includeConstantFields = true
    random = java.util.Random(10)
  }
  number { includeConstantFields = false }
  metadata { stripDebugInfo = true }
}
```

`string()` および `number()` は登録時にオプションをスナップショットします。`includeConstantFields` の既定値は `true` です。`build()` は要求された値の型に対して最大 1 つの共有定数フィールドプリプロセッサを追加します。要求されていない型に対するパスは追加されません。文字列/数値の重複登録や、手動で追加された定数プリプロセッサとの競合は拒否されます。旧 `constants {}` / `ConstantsBuilder` / `ObfTransformers.constants*` エントリポイントはこれらのオプションに置き換えられました。戦略や乱数の引数は `string {}` または `number {}` 内に移動してください。ビルダーとパスは実行ごとに新しく作成してください。`build()` を繰り返し呼び出すとリストはスナップショットされますが、登録済みパスインスタンスは再利用されます。乱数の既定値はホストのシードと連動しないため、再現性が必要な場合はシード付きの `java.util.Random` インスタンスを渡してください。core は引き続き依存関係の検証とパスの順序付けを行います。標準パスを置き換えるには、`PipelineConfigurer` と `replace`（存在しない場合は `add`）を使用し、必要に応じてプリプロセッサも含めてください。

`context.transformationInputs` はアプリケーションスコープ、定数保持判定、およびクラスごとの Kotlin メタデータ削除判定を提供します。core はカスタムパスやフィルタリングされたコンテキストを含め、現在の実行からこれらを設定します。未知のスコープはすべてのクラスではなく、対象クラスなし（アプリケーションクラスなし）として扱われます。ルート、keep 述語、メタデータクラスセットを受け取る従来のコンストラクタ引数は引き続き利用可能ですが、新しいビルダーではユーザーオプションのみを指定します。明示的なルートはコンテキストのスコープを上書きし、keep 判定は合算され、メタデータ削除要求は加算的に適用されます。既存のアノテーションポリシーのチェックは変更されていません。標準パイプラインの組み立てと core から transformer への依存関係は、現時点では維持されています。

仮想化の選択および名前の保持は、現時点ではこの共通ポリシーに含まれていません。マッピング、件数、除外クラス内の参照更新に関する共通のレポート規約が確立されるまで、`rename` の置換はサポートされていません。Paper 固有の tick 検出は引き続き `platforms/paper` に配置されます。

## 正規表現除外と制御フロー

CLI は繰り返し可能な `--exclude-class regex`、`--exclude-resource regex`、`--exclude-renaming regex`、および `--exclude-virtualization regex` を受け付けます。正規表現は元の JVM 内部クラス名（`com/example/Type`）、リソースパス、または `owner.method(descriptor)` に対する**完全一致**です。不正なパターンは変換処理の開始前に失敗します。

`excludeClasses` は本体変換と名前変更を抑止しますが、名前変更された宣言への参照は引き続き更新されます。`excludeRenaming` は名前変更のみを抑止します。`excludeResources` はリソース変換を抑止しますが、不要になった古い JAR 署名は削除されます。そのため、除外されたエントリであってもバイト単位で同一であることは保証されません。

`--target-package hidden --flatten-packages` は、名前変更の対象となったクラスを共通パッケージへ集約します。両方の設定を同時に指定する必要があります。Obfaryn は保護された構造、パッケージプライベートアクセス、未解決の同一パッケージ内参照、または相対リソース取得を持つパッケージを保守的に保護し、警告を出力します。設定ファイル内に埋め込まれた任意のクラス名を書き換えるものではありません。

`--control-flow` は条件分岐の反転とキー付き switch ディスパッチを適用します。コンストラクタ、クラス初期化子、および大きなメソッドは対象外です。制御フローおよび仮想化を適用するとフレームの再計算が自動でトリガーされるため、クラスパスに外部依存関係が必要になる場合があります。

## メソッドの仮想化（VM化）

対象クラスまたはメソッドには `@Virtualize` アノテーションを使用することを推奨します。正規表現パターンによる選択は実験的機能であり、Gradle Kotlin DSL では `@file:OptIn(VirtualizeOptIn::class)` が必要です:

```kotlin
@file:OptIn(com.peco2282.obfaryn.gradle.VirtualizeOptIn::class)

obfaryn {
  virtualize {
    methods = listOf("com/example/logic/Calc\\.compute\\(I\\)I")
    excludeMethods = listOf("com/example/logic/Calc\\.tick\\(.*")
    runtimeGroupSize = 8
    mode = "strict" // strict または eligible
    analyzeOnly = false
    report = "build/reports/obfaryn/virtualization.json"
  }
}
```

CLI の対応オプションは `--virtualize-method regex`、`--exclude-virtualization regex`、`--virtualization-runtime-group-size N`、`--virtualization-mode strict|eligible`、`--virtualization-analyze-only`、および `--virtualization-report path` です。明示的な除外が優先されます。`strict` モードは選択されたメソッドに対象外のものが含まれていた場合、変換前に処理を中断して失敗します。`eligible` モードは非対応メソッドを JVM バイトコードのまま残し、警告を出力します。解析専用（analyze-only）モードでは JAR、マッピング、ランタイム、ブリッジは生成されません。

`@HotPath` はレイテンシに敏感な高頻度メソッドを仮想化から除外します。`@KeepName` は名前のみを保持します。`@RenameOnly` は名前変更を許可しますが本体変換を抑止します。`@NoObfuscation` は名前変更と本体変換の両方を抑止します。これらはバイナリ保持アノテーションであり、実行時依存関係は不要です。メソッドのアノテーションはクラスレベルの `@HotPath`、`@RenameOnly`、または `@Virtualize` を上書きできますが、クラスレベルの `@NoObfuscation` は最終決定となります。同一宣言に対して矛盾するアノテーションが指定された場合は検証でエラーとなります。

```kotlin
@HotPath
fun monitorEntities() { /* 頻繁に呼び出されるメソッド: JVM バイトコードのまま維持 */ }

@Virtualize
fun determineMatchResult(): Int = 1 // 呼び出し頻度が低く重要なメソッドを選択

@RenameOnly
fun updateProjectilePositions() { /* 本体の変換を行わずに名前のみ変更 */ }

@NoObfuscation
fun externallyReferencedEntryPoint() { /* 名前と本体の両方を維持 */ }
```

Paper プラットフォームはライフサイクルコールバック、`@EventHandler`、`Runnable`/`BukkitRunnable.run()V`、引数なしの `tick()V` / `onTick()V`、およびそれらから呼び出される JAR 内メソッドを自動的に除外します。解析レポート（スキーマバージョン 2）にはポリシーによる除外と理由が記載されます。その他の高頻度メソッドには `@HotPath` または明示的な除外パターンを使用してください。

コンパイラはサポート対象のバイトコードを型付きブロックベースの独自 IR に変換します。IR ベリファイアがローカル変数、分岐、スタック境界、値の種類、合流点を検証します。出力 JAR には専用のインタープリターと、選択されたフィールド、メソッド呼び出し、キャスト、配列、コンストラクタ操作用の整数 ID ブリッジが含まれます。実行時に Obfaryn 自体は不要です。サポートされる値には int 相当のプリミティブ、参照型、float、long、double、void 戻り値が含まれます。配列、分岐、ループ、throw、および特定のメソッド呼び出しがサポートされます。メソッド本体内のコンストラクタ、クラス初期化子、モニター、try/catch、任意の未初期化オブジェクト、switch、レガシーサブルーチンは仮想化されません。安全基準を満たす直線的な `NEW → DUP → 引数評価 → invokespecial <init>` コンストラクタ呼び出しのみがブリッジ化されます。

IR は JVM のローカルインデックスを保持し、long と double はインデックスを 2 つ進めます。float と double の値は raw ビット表現で保持されるため、負のゼロ（`-0.0`）や NaN もブリッジへそのまま伝達されます。復号済みプログラムは遅延キャッシュされ、スレッドごとに小さな実行フレームが再利用されます。ブロックの並べ替え、フォールスルーの明示化、条件反転、および特定の定数演算の融合が適用される場合があります。現在の演算融合は任意のハンドラー自動生成ではなく、定数との加算および乗算を対象としています。

`runtimeGroupSize` は正の整数で、既定値は 8 です。アクセス権を維持するため、グループは元のパッケージ内に留まります。値 1 はメソッドごとに独立したランタイムを生成するため分離強度は高まりますが、クラス数やメモリ使用量が増加します。16〜32 はオーバーヘッドを抑える設定です。シードが指定されている場合、同一の入力、設定、シード、ツールバージョンに対して再現性のある出力が生成されます。ランタイムコードと鍵は解析可能であり、仮想化は暗号学的な秘匿性を保証するものではありません。

各ランタイムグループは命令 ID、ワード幅、スロット配置、ヘッダー/マスク位置、分岐テーブル形式、エンコード鍵を変化させます。未使用のワードはランダム化され、以降の復号状態に寄与します。ブリッジは元の呼び出し元クラス内の synthetic static メソッドとして配置され、通常の JVM アクセス命令を使用するため、後続の名前変更に追従します。ランタイムは Java テンプレートから特殊化されるため、解析によってメソッドとプログラムの対応関係を復元可能です。

出力 JAR を生成せずに仮想化の適格性を確認する場合:

```shell
obfaryn input.jar --virtualize-method 'com/example/.*' --virtualization-analyze-only --virtualization-report virtualization.json
```

レポートには元のシンボルが含まれるため、公開配布物ではなくビルド情報として扱ってください。

JSON レポートにはスキーマバージョン 2、解析対象/選択/適格/拒否の各件数、ポリシー除外、命令位置付きのメソッド別理由、opcode および参照サマリー、推定 VM 命令数、ブリッジ数が含まれます。ラベル、フレーム、行番号ノードは命令数から除外されます。選択にマッチするメソッドが存在しない場合と、選択された中に適格なメソッドが存在しない場合は区別して報告されます。

## オフライン JAR 整合性検証

整合性保護を有効にするには、**両方**の RSA 鍵を指定します:

```shell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
obfaryn input.jar -o output.jar --integrity-private-key private.pem --integrity-public-key public.pem
```

Gradle 拡張機能および core 設定では `integrityPrivateKey` と `integrityPublicKey` を使用します。秘密鍵は非暗号化 PKCS#8 PEM/DER、公開鍵は X.509 SubjectPublicKeyInfo PEM/DER 形式である必要があり、RSA 鍵は 2048 ビット以上である必要があります。鍵が一致しない場合は出力前に失敗します。秘密鍵はソース管理や配布 JAR に含めないでください。

Obfaryn は最終的な JAR エントリのパスと内容の SHA-256 リストに対して SHA256withRSA で署名し、公開鍵のみを埋め込みます。検証は最初に対象となるクラスの初期化時に実行され、改ざん、欠落、追加、または重複したファイルが存在する場合に `SecurityException` をスローします。整合性メタデータエントリ自体はハッシュリストから除外されます。ディレクトリエンティティ、圧縮方式、タイムスタンプは検査されません。署名済みの入力を再処理することはできず、少なくとも 1 つの非除外クラスが必要です。検証にはファイルベースの JAR が必要であり、認証サーバーや Obfaryn ランタイムは不要です。実行中のメモリやファイルの継続的な監視を行うものではありません。ベリファイアと埋め込み公開鍵を差し替えることができる攻撃者は、この検証を回避できます。

Obfaryn の出力後に JAR を再変換する環境では、最終的なデプロイ成果物に署名する必要があります。標準の `jarsigner` も代替手段となりますが、その場合はアプリケーション側で保護対象エントリをすべて読み込み、信頼できる証明書や公開鍵を固定する必要があります（署名単体では未署名エントリの追加をすべて拒否できるわけではありません）。

## 安全性と現在の制限

- Obfaryn は `kotlin.Metadata` の書き換えを行いません。リフレクションやメタデータに依存するフレームワークを使用する場合は `preserve` を指定するか、ランタイムでテストしてください。
- リフレクションや設定ファイルに記述された名前には明示的な keep ルールが必要になる場合があります。Paper/マニフェストのエントリポイントおよび ServiceLoader 名には組み込みの保護が適用されます。
- マルチリリース JAR のバージョン付きクラスは、名前変更対象のアプリケーションパッケージへの参照がないと Obfaryn が判断できる場合にのみ保持されます。それ以外の場合は処理が失敗します。
- VM サポートは特定のバイトコードパターンを対象としており、すべての JVM 命令を網羅しているわけではありません。VM の強化、パフォーマンスバジェット、耐性の評価は今後の課題です。
- 整合性保護は署名されたマニフェストに従って変更を検出します。自己完結型の JAR 単体で改変やリバースエンジニアリングを完全に不可能にすることはできません。

### 組み込みパイプラインのカスタマイズ

```kotlin
val obfuscator = Obfuscator(
  config = ObfuscationConfig.lightweight().copy(seed = 42),
  configurePipeline = {
    add(MyTransformer())
    // replace("rename", MyRenameTransformer()) // 同じ ID を維持
    // disable("rename")
  },
)
val result = obfuscator.obfuscate(input, output)
println(result.transformerReports)
```

`lightweight()` は文字列および数値の本体変換を無効化し、名前変更とメタデータのクリーンアップを維持します。既存のコンストラクタの既定値は変更されていません。パイプラインの編集は実行ごとに新しい組み込みパスに適用されます。設定で無効化されたオプションに対するパスは生成されません。重複 ID、未知の置換/削除 ID、欠落した `Transformer.requires` 依存関係、および依存関係の循環は、パスの実行前に失敗します。依存関係は数値の `order` より優先され、それ以外は `order`、次いで ID 順で並べ替えられます。カスタムパスは `report()` からメトリクスと警告を返すことができます。組み込みの結果カウンターとマッピング出力は組み込みパスのみを対象としています。カスタムの名前変更パスを導入する場合は、独自にマッピングを提供する必要があります。既存の全リスト指定コンストラクタも引き続き利用可能です。

`@HotPath` は文字列、数値、制御フロー、および仮想化の本体変換を除外します。クラスレベルで指定した場合、定数フィールドの展開も抑止します。メソッドレベルの `@Virtualize` はクラスレベルの `@HotPath` を上書きできます。プラットフォームのコールバック検出は引き続き仮想化のみを除外します。
# 変換の拡張

Gradle DSLから独自パスを登録できます。実行ごとに新しいレジストリへ設定を適用するため、状態を持つTransformerはブロック内で生成してください。

```kotlin
import com.peco2282.obfaryn.api.transformer.*

obfaryn {
  pipeline {
    add(MyTransformer())
    // disable("number-obfuscation")
    // replace("string-encryption", MyStringTransformer()) // 同じIDを使用
  }
}
```

パスの必須依存は`requires`、任意の実行順は`before` / `after`で指定します。任意の対象が未登録なら無視し、循環は実行前にエラーにします。実行可能なパスの優先順位には`order`を使用します。新しいスタックマップフレームが必要な独自パスは`requiresFrameRecomputation = true`を指定し、必要な依存JARを`classpath`へ追加してください。

`context.forEachTransformableMethod { owner, method -> ... }`は、メソッド一覧のスナップショットに対して本体変更ポリシーを確認して走査します。`owner.uniqueMethodName("helper")`は未使用の補助メソッド名を返します。任意のバイトコード変更の検証やランタイム依存の登録は行いません。生成するメソッドには正しい本体とスタック・ローカル変数の上限を設定してください。

数値の検出処理を書き直さずに、複数の生成方式と適用率を組み合わせられます。

```kotlin
import com.peco2282.obfaryn.api.transformer.*
import com.peco2282.obfaryn.transformer.*

obfaryn {
  number {
    strategy = WeightedNumberObfuscationStrategy(listOf(
      WeightedNumberStrategy(DefaultNumberObfuscationStrategy(), 3),
      WeightedNumberStrategy(MyNumberStrategy(), 1),
    ))
    selector = DefaultNumberObfuscationSelector() and NumberObfuscationSelector.probability(0.75)
  }
}
```

例の`MyTransformer`、`MyStringTransformer`、`MyNumberStrategy`は利用者が実装するクラスです。重みは正の整数で指定し、選択された方式が`null`を返すと再試行せずその定数を保持します。float/double専用実装も維持されます。選択には渡された乱数源を使い、シードと設定が同じなら再現できます。方式の種類を増やすだけで解析耐性が保証されるわけではありません。
