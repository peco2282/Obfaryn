# API互換性ポリシー

[English](api-stability.md)

1.0.0リリースにあたり確定済みの方針です。目的: 「この変更は破壊的変更か」に全員が同じ基準で
答えられるようにし、プルリクエストごとに毎回議論し直さなくて済むようにすることです。

## 1.0.0が約束するもの(semver上の破壊的変更はメジャーバージョンを要する)

- **CLIフラグとその挙動**(`obfaryn-cli`): フラグの削除、デフォルト値の変更、受け付ける値の範囲を狭める、
  既存の入力に対する挙動の変更は破壊的。新しいフラグや、フラグが受け付ける新しい値の追加は破壊的ではない。
- **Gradle DSL**(`obfaryn-gradle-plugin`の`obfaryn { ... }`ブロック): プロパティの削除・リネーム、型の変更、
  文書化されたデフォルト値の変更は破壊的。プロパティやネストしたブロックの追加は破壊的ではない。
- **`obfaryn-api`の拡張インターフェース**: `Transformer`、`RenamingTransformer`、
  `TransformationPackage`/`TransformationPackageProvider`、`Platform`/`PlatformProvider`、
  `PipelineConfigurer`、`NumberObfuscationStrategy`、`StringEncryptionCodec`、および
  `obfaryn-annotations`のアノテーション型。メソッドの削除、シグネチャ変更、文書化された意味の変更
  (例: `canTransformBody`が何を参照するか、`transformedCount`がいつ読まれるか)は破壊的。
  デフォルト実装付きのメソッド追加は破壊的ではない(既にKotlinの`DefaultImpls`互換性回帰テストで
  この方針を実践済み)。
- **標準ファサードの公開コンストラクタ/メソッド**: CLIやGradle pluginを経由せず直接エンジンを
  embedする利用者向けの`Obfuscator`・`ObfuscationConfig`・`ObfuscationResult`(`obfaryn-standard`)。
- **公開済みのMaven座標**: 下表の各モジュールの`groupId`/`artifactId`は固定。メジャーバージョンを
  経ずにモジュール名を変更したり他モジュールへ統合したりしない。

## 明示的に対象外のもの(どのリリースでも自由に変更可)

- 上記の公開サーフェス以外の、`obfaryn-core`・`obfaryn-standard`・`obfaryn-transformer`内部の全て:
  内部クラス名、内部メソッドシグネチャ、内部パスの正確な集合と順序、内部のファイル配置。
- `docs/protection.md`で既に宣言されている安定した`--report`/`--transformation-report`のJSON
  スキーマバージョンを除く、CLIの出力整形(人間向けサマリー行)。
- 1.0.0タグ時点でexperimentalまたは`@VirtualizeOptIn`と明示されているもの(そのマーキングが
  外れるまで)。
- 構造予算・増加許容量などのチューニング用定数の「既定値」そのもの——存在と型は安定だが、
  数値自体は調整対象でありうる。
- 性能特性(速度・メモリ割り当て)は互換性の約束の対象に一切含まない。文書化された挙動のみが対象。

## モジュールの安定度ティア

| モジュール | ティア | 理由 |
| --- | --- | --- |
| `obfaryn-api` | 安定サーフェス | 拡張の実装者が直接コンパイル対象にする |
| `obfaryn-annotations` | 安定サーフェス | 利用者自身のソースに現れる |
| `obfaryn-runtime` | 安定サーフェス | 利用者の出力JARに埋め込まれる。挙動変更は他人のアプリ内で発生する |
| `obfaryn-cli` | 安定サーフェス(フラグのみ) | 利用者向けの契約 |
| `obfaryn-gradle-plugin` | 安定サーフェス(DSLのみ) | 利用者向けの契約 |
| `obfaryn-standard` | 部分的に安定 | `Obfuscator`/`ObfuscationConfig`/`ObfuscationResult`は安定、それ以外は対象外 |
| `obfaryn-core` | 内部 | `obfaryn-api`経由で公開されている範囲を超えて外部利用者が依存することは想定しない |
| `obfaryn-transformer` | 内部 | 同上 |
| `obfaryn-platform-paper` | 安定サーフェス(provider契約のみ) | 外部platform providerは`obfaryn-api`の`PlatformProvider`を実装する。このモジュールはその参照実装 |

## 今後の運用

1. 上記「1.0.0が約束するもの」に変更を加えるPRは、破壊的変更として明記する。
2. 安定サーフェスへの破壊的変更には、メジャーバージョンのbumpか、明示的かつ文書化された
   非推奨期間(`@Deprecated`を付けたまま最低1回のマイナーリリースは動作を維持)のいずれかを
   必須とする。
3. `obfaryn-api/src/test/kotlin/.../BinaryPolicyCompatibilityTest.kt`のパターン(古いインターフェース
   形状でコンパイルし、現在の実装でロードする)が、「メソッド追加は破壊的ではない」という規則の
   機械的な検査になっている。安定インターフェースに新しいメソッドを追加する際はこれを拡張すること。
