# Obfaryn

[English](README.md)

Kotlin/JVM と Java の JAR を対象にした ASM ベースの難読化ツールです。Java 8 以降の JAR を対象に、デバッグ情報の削除、文字列暗号化、整数定数の変形、保守的な名前変更を行います。

## 公開 API と実行環境

`obfaryn-api`、`obfaryn-annotations`、`obfaryn-core` と CLI は Kotlin language/API 2.1、Java 8 bytecode で公開します。Gradle Plugin は Java 17 以上で動作します。ビルドには JDK 21 と Kotlin Gradle Plugin 2.4.21 を使用します。各モジュールの Kotlin 言語/API バージョン、標準ライブラリの下限、JVM target はルートの `build.gradle.kts` で設定しています。難読化対象の JAR に必要な Java バージョンは、その入力 JAR と変換内容に依存します。

## ライセンス

Obfaryn の独自ソースコード（API、アノテーション、core、CLI、Gradle Plugin を含む）は [Mozilla Public License 2.0](LICENSE) で提供します。Obfaryn のソースファイルを改変して配布する場合は、そのファイルのソースを MPL 2.0 の条件で提供してください。Obfaryn を利用する別ファイルには独自のライセンスを選べます。Gradle Wrapper と外部依存ライブラリには、それぞれのライセンスが適用されます。

## CLI

```powershell
.\gradlew.bat :obfaryn-cli:installDist
.\obfaryn-cli\build\install\obfaryn\bin\obfaryn.bat input.jar -o output.jar --platform paper --seed 123 --keep com.example.PublicApi
```

`--platform` は `auto`、`generic`、および導入済みPlatform ProviderのIDを受け付けます。`paper` を指定する例では、実行時classpathに `obfaryn-platform-paper` が必要です。`--mapping path` でマッピング先を指定できます。省略すると名前変更が発生した場合に出力 JAR と同じ場所に `*.mapping.txt` を作成します。`--report report.json` は処理件数と警告を JSON で出力します。`--dry-run` は変換件数を調べますが JAR と Mapping を書きません。`--verify-output` は出力時に ASM の `CheckClassAdapter` を使用します。`--strip-generic-signatures`、`--keep-method-parameters`、`--no-string-encryption`、`--no-number-obfuscation`、`--no-rename` も指定できます。

Kotlin Metadata は既定で `AUTO` です。選択されたPlatform Providerが報告するエントリポイント、または Manifest の Main-Class 等がある場合、そのパッケージ配下のアプリクラスだけ Metadata を除去して Rename します。JAR に同梱された他パッケージの依存ライブラリは名前を保持します。明示的に範囲を追加するには `--application-package com.example.app` を指定します。`--preserve-kotlin-metadata` は Metadata を保持し、対象 Kotlin クラスの Rename を抑制します。`--strip-kotlin-metadata` は JAR 全体を対象にします。Metadata 除去は Kotlin reflection や kotlinx.serialization 等に影響する可能性があり、実行時の互換性を要確認です。

`--recompute-frames` は ASM の StackMap Frame を再計算します。外部型が必要な JAR では `--classpath dependency.jar` を複数回指定できます。CLI は依存クラスが不明なメソッドを保守的に Keep します。階層を解決できない状態で Frame 再計算を要求した場合は、誤った Frame を推測せず対象クラス名を含むエラーにします。Frame 再計算を指定しない場合は既存 Frame を保持します。

メソッド名は JAR 内の interface・superclass・実装クラスの override family 単位で変更します。内部 enum が interface を実装していても同じ名前を割り当てます。JDK や `--classpath` 上の外部 API を override する family（例: `Runnable.run`）と、外部の親クラスを解決できない family は保持します。外部 API を使う場合は依存 JAR を `--classpath` で指定すると、不要な Keep を減らせます。

