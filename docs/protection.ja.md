# 機能別ポリシー・変換予算・呼び出し先の事前解決

Paper に依存しない JVM 共通の機能です。ルールは名前変更前の `com/example/Physics.step(I)I` に対する完全一致の正規表現で指定します。呼び出し間接化と整数演算変換にはアプリケーション範囲が必要です。VM 化の選択は既存の `@Virtualize` と専用の除外・解析レポートを使います。以下の構造予算は通常の本体変換が対象で、インタープリタの実行時間を制限するものではありません。

## アノテーションと優先順位

```kotlin
import com.peco2282.obfaryn.annotations.*

@Protection(
  controlFlow = FlowProtection.LIGHT,
  callIndirection = ProtectionMode.DISABLED,
  integerArithmetic = ProtectionMode.DISABLED,
)
class Physics {
  @Protection(stringEncryption = ProtectionMode.ENABLED)
  @PerformanceBudget(maxAddedInstructions = 64, maxGrowthPercent = 175)
  fun step(n: Int): Int = n + 17
}
```

`Protection` は `stringEncryption`、`numberObfuscation`、`callIndirection`、`integerArithmetic`、`constantFields` を個別に制御します。各値は `INHERIT`・`ENABLED`・`DISABLED` です。`controlFlow` は `INHERIT`・`NONE`・`LIGHT`・`DISPATCH` から選べます。クラスの設定を引き継ぎ、メソッド側で個々の項目を上書きします。

`ENABLED` は有効なパスへの適用を許可する設定です。グローバルで無効な機能を登録するものではありません。通常メソッドの `constantFields` 指定は、そのメソッド外のフィールド抽出を変更しません。クラス側、またはクラス初期化子への API ルールで制御してください。

優先順位は次のとおりです。

1. クラス除外、および既存の `@NoObfuscation`・`@RenameOnly`・`@HotPath` による本体変換制限
2. メソッドの明示的な `Protection` の項目
3. クラスの明示的な `Protection` の項目
4. API の一致ルール。同じ項目には後の一致ルールを適用
5. 実行全体の既定値

既存の「クラス側の `@HotPath` / `@RenameOnly` に対する、メソッドの明示的な `@Virtualize`」の扱いは維持します。新しい `Protection` だけで既存の制限を回避できません。`@KeepName` は名前の保持のみです。core では `tick` / `onTick` のような名前から処理頻度を推測しません。

ソースを変更できない場合は API を使えます。

```kotlin
import com.peco2282.obfaryn.api.protection.*

obfaryn {
  protectionRules += ProtectionRule(
    methodPattern = "com/example/Physics\\.step\\(I\\)I",
    features = mapOf(BodyFeature.CALLS to false),
    controlFlow = ControlFlowMode.LIGHT,
    budget = TransformationBudget(maxAddedInstructions = 64),
  )
}
```

ルールは改名・再配置後も**元のメソッド識別子**に一致します。ルールの `budget` は実行全体の予算を置き換えます。その後、クラス・メソッドの予算アノテーションで項目ごとに継承・上書きします。アノテーションの `-1` は継承で、それ以外の負値は出力前に拒否します。

## 構造的な変換予算

```kotlin
obfaryn {
  applicationPackages += "com.example"
  protection = "strong"
  lightweightControlFlow = true
  callIndirection = true
  integerArithmetic = true
  maxArithmeticOperationsPerMethod = 4
  transformationReport = "build/protection-decisions.json"
  performance {
    maxInputInstructions = 2000
    maxInputBranches = 128
    maxAddedInstructions = 256
    maxGrowthPercent = 175
    maxOutputCodeBytes = 60000
  }
}
```

core の `ObfuscationConfig` にも `transformationBudget`・`protectionRules`・`integerArithmetic`・`maxArithmeticOperationsPerMethod`・`prelinkCalls`・`transformationReport` があります。`TransformationBudget.latencySensitive()` は上の予算を作成します。これは測定の出発点であり、実行時間の保証ではありません。

