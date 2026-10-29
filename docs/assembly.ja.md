# Obfarynの組み立て

[English](assembly.md)

実行エンジンと標準構成は別の依存関係です。利用者はこのリポジトリの外で、**`obfaryn-api`だけに依存する拡張**を定義できます。独自パッケージ・Transformer・機能IDを本体へ登録する必要はありません。

| モジュール／契約 | 責務 |
| --- | --- |
| `obfaryn-api` | パーツ・構成パッケージ・platform・改名結果・ポリシー・出力処理の契約 |
| `obfaryn-core` | JAR入出力、構成の組み立て、順序、元識別子、予算、検証、報告。transformer／standardへの依存なし |
| `obfaryn-transformer` | 選択して使う標準の変換パーツ・ビルダー |
| `obfaryn-standard` | 標準構成、改名、VM化、整合性保護、設定付きの`Obfuscator` |
| platform | 環境検出、互換性ルール、生成物、専用パッケージの提供 |
| CLI／Gradle | 利用者向けの設定と拡張の明示選択 |

既存の`com.peco2282.obfaryn.core.Obfuscator`や設定・改名・VM関連の型名は維持していますが、それらの実装は**`obfaryn-standard`へ移動**しました。利用側の依存をcoreからstandardへ変更してください。標準機能を必要としない実行エンジンは`TransformationEngine`です。

## 自分のプロジェクトで定義する

設定済みのMavenリポジトリから`com.peco2282.obfaryn:obfaryn-api:<version>`へ依存し、`Transformer`を実装します。パーツを束ねる処理も利用者側で書けます。

```kotlin
import com.peco2282.obfaryn.api.assembly.TransformationPackage

val myPackage = TransformationPackage { assembly, pipeline ->
  // パッケージは再利用できるため、状態を持つパーツはこの中で生成する。
  pipeline.add(MyTransformer(assembly.random))
}
```

パッケージは共通の`TransformerRegistry`を使い、追加・無効化・置換できます。全パッケージと最終調整が終わった後で、ID重複・必須依存・循環を検査します。同じIDの暗黙上書きはありません。`before`／`after`は任意の相手との順序、`requires`は相手の存在も要求します。

実行側はcoreと拡張モジュールに依存します。

```kotlin
import com.peco2282.obfaryn.core.EngineOptions
import com.peco2282.obfaryn.core.TransformationEngine
import java.nio.file.Paths

val engine = TransformationEngine(EngineOptions(
  applicationPackages = listOf("com.example"),
  seed = 42L,
))
engine.transform(Paths.get("input.jar"), Paths.get("output.jar"), packages = listOf(myPackage))
```

generic platformで追加platformもない場合、空リストなら**変換パスを実行しません**。選択したplatformがパッケージを提供する場合は、そのパッケージも適用します。JARの正規化、無効な署名の除去、検証は入出力側の処理なので、ZIPのバイト一致を保証する意味ではありません。標準構成を加える場合はstandardモジュールの`com.peco2282.obfaryn.standard.StandardPackage()`をリストへ追加します。

standard側の`Obfuscator`は既存設定に基づく標準構成を既定で使用します。`packages = emptyList()`でそれを外せます。`pipelineConfigurers`は、利用者とplatformのパッケージ適用後の最終調整です。

`EngineOptions`は対象範囲・依存・ポリシー・予算・出力検証を担当し、`StandardPackage(ObfuscationConfig(...))`は標準パーツと方式の選択を担当します。従来の設定全体、解析のみの実行、マッピングや詳細レポートのファイル出力が必要なら、standard側の設定付き`Obfuscator`を使ってください。

## 拡張JARとして配る

公開された引数なしコンストラクターを持つ`TransformationPackageProvider`を実装し、安定した`id`と`create(options)`を定義します。次のサービスファイルに実装クラス名を登録します。

```text
META-INF/services/com.peco2282.obfaryn.api.assembly.TransformationPackageProvider
```

JARを導入しただけではパッケージは実行されません。ホストが`TransformationPackages.select(...)`でIDを明示選択します。IDは空白不可・重複不可で、`none`は予約語です。生成時は文字列オプション、構成時は入力・共有設定・アプリケーション範囲・依存・実行用乱数を受け取れます。

[独自拡張のサンプル](../examples/custom-extension/src/main/kotlin/example/extension/ExamplePackage.kt)はAPIだけでコンパイルします。独自機能ID、メソッドの変換許可確認、クラス単位の予算巻き戻しに対応し、同じパッケージを専用platformからも提供します。core・transformer・standard実装には依存しません。

## CLI

```shell
obfaryn input.jar -o output.jar --extension my-extension.jar \
  --no-default-package --package my-package --platform-option rounds=2
```