enum クラス名は、通常のクラスと同じ Keep 条件に従って変更します。列挙子名は `name()`、`valueOf()`、設定値として使われるため保持します。enum の private field と、内部で参照先を更新できる `Companion` field は名前変更します。
JAR 内のアノテーション型も、通常の Keep 条件に従って型名を変更します。アノテーション要素名は保持し、付与箇所の型参照は更新します。外部から名前で参照される型は Keep 指定してください。

アプリパッケージ内の `static final` / Kotlin `const val` の文字列・数値は `ConstantValue` 属性からクラス初期化コードへ移し、既存の文字列暗号化・数値変形に通します。対象パッケージは Paper/Manifest のエントリポイントまたは `--application-package` で決まります。明示的に Keep したフィールドは変更しません。変換後のフィールドは Java ソースからコンパイル時定数として使えなくなるため、外部ライブラリとして公開する定数は Keep してください。

```powershell
obfaryn deobfuscate-stacktrace --mapping output.mapping.txt stacktrace.txt
```

スタックトレースはクラス名と一意に判定できるメソッド名を復元します。元の行番号はデバッグ情報削除時には復元できません。

`--keep` は完全一致クラス、`com.example.*` のパッケージ、`com.example.api.**` の再帰パッケージ、`com.example.Class#method()V` のメソッド、`com.example.Class#field` のフィールドを受け付けます。メソッドの引数・戻り値は JVM descriptor で指定します。名前だけをソースから保持する場合は `obfaryn-annotations` の `@KeepName` を使用します。

## Gradle Plugin

Gradle Plugin の ID は `com.peco2282.obfaryn` です。ローカルのプラグインを利用する場合は、このビルドを `settings.gradle.kts` から `includeBuild` してください。

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("../Obfaryn") }