- 入力命令数と分岐命令数は各パスの直前に判定します。switch は分岐命令一つとして数えます。
- 追加命令数と増加率は、各パス単独ではなく元のメソッドからの**累積増加**を制限します。`175` は元の命令数の 175% まで、追加命令数 `0` も有効です。
- 命令数からラベル・行番号・フレームを除きます。byte 数は ASM が算出する保守的な最大値です。分岐の拡張も見込むため、実際の符号化なら収まるメソッドを拒否する場合があります。
- 生成された synthetic 補助メソッドはコード上限を検査しますが、元の本体がないため増加率の比較から除きます。新規の初期化子には追加命令数の制限を適用します。
- 任意の構造制限の既定値は無制限です。対応するパスでは、class ファイルのコード長・定数プールの上限を常に検査します。

変更の確定は**クラスとパスの単位**です。一つの変更メソッドが出力予算を超えた場合、そのパスによるクラス全体の変更を、フィールド・初期化子・補助クラスも含めて取り消します。それ以前のパスで確定した変更は残します。定数だけがフィールドから除去された状態や、キャッシュ初期化のない復号器を残さないための保守的な設計です。その分、メソッド単位の取り消しより適用範囲が狭くなる場合があります。

文字列・数値・定数フィールド抽出・制御フロー・整数演算・呼び出し間接化が対象です。名前変更と VM 化は独自の処理を維持します。独自の変換は `Transformer.isolatedBodyFeature` を宣言すると、同じ分離・予算・診断に参加できます。その契約では、一つの候補クラスの変更と補助クラスの追加、依存クラスの読み取りを許可し、リソースの変更・依存クラスの変更・クラスの削除を禁止します。グローバルな操作が必要な独自パスはこの宣言を省略してください。`skipReason` には、乱数を消費せず変更もしない診断を実装できます。

ビルダーには `integerArithmetic(...)` と `callIndirection(...)` を追加しています。`transformers { ... }` はリストを作るだけなので、メイン README の方法で明示的に登録・指定してください。

## 整数演算の変形

`integerArithmetic = true` / `--integer-arithmetic` で、int/long の加算・減算・XOR を、鍵付きの等価な演算式に置き換えます。JVM のオーバーフロー、オペランドの評価順、副作用を保持します。浮動小数点・乗算・除算・剰余・シフトは変更しません。コンストラクタ・初期化子・synthetic メソッドは対象外です。

インタープリタ、ボックス化、追加のメソッド呼び出しは使いません。ただし命令の増加が JIT や実行性能に影響する可能性があります。JIT による整理と解析による式の復元も可能です。

## 呼び出し先の明示的な事前解決

`callIndirection` と合わせて `prelinkCalls = true` / `--prelink-calls` を有効にします。API を呼ぶアプリケーションは、小さな Java 8・外部依存なしのモジュールに対してコンパイルできます。

```kotlin
dependencies {
  compileOnly("com.peco2282.obfaryn:obfaryn-runtime:<使用するバージョン>")
}
```

有効時は API を難読化 JAR に埋め込み、難読化エンジンの実行時依存は不要です。生成リンカーのキャッシュには `ClassValue` を使い、親側リンカーのグローバルな Map が子ローダーのクラスを保持し続ける構造を避けています。

```kotlin
import com.peco2282.obfaryn.runtime.ProtectionRuntime

// tick の外など、利用側が明示的に選んだ起動時点で実行
var offset = 0
while (true) {
  val prepared = ProtectionRuntime.prelink(Physics::class.java, offset, 32)
  if (prepared == 0) break
  offset += prepared
}
```

`prelink(caller, startSite, maxSites)` は、その安定した範囲で処理した件数を返します。二引数の `prelink(caller, maxSites)` は位置 0 からです。内部でカーソルを進めないので、分割する場合は戻り値だけ位置を進めてください。負の引数は拒否します。生成入口のないクラスは 0 を返します。

**対象メソッドの本体は実行しません。** 対象クラスを初期化せずにロードし、元の呼び出し元の lookup でハンドルを解決して、固定 CallSite をキャッシュします。一方、生成された static 入口を呼ぶため、**指定した呼び出し元クラス自身の初期化は起こります**。安全な実行時点を明示的に選んでください。