`--extension`はその実行中のホスト側にJARを追加します。拡張の依存JARも繰り返し指定でき、アプリケーションの出力へは混入しません。`--package`は明示選択する構成です。`--no-default-package`を省略すると設定済みの標準構成へ追加します。標準構成にさらに`--package standard`を重ねるなど、同一パスIDの重複はエラーです。通常呼び出しと`obfuscate`の両方に対応します。

`--platform paper --also-platform my-framework`でplatformを明示順に合成できます。主platformの自動検出が同点なら失敗し、追加platformは名前指定が必要です。クラス・リソースの重複追加、アノテーション方針の矛盾、パスIDの衝突はエラーになります。

## Gradle

```kotlin
dependencies {
  obfarynPlatform(files("my-extension.jar"))
}
obfaryn {
  useDefaultPackage = false
  packageIds = listOf("my-package")
  additionalPlatforms = listOf("my-framework")
  pipeline {
    // 利用者とplatformのパッケージを適用した後の最終調整。
  }
}
```

Groovyでは`packageIds = ['my-package']`、`useDefaultPackage = false`と指定します。`obfarynPlatform`はplatformとパッケージのproviderを読み込み、アプリケーションのcompile/runtime依存には追加しません。ビルドやconvention pluginから`transformationPackage(myPackage)`で直接渡すこともできます。状態を持つパーツはパッケージまたは`pipeline`のコールバック内で生成してください。

## 専用ツールと高度な拡張

platformの`extensions()`から`PlatformPackages { context, options -> listOf(myPackage) }`を返すと、専用パーツも共通の登録経路を使います。生成物の追加後、利用者のパッケージ、選択したplatformのパッケージ、最終調整の順に適用します。coreの`EngineOptions.additionalPlatforms`は利用者が直接定義したplatformオブジェクトも受け付け、ライブラリ側で`CompositePlatform`を作ることもできます。合成した全platformの生成物を追加してからアノテーション方針を適用するため、別platformが追加したクラスも対象になります。

独自の改名パスは`RenamingTransformer`を実装し、改名前のクラス・メンバー識別子に対応するマッピングを公開します。`context.namePreservation`でplatformや後続パッケージが追加したクラス・メンバー・リソースの保持要件を参照します。階層やリフレクションの安全性確認と参照の書き換え自体はパスの責任です。エンジンはこの結果を使い、メソッド順や記述子が変わっても元識別子とアプリケーション範囲を追跡し、マッピング文字列を返します。1実行につき改名パスは1個までで、標準の実装クラスである必要はありません。

独自の本体変換は`BodyFeature("my-tool:feature")`を`isolatedBodyFeature`に指定できます。変更前に`canTransformBody(owner, method, feature)`を確認し、直近の呼び出し件数を`transformedCount`で返してください。本体への機能登録は不要で、標準機能と同じ元名ルール・予算巻き戻し・メソッド報告が使えます。APIは`ProtectionRule(pattern, mapOf(feature to false))`、Gradleは`rules { method("com/example/.*") { feature("my-tool:feature", false) } }`、CLIは`--disable-feature 'my-tool:feature=com/example/.*'`です。CLIの独自IDにはコロンが必要です。既存アノテーションの個別プロパティは標準機能用のままですが、一般的な本体制限と構造予算は独自機能にも適用します。

構成時の`AssemblyContext`には`excludeClass`・`excludeResource`・`protectInput`があります。除外は通常パスの列挙から隠し、名前も保持しますが、依存調査の直接検索は可能です。`protectInput`は他の入力と同じパス同一性チェックでホストの成果物による上書きを拒否します。拡張自身が追加ファイルを出力する場合、その出力先の検証は拡張の責任です。

出力ツールは`OutputProcessor`を登録します。`beforeWrite`はパスの後でdry runでも呼ばれ、`afterWrite`はJAR出力成功後だけ呼ばれます。フックの順序は登録順です。標準の整合性保護もこの契約を使います。任意の拡張コードを実行する仕組みであり、セキュリティ用サンドボックス、ファイルロック、複数成果物のトランザクションではありません。

## 検証

JDK 21で実行します。

```shell
bash gradlew test :obfaryn-cli:installDist :examples:custom-extension:jar
python3 scripts/verify-cli-distribution.py
python3 scripts/verify-custom-extension.py
```

coreテストには標準実装・標準Transformerを含めません。独自の予算・ルール・改名・出力フックを検証し、TestKitでKotlin/Groovy両方のパッケージ選択を確認します。配布検証スクリプトは、インストール済みCLIへAPIのみの拡張を明示ロードしてJava 8サンプルを処理し、出力JARだけを`-Xverify:all`で実行します。JVM上の確認であり、Paper実機動作や全環境の互換性を保証するものではありません。