// build.gradle.kts
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
  stringEncryption = true
  numberObfuscation = true
  verifyOutput = true
  reproducible = true
  recomputeFrames = true // runtimeClasspath と compileClasspath を参照
  kotlinMetadataMode = "auto" // auto, preserve, strip
  applicationPackages = listOf("com.example.app")
  keep = listOf("com.example.api.*")
  rename {
    classes = true
    methods = true
    fields = true
  }
}
```

`obfuscateJar` は `shadowJar` が存在すればそれを、なければ `jar` を入力にし、`build/libs/*-obfuscated.jar` と対応するマッピングを生成します。Gradle の `runtimeClasspath` と `compileClasspath` は、メソッド Rename、Frame 再計算、およびアノテーションで選択されたVM化の階層・アクセス解析に自動で使います。CLI より多くの内部メソッドを安全に改名できます。

## Platform API / Core 設定

`Platform<C : PlatformConfiguration>` は設定生成 (`createConfiguration`)、既定設定 (`configure`)、リソース保護・VM性能ポリシー (`configureResources`)、エントリポイント (`entrypoints`) を提供します。`PlatformConfiguration.excludeMethodFromVirtualization(owner, name, descriptor, reason)` は通常のJVM bytecodeへ残すメソッドを指定します。この除外はVM化だけに作用し、他の難読化は維持します。`configure { ... }` は既定設定を適用した新しい設定を返します。設定はプラットフォームに保存されないため、返り値を使用してください。

core は `applyConfiguration` で設定を生成し、`PlatformConfiguration.applyTo` で実行用の Keep ルールへ反映します。独自設定はこのメソッドで保持ルールを渡してください。core の `KeepRules` は全種類のルールを別の `KeepRules` に合成します。

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

独自プラットフォームは `PlatformProvider` を実装し、`META-INF/services/com.peco2282.obfaryn.api.PlatformProvider` にProviderクラス名を登録します。Providerはメタデータを公開し、実行ごとに新しい `Platform` を生成します。自動選択では導入済みProviderの検出結果を比較し、該当しなければ `generic` を使用します。ID は一意かつ空でない文字列とし、`auto` は予約済みです。ProviderのAPIバージョンは `PlatformApi.VERSION` と一致する必要があります。

Paper対応はcore外の `platforms/paper` にあり、`com.peco2282.obfaryn:obfaryn-platform-paper` として公開します。このProviderが存在しない場合、coreは `plugin.yml` を通常のリソースとして扱い、Paper固有のKeep・ホットパス判定を行いません。

## 正規表現除外・集約・制御フロー・仮想化

core の `ObfuscationConfig` と Gradle の `obfaryn` に次の設定を追加しています。

```kotlin
@file:OptIn(com.peco2282.obfaryn.gradle.VirtualizeOptIn::class)

obfaryn {
  excludeClasses = listOf("com/example/excluded/.*")
  excludeResources = listOf("assets/.*", "config\\.yml")
  excludeRenaming = listOf("com/example/fixed/.*")
  targetPackage = "hidden"
  flattenPackages = true
  controlFlow = true
  virtualize {
    methods = listOf("com/example/logic/Calc\\.compute\\(I\\)I")
    excludeMethods = listOf("com/example/logic/Calc\\.tick\\(.*")
    runtimeGroupSize = 8 // 小さいほど強度寄り、大きいほどクラス数・metaspaceを節約
    mode = "strict" // strict, eligible
    analyzeOnly = false
    report = "build/reports/obfaryn/virtualization.json"
  }
}
```

- 正規表現は変換前の JVM 内部クラス名（`com/example/Type`）、リソースパス、または `owner.method(descriptor)` に**全体一致**させます。不正な式は処理開始前に拒否します。
- `excludeClasses` はコード・メタデータ変換と名前変更を抑止します。ただし他クラスの改名に追従する型・メンバー参照の更新と再出力は行い、バイト単位の同一性は保証しません。`excludeRenaming` はクラスとメンバーの名前変更だけ抑止します。`excludeResources` はリソース変換対象から除外しますが、無効になる既存 JAR 署名は引き続き削除します。
- `flattenPackages = true` と `targetPackage` の組み合わせで、改名可能なクラスを同一パッケージへ集約します。意図しない移動を避けるため、`targetPackage` だけの指定は拒否します。名前保持・特殊構造、package-private メソッド（コンストラクター以外）やフィールド、相対リソース取得などがある元パッケージは保守的に移動せず、警告します。移動しないパッケージ内でも通常の改名は可能です。名前が埋め込まれた任意の設定ファイルを自動更新する機能ではありません。
- `controlFlow` は条件分岐の反転とランダムキーの switch 経由のディスパッチを行います。コンストラクター・初期化子と大きなメソッドは対象外です。完全な状態機械への平坦化ではなく、軽量な変形です。
- VM化は `@Virtualize` による対象の明示を推奨します。正規表現による一括指定は `virtualize { methods = ... }` にまとめた高度な実験APIで、Kotlin DSLでは `@file:OptIn(VirtualizeOptIn::class)` が必要です。Opt-Inのコンパイルメッセージでも `@Virtualize` を案内します。`@Virtualize` だけを使う場合、Gradle DSL側のOpt-Inは不要です。選択した static / instance メソッドは符号化命令列と JAR 内の専用インタープリターに置換されます。int 系・reference・float・long・double 引数と戻り値、void 戻り値、数値演算・ローカル変数・比較・条件分岐・ループ・throw、配列の生成・length・読書きが対象です。field、static / virtual / interface invocation、安全な special invocation、cast / instanceof、配列生成、制限付きconstructorは整数 ID の Bridge 経由で実行します。実行に Obfaryn は不要ですが、元コードの依存ライブラリは引き続き必要で、実行コストは増えます。
- `excludeVirtualization` は `virtualizeMethods` より優先されます。tick、物理演算、packet処理などプロファイラで高頻度と判明したメソッドを通常のJVM bytecodeに残すための正規表現です。除外しても文字列・数値・制御フロー・名前変更など他の変換は適用されます。
- `@Virtualize` はクラスまたはメソッドを、正規表現なしでVM化対象にします。`@HotPath` は高頻度なクラスまたはメソッドを通常のJVM bytecodeへ残します。`@KeepName` は名前だけを保持して本体変換を許可します。`@RenameOnly` は名前変更だけを許可し、文字列・数値・制御フロー・VM化など本体の変換を抑止します。`@NoObfuscation` は名前変更と本体変換の両方を抑止しますが、他クラスの改名に必要な型・メンバー参照は追従更新します。すべて `obfaryn-annotations` のBINARY annotationで、実行時依存は不要です。
- メソッド指定はクラスの `@RenameOnly` / `@HotPath` / `@Virtualize` を上書きできます。クラスの `@NoObfuscation` はクラス全体に対する終端指定です。明示的な `excludeVirtualization` はVM化指定より優先されます。同じ要素の `@Virtualize` と除外系、または `@RenameOnly` と `@NoObfuscation` のような矛盾した組み合わせは変換前に失敗します。
- Paper Platform は `BukkitRunnable` / `Runnable` の `run()V`、引数なしの `tick()V` / `onTick()V`、`@EventHandler`、plugin lifecycleを性能保護対象として自動検出します。さらに、それらから直接・間接に呼ばれるJAR内メソッドもホット経路としてVMから除外します。単に名前へ `tick` を含むgetter等は除外しません。解析JSON schema 2では `excludedByPolicy` と各メソッドの `exclusionReason` で判断を確認できます。
- 制御フロー・仮想化を有効にすると Frame を自動再計算します。外部依存は classpath に必要です。

```kotlin
import com.peco2282.obfaryn.annotations.HotPath
import com.peco2282.obfaryn.annotations.NoObfuscation
import com.peco2282.obfaryn.annotations.RenameOnly
import com.peco2282.obfaryn.annotations.Virtualize

@HotPath
fun monitorEntities() {
  // 毎tick呼ばれるためVM化しない
}

@Virtualize
fun determineMatchResult(): Int {
  // 低頻度で重要な判定だけVM化する
  return 1
}

@RenameOnly
fun updateProjectilePositions() {
  // 名前は難読化するが、高頻度な本体は通常のJVM bytecodeのまま
}

@NoObfuscation
fun externallyReferencedEntryPoint() {
  // 名前と本体を維持する
}
```

### VM 設計と実装状況

ASM 命令は `JvmToVirtualCompiler` が JVM opcode に依存しない意味ベースの操作と、`VirtualBlock` / `VirtualTerminator` を持つ独自 IR へ変換します。IR verifier が local 範囲、分岐先、スタック容量・underflow・INT / REFERENCE / FLOAT / LONG / DOUBLE kind・合流点の高さと型を検証します。JVM local index を保持し、instance の local 0 は this、long / double は JVM と同じく local index を2つ進めます。floatはraw int bits、longとdoubleはraw long値の専用local / stackに保持し、浮動小数点の`-0.0`とNaNを含む値をBridgeまで運びます。nullと整数0は区別します。復号済みprogramはRuntime内に遅延キャッシュし、local / stackフレームはスレッド単位・再入可能な小容量プールから再利用します。未使用の引数種別配列はwrapperで生成しません。ブロックの並べ替え、fallthrough の明示 jump 化、条件反転、定数と加算・乗算の2命令融合を seed に応じて適用します。融合候補は現段階ではこの2種類に固定で、頻出列から任意の handler を自動生成する方式ではありません。

`virtualizationRuntimeGroupSize` は正の整数で既定値8です。アクセス制御を維持するため、同じ元パッケージ内でグループ化します。1はメソッドごとの専用Runtimeになり解析分離は強い一方、大規模JARではクラス数・JITコード・metaspace・ThreadLocal保持量が増える強度優先設定です。通常は8、軽量化を優先する場合は16〜32を目安にします。グループごとに命令番号、命令幅（3〜6 ワード）、slot 配置、ヘッダー位置・mask、分岐表形式（table/lookup switch）、状態更新型 word stream の方式と鍵素材を変えます。wrapper は戻り値に応じて `executeInt` / `executeReference` / `executeFloat` / `executeLong` / `executeDouble` / `executeVoid` を呼び、旧 static int の `execute` も維持します。鍵と layout は Runtime に埋め込みます。未使用 word は乱数化し、復号状態に全 word を混ぜるため後続の復号にも関与します。同じ入力・設定・seed・ツールバージョンでは再現可能です。CLI は `--virtualization-runtime-group-size N` で指定します。

Runtime はまだ Java テンプレートを ASM で特殊化する方式です。鍵素材や命令の意味は Runtime を解析すれば復元でき、暗号学的な秘匿や AI に対する安全性を保証するものではありません。グループ内で Runtime は共有され、メソッドと program の1対1対応はまだ残ります。

`VirtualizationAnalyzer` はノードを変更せず、選択・適格性・性能ポリシー除外・全拒否理由と命令位置、opcode・型・field / invoke の参照と件数、実命令数・推定 VM 命令数・Bridge 数を報告します。Label / Frame / LineNumber は実命令数に含めません。既定の `strict` は選択された非対応メソッドを全件まとめ、変換前に失敗します。`eligible` は非対応メソッドを残して理由を警告します。選択ゼロと適格ゼロは区別します。core の設定名は `virtualizationSelectionMode`、`virtualizationAnalyzeOnly`、`virtualizationReport` です。

```shell
obfaryn input.jar --virtualize-method 'com/example/.*' --virtualization-analyze-only --virtualization-report virtualization.json
obfaryn input.jar -o output.jar --virtualize-method 'com/example/.*' --virtualization-mode eligible
```

解析専用では変換 pipeline、JAR、mapping、Runtime、Bridge を生成しません。JSON は `schemaVersion: 2`、`classesAnalyzed`、`methodsAnalyzed`、`selected`、`eligible`、`rejected`、`excludedByPolicy`、`methods`、`rejectionSummary` を含む決定的な形式です。レポートは変換前のシンボルを含むため、配布物ではなくビルド用情報として管理してください。

BridgeRegistry は runtime group ごとに独立した seed ベースのランダム整数 ID を使用します。VM blob に owner / field name / method name / descriptor を格納しません。Bridge は元のアクセス元クラス内の synthetic static method として生成し、通常の FieldInsn / MethodInsn / TypeInsn を維持するため、後段の Rename と targetPackage に追従します。reflection dispatch は使用しません。Kotlin メソッド先頭の `checkNotNullParameter` は、例外メッセージの呼び出し元を維持するため wrapper にも残します。

constructor本体 / class initializer、abstract / native、synchronized / monitor、try/catch、任意のuninitialized object操作、switch、旧サブルーチン等は未対応です。float / long / doubleは定数、load/store、算術、比較、相互変換、return、field / invocation / invokedynamic Bridgeに対応し、`ATHROW`もVM終端命令として扱います。配列はboolean / byte / char / short / int / float / long / double / referenceの一次元生成・length・load/storeに対応します。constructor呼び出しは分岐や未初期化参照の退避を含まない直線的な`NEW → DUP → 引数評価 → invokespecial <init>`を、完成済み参照を返すBridgeへ変換し、同じ安全条件を満たすconstructorのネストも認識します。String LDC は Bridge に保持し、その他の未対応 LDC は拒否します。外部階層・protected access の解決に必要な依存は classpath に指定してください。

Java / Kotlin の実 JAR ロードと元実装比較、instance / reference / field / invocation / cast、float / long / doubleの算術・比較・変換・Bridge、各対応配列、`ATHROW`、制限付きconstructorとそのネスト、NaN / infinity / `-0.0` の実行、並列呼び出し、rename / targetPackage、seed 再現性・型不正拒否のテストがあります。ただし、これは解析耐性の証明ではありません。

以下は未実装です。複数クラスへの意味分散、primitive/reference の型消去、VM 内の文字列生成、署名検証結果を計算鍵にする統合、Platform と独立した Framework adapter、デコンパイル成功率と意味復元率の自動評価、実測に基づく性能予算。既存の文字列暗号化・署名検証は VM と独立しています。全 JVM 命令対応ではありません。

CLI は `--exclude-class regex`、`--exclude-resource regex`、`--exclude-renaming regex`、`--target-package package --flatten-packages`、`--control-flow`、`--virtualize-method regex` を受け付けます。正規表現指定は複数回利用できます。除外されたクラスのメソッドは仮想化しません。

## JAR 内で完結する署名検証

認証サーバーは不要です。最終出力の全ファイルのパスと `SHA-256` 一覧に `SHA256withRSA` で署名します。秘密鍵はビルド環境だけに保持し、出力には公開鍵のみ埋め込みます。既定では無効で、次の両方の鍵指定により有効になります。

```shell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
obfaryn input.jar -o output.jar --integrity-private-key private.pem --integrity-public-key public.pem
```

```kotlin
obfaryn {
  integrityPrivateKey = "/secure/build/private.pem"
  integrityPublicKey = "public.pem"
}
```

core の `ObfuscationConfig` も同名の `Path?` 設定を受け付けます。秘密鍵は非暗号化 `PKCS#8`、公開鍵は `X.509 SubjectPublicKeyInfo` の PEM/DER 形式で、RSA 2048 bit 以上が必要です。鍵不一致は出力前に拒否します。秘密鍵をソース管理や配布 JAR に入れないでください。

除外クラスと `module-info` 以外のクラス初期化子の先頭に検証を挿入します。最初の対象クラスの初期化時に、ローカル JAR の署名と全内容を確認し、不一致は `SecurityException` により初期化を中止します。除外クラス・リソースや検証ヘルパーも署名対象です。`META-INF/obfaryn/integrity.bin` と `integrity.sig` のみ循環回避のためハッシュ対象外とし、署名破損、欠落、重複、ファイル追加を拒否します。ディレクトリーエントリ、圧縮方式・時刻の変更は検査対象外です。署名済み入力の再処理は拒否します。

Java 8 以降で動作し、Obfaryn/Kotlin ランタイムやネットワーク通信は不要です。成功結果はヘルパーのクラスローダー内で、パス・サイズ・更新時刻・公開鍵をキーにキャッシュします。起動後の継続監視や実行中のメモリ改変検知ではありません。展開ディレクトリーや非 file URL は拒否します。Paper 等が JAR を再変換する環境では検証に失敗するため、変換後の成果物を署名する運用が必要です。全対象クラスを除外した設定は拒否します。

別案は標準 `jarsigner` で難読化後の JAR に署名する方式です。ただし署名があるだけでは未署名エントリの混入や検証処理の省略を防げないため、アプリ側で全対象エントリを読み切って署名者を確認し、信頼する証明書または公開鍵を固定する必要があります。単純な埋め込みハッシュ一覧もオフラインで使えますが、一覧まで書き換えられるため署名より弱く、バイト数だけの検査は同サイズの変更を検出できません。

検証コードや埋め込み公開鍵を攻撃者が差し替えることは完全には防げません。JAR 内だけで「解除不可能」「復元不可能」を保証することはできません。

## 安全上の範囲

- `kotlin.Metadata` を正確に書き換える仕組みは未実装です。AUTO ではアプリパッケージだけ除去し、その他の Metadata は保持します。Kotlin reflection や Metadata 依存ライブラリを使うアプリは `preserve` を選択するか実機で十分に検証してください。
- 外部 API との契約が不明な仮想メソッドは名前変更しません。reflection や設定ファイル経由で参照される追加の API は `keep` で指定してください。
- `plugin.yml` / `paper-plugin.yml` の main、bootstrapper、loader、manifest のエントリポイント、ServiceLoader のクラス名は保護します。
- Kotlin Metadata の書き換えは今後の課題です。multi-release JAR はアプリパッケージ外の versioned class を無変更で保持します。versioned class が Rename 対象パッケージにある場合や参照する可能性がある場合は、破損を避けるため拒否します。