依存関係・メンバー・アクセス権が足りなければ、まだ通らないコードの呼び出し先でも解決に失敗する場合があります。失敗を隠さず伝えます。1 件で依存クラスのロードが発生する可能性があり、件数制限は時間制限ではありません。

最初の実行で行う復号・検索は減らせますが、実際の `invokedynamic` の JVM リンクと JIT は初回利用時に残ります。初回負荷ゼロの保証ではありません。生成入口は小分けにして巨大なメソッドを避けます。自動のクラス走査・初期化や、Paper のライフサイクルの横取りはしません。

## 適用・除外レポート

`--transformation-report PATH` は schema version 1 の JSON を出力します。同じメソッド行を `ObfuscationResult.transformerReports[*].methods` から取得できます。実行されたパスだけが含まれ、詳細なメソッド行はクラス単位で分離できる通常の本体変換が対象です。

各行には元の識別子、`applied` / `skipped` / `rejected`、理由、入出力の命令数・分岐命令数・最大コード byte 数を記録します。アノテーション、機能単位の制限、入力予算、クラス全体の取り消し、非対応形状、生成補助メソッドを区別します。除外クラスも skipped として記録し、取り消した新規補助メソッドの出力サイズは 0 です。元の名前を含むため、配布物には含めないでください。

CLI の予算指定は `--max-input-instructions`、`--max-input-branches`、`--max-added-instructions`、`--max-growth-percent`、`--max-output-code-bytes`、`--arithmetic-operations` です。

## 継続検証と測定

- `./gradlew test`：整数オーバーフロー・浮動小数点への非干渉・副作用順・設定継承・元名ルール・累積予算・クラス単位の取り消し・事前解決の範囲分割と並行実行・Kotlin 形式の enum 配列 clone などを回帰検証します。
- `python3 scripts/compatibility/run.py`：`corpus.json` に固定した公開 commit を取得して元のソースからビルドし、元と同じコンパイル済みテストを難読化後の JAR へ向けて実行します。元の production 出力ディレクトリと元 JAR を差し替え用 classpath から除き、誤って元コードを検証することを防ぎます。ライブラリとしての public API 名は保持し、JVM の検証を有効にしています。上流のアルゴリズムは変更しません。`--skip-build` は一致する既存ビルドを再利用するローカル用で、fresh CI には使いません。
- `python3 scripts/performance/run.py`：tick 相当の合成ループを、別 JVM・交互の実行順で比較します。最低時間のウォームアップを行ってから入力をリセットし、測定時の処理と入力を揃えます。初回実行、明示的な事前解決時間、ウォーム後の p50/p95/p99/最大値、スレッドの割り当て量、結果のビット一致を記録します。`@HotPath` の対照群も含みます。`--max-p99-ratio` と `--max-extra-bytes-per-tick` で、利用環境に応じた退行判定を指定できます。共有マシンの時間は揺らぎ、Minecraft サーバーの TPS 測定ではありません。

GitHub のワークフローは変更時のテスト、週次・手動の公開プロジェクト検証、手動の合成 tick 測定を用意しています。ログと JSON を成果物として保存します。これらは品質を判断する証拠を増やすもので、全フレームワークとの互換性・性能不変・商用品質の保証ではありません。実際のサーバー起動、負荷の末尾分布、メモリ・GC、利用する環境の組み合わせも確認が必要です。

API の既定フックは JVM の default メソッドと Kotlin の `DefaultImpls` 互換ブリッジを生成します。旧インターフェース相当でコンパイルした実装を新 API でロードする回帰テストを用意しています。設定の根拠は [Kotlin のコンパイラ設定](https://kotlinlang.org/docs/gradle-compiler-options.html) を参照してください。

事前解決 API の埋め込みは classpath の JAR で検証しています。名前付きモジュールの記述子や `requires` は書き換えないため、JPMS の配備は別途検証が必要です。static ハンドル検索と初期化の仕様は [MethodHandles.Lookup](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/MethodHandles.Lookup.html#findStatic(java.lang.Class,java.lang.String,java.lang.invoke.MethodType)) を参照してください。
