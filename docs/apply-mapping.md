# 逐次難読化（apply-mapping）仕様書

難読化済みの成果物（典型的には内部API）を**先に配布**し、その利用側プロジェクトを**後から別ビルドで難読化**する運用を支える仕組みの設計と実装契約をまとめます。先行成果物の rename 結果を**固定入力**として読み込み、後続のビルドへ**再適用**することで、別々にビルドされた成果物間でも参照が壊れないことを保証します。

## 読み方

| 目的 | 読む節 |
| --- | --- |
| 対象になる運用を判断する | [1. 目的とスコープ](#1-目的とスコープ) |
| 既存マッピングとの違いを理解する | [2. 2方向のマッピング](#2-2方向のマッピング) |
| フォーマットを理解する | [4. マッピングフォーマット](#4-マッピングフォーマット) |
| 再適用の意味論を理解する | [5. 再適用の意味論](#5-再適用の意味論) |
| 失敗条件や制約を確認する | [8. 不変条件・エラー処理](#8-不変条件エラー処理) |

ステータス: **design（設計確定・未実装）**
対象読者: Obfaryn 開発者
関連: [multi-artifact-assembly.md](multi-artifact-assembly.md), [assembly.md](assembly.md), [protection.md](protection.md)

> **実装状況（2026-11）**: 本書は設計のみ。実装はまだ無い。[multi-artifact-assembly.md](multi-artifact-assembly.md) の whole-program 集約（全成果物が揃う前提）と対になる、**成果物を配布して後からビルドする**運用のための設計である。

> **命名方針**: 本書は[multi-artifact-assembly.md](multi-artifact-assembly.md)と同じく、**新規に作成する型・メンバ・フラグ・タスクの名前を規定しない**。すべて役割で記述する。
> 文中で参照の便宜上つける**仮ラベルは `〈 〉` で囲む**（例: 〈適用マッピング〉）。実際の名前は実装時に決める。
> 一方、`RenameTransformer`・`Mapping` のような**通常表記は既存コードの実在物**への参照で、これは確定した名前である。

---

## 1. 目的とスコープ

### 1.1 目的

難読化済みの成果物 A（内部API等）を配布した後、**A を参照する利用側 B を別のタイミング・別のビルドで難読化**したい。このとき B 内の A への参照は、**A が実際に出力した難読化後の名前**に一致していなければ、難読化済み A に対して壊れる。本仕様は、A の難読化時に生成した**順引きマッピング（元名→難読名）を固定入力として B のビルドへ再適用**する経路（apply-mapping）を定義する。

### 1.2 前提（適用条件）

- A は難読化済みで配布され、B のビルド時には A の**ソースではなくマッピングと成果物（classpath 上の JAR）だけ**が手に入る。
- B は A のクラスを**自分の成果物に含まない**（A は classpath 上の外部依存）。
- A のマッピングは B のビルド時点で入手できる。

### 1.3 非目標（Non-goals）

- 全成果物を同時に1命名宇宙で難読化する運用（それは [multi-artifact-assembly.md](multi-artifact-assembly.md) の whole-program 集約が担う。本仕様は**配布して後でビルド**する逆の運用）。
- A 自身の再難読化（A のマッピングは確定済みとして扱う。再生成は A を作り直す別の run）。
- 複数 jar 書き出しの原子性保証・ファイルシステムロック。
- live Paper 挙動・全 JVM/OS・module-path 実行の反復テスト保証（[AGENTS.md] の検証スコープに従う）。

### 1.4 位置づけ

本機能は **design（未実装）**。whole-program 集約が「全部そろえて一度に」なのに対し、本仕様は「先に配布・後から追従」。両者は排他ではなく、提供経路（Gradle/CLI）と検証を共有しうる。

---

## 2. 2方向のマッピング

マッピングには用途の異なる2方向があり、本仕様が追加するのは後者である。符号化（テキスト表現）は両者で共有できる。

| | 向き | キー | descriptor | 主用途 | 現状 |
|---|---|---|---|---|---|
| 逆引き（de-obfuscation） | 難読名 → 元名 | クラス名・メソッド名 | 持たない | スタックトレース復元 | 実装済み（`Mapping`） |
| **順引き（apply-mapping）** | **元名 → 難読名** | **owner＋name＋desc** | **必須** | **参照書換＋名前予約** | **本仕様で追加** |

現 `Mapping`（[Mapping.kt](../obfaryn-standard/src/main/kotlin/com/peco2282/obfaryn/core/mapping/Mapping.kt)）は逆引き・descriptor 無し・名前一意時のみ解決、というスタックトレース向けの設計で、**再適用には使えない**。順引きはオーバーロードを区別するため descriptor が必須で、`MemberKey(owner, name, desc)` をキーに元→難読名を引く。

---

## 3. アーキテクチャ

### 3.1 基本方針

- **マッピングの符号化はテキストのみ**を canonical とする。モデルを単一の正とし、テキストはその1シリアライザと位置づける。JSON/バイナリは将来 serializer 追加で対応でき、今は規定しない。
- **フォーマット知識を1箇所に集約**する。現在は `RenameTransformer.mappingText()`・`RenamingTransformer` の default `mappingText()`・`Mapping.parse` の3箇所にフォーマット知識が分散している。読み書き両方向を単一の〈マッピングモデル〉経由にする。
- **`RenameTransformer` は再適用のための最小拡張**にとどめる。元→難読名の決定機構（アロケータ・remapper）はそのまま使い、「固定入力を先に投入し、未確定分だけ新規採番する」形にする。

### 3.2 構成要素（役割）

| 仮ラベル | 役割 | 位置（レイヤ） |
|---|---|---|
| 〈マッピングモデル〉 | class/method/field/package の順引き表＋来歴を保持する単一の正。read/write はここを通る | api（契約）／standard（実装） |
| 〈来歴ヘッダ〉 | マッピングファイル先頭のメタ。ソースエントリ配列＋format/tool version | 〈マッピングモデル〉の一部 |
| 〈ソースエントリ〉 | 1つの寄与元。識別子＋digest＋version。連鎖時に累積する | 〈来歴ヘッダ〉の要素 |
| 〈適用ソース〉 | 再適用で読み込む先行マッピング入力（複数可） | `ObfuscationConfig` 相当に追加 |
| 〈固定割当〉 | 〈適用ソース〉由来の確定済み元→難読名。再採番しない | `RenameTransformer` の事前投入 |

既存の実在物: `Mapping`・`RenameTransformer`・`mappingText()`・`classMapping`/`methodMapping`/`fieldMapping`/`packageMapping`・`MemberKey`・`RenamingTransformer`・`ObfuscationConfig`・`Obfuscator.obfuscate`・`ClassRemapper`/`Remapper`・`SequentialNameGenerator`・`occupied`・`ClassHierarchyResolver`・`ArtifactPaths`・`.mapping.txt`。

---

## 4. マッピングフォーマット

### 4.1 方針

テキストを canonical に維持する。理由:

- **監査性・diff 可能性**: リリース間の記号変化を VCS の diff で追える。
- **寿命**: マッピングはツールより長生きする。素の目で読めることに価値がある。
- **既存資産**: `.mapping.txt` と `Mapping.parse` が既にある。

再適用に足りない3点を補う。いずれも現 `mappingText()` 出力（class/method/field のみ、[RenameTransformer.kt](../obfaryn-standard/src/main/kotlin/com/peco2282/obfaryn/core/rename/RenameTransformer.kt)）に欠けている。

### 4.2 package 対応の記録

現状 `packageMapping` はテキストへ出力されていない。`--flatten-packages` で B が A のパッケージを参照する整合、および `module-info` の `exports`/`opens` 再書換のため、package の元→新対応を出力・再適用の両方に含める。クラス行と構文的に区別できる表現にする（専用セクション or 接頭辞。構文は実装時に確定）。

### 4.3 〈来歴ヘッダ〉

ファイル先頭に、現 `Mapping.parse` と後方互換な形（空白・コメント行は無視される既存仕様を活かし、`#` 始まりのコメント行）でメタを置く。内容:

- **format version**: フォーマット自体のバージョン。
- **tool version**: 生成した Obfaryn のバージョン。
- **〈ソースエントリ〉配列**: このマッピングが依拠した先行成果物それぞれについて、`識別子 ＋ digest`。識別子は classpath 上の該当 JAR を特定できるもの（coordinate または JAR ファイル名）。digest はその成果物の内容ハッシュ。

A を難読化した直後のマッピングは、**自分自身を1つの〈ソースエントリ〉**として持つ（A の JAR の digest）。

### 4.4 本体行

- クラス: `元内部名 -> 難読内部名`（既存どおり）。
- メソッド: `  owner.name(desc) -> 難読名`（既存どおり、descriptor 付きで一意）。
- フィールド: `  owner.name:desc -> 難読名`（既存どおり）。
- パッケージ: §4.2 の表現で `元package -> 新package`。

本体行のキーは**元 identity**（`MemberKey` と同じ）であり、AGENTS.md の「original method identities で解決」不変条件と一致する。

---

## 5. 再適用の意味論

B のビルドの `RenameTransformer.transform` 冒頭で、〈適用ソース〉を読み込み、以下を行う。

### 5.1 固定割当の事前投入

〈適用ソース〉の class/method/field/package を、`classMapping`/`methodMapping`/`fieldMapping`/`packageMapping` に**先に投入**する。これらは A のクラス（B の `context.classes` に無い）についての確定割当であり、**B のクラスとして再採番しない**。

### 5.2 参照書換のフォールバック

B 内の A 参照を A の難読名へ着地させるため、remapper の `map`/`mapMethodName`/`mapFieldName`/`mapPackageName` が、B 自身のテーブルに加えて〈固定割当〉も参照する。現 remapper（[RenameTransformer.kt](../obfaryn-standard/src/main/kotlin/com/peco2282/obfaryn/core/rename/RenameTransformer.kt)）は自前テーブルしか見ないため、**ここの拡張が参照整合の要**。A クラスは classpath 上にあり `ClassHierarchyResolver` の階層解決には寄与するが、改名・出力の対象ではない。

### 5.3 名前予約

`--flatten-packages` で B を A と**同じ宛先パッケージ**へ集約する場合、A が既に取得した難読名を B の新規採番が再利用しないよう、〈固定割当〉の難読名を `occupied` に投入する。`SequentialNameGenerator` の採番は `occupied` を避けるため、これで宛先パッケージ内の衝突を防ぐ。

### 5.4 固定の不可侵

〈固定割当〉の identity は keep 判定・再採番の対象外（確定済みとして扱う）。B が A のクラスを含まない前提では通常問題にならないが、含んだ場合は §8.1 のエラーとする。

---

## 6. 出力（マージ・自己完結）

B の難読化結果のマッピングは、**〈適用ソース〉由来のエントリと B 自身の rename を1ファイルにマージ**して出力する（自己完結）。

- 本体行: 〈固定割当〉＋ B 自身の `classMapping`/`methodMapping`/`fieldMapping`/`packageMapping` を連結。同一 `元→難読` の重複は一意化する。
- 利点: B のスタックトレースに A 由来の難読名が現れても、この1ファイルで逆引き復元できる。
- 〈来歴ヘッダ〉: **連鎖対応で累積する**（§7）。

---

## 7. 来歴の累積（連鎖 apply）

マージ出力は複数ソース由来（先行マッピング群＋B 自身）になるため、これをさらに次段へ適用する連鎖（A→B→C…）を見据え、**〈ソースエントリ〉を累積**する。

- B の出力ヘッダ = **取り込んだ全〈適用ソース〉のエントリを引き継ぎ ＋ B 自身のエントリを追加**。
- これにより C が B の出力を〈適用ソース〉にすると、A と B 双方の digest 来歴が C に積み上がる。
- **適用時の照合（§8.2）は各〈ソースエントリ〉ごと**に行う。1つでも不一致なら fail。

---

## 8. 不変条件・エラー処理

### 8.1 エラー（実行前・実行時に失敗）

- **同一クラスが〈適用ソース〉と B の両方に存在**（shaded 等）→ **拒否**（import 優先はしない）。曖昧な構成として難読化を止め、利用者に解消させる。
- **digest 不一致**（§8.2）→ 拒否。
- 既存の設定妥当性（`flattenPackages` の宛先、`prelinkCalls` without `callIndirection` 等）は従来どおり検査。

### 8.2 stale 検出（digest 照合で fail）

〈来歴ヘッダ〉の各〈ソースエントリ〉について、識別子で classpath 上の該当 JAR を特定し、その digest を照合する。不一致は「古いマッピングを新しい成果物に当てた取り違え」であり、**黙って食い違わせず即エラー**にする。

### 8.3 不変条件

- **re-apply 中もリネーマーは1つ**。〈固定割当〉は新規パスではなく、既存 `RenameTransformer` への事前投入として扱う。
- **キーは元 identity**。〈適用ソース〉の本体行キーは `MemberKey` と同じ元 identity。
- **core は standard/transformer 非依存**を維持。フォーマット/モデルの契約は api、適用機構は standard（`RenameTransformer` 周辺）。
- **module-info**: `exports`/`opens` は `packageMapping` 由来で B 側でも再書換される。`requires` と module 自身の名前は**不変**（現状の方針どおり）。

---

## 9. レイヤリング（モジュール配置）

> 右欄は**役割**。新規型・フラグ・タスクの名前は規定しない。

| レイヤ | 追加する役割 | 制約 |
|---|---|---|
| obfaryn-api | 〈マッピングモデル〉契約（順引き表・来歴）、〈適用ソース〉を表す入力契約 | 公開契約は API に置く |
| obfaryn-standard | 〈マッピングモデル〉実装、`mappingText()` の集約、`RenameTransformer` の固定割当投入・参照フォールバック・名前予約、digest 照合、マージ出力 | core はリネーマーを知らない |
| obfaryn-core | （必要なら）digest 算出・classpath JAR 特定の補助。`ArtifactPaths` 近傍 | standard/transformer 非依存を維持 |
| obfaryn-cli | 〈適用ソース〉入力フラグ（複数可） | 既存 `obfuscate` 挙動は不変 |
| obfaryn-gradle-plugin | 〈適用ソース〉入力 DSL（複数可） | 既存タスクは温存。nested/flat は同じ値を共有 |

既存の `.mapping.txt` 書き出し経路（`Obfuscator.obfuscate` の mapping 引数）は不変。

---

## 10. Gradle / CLI 利用像（役割）

> フラグ名・DSL の形は未定。役割のみ示す。

- **CLI**: `obfuscate` に、再適用する先行マッピングを**複数**与えられる入力を追加。A の classpath JAR は既存の classpath 入力で渡す。
- **Gradle**: 利用側プロジェクトの `obfaryn { … }` に、先行マッピング入力（複数可）を追加。A を依存として解決し、そのマッピングと JAR を再適用。

---

## 11. 検証

[AGENTS.md] のテスト指針に従う（JUnit Jupiter、temp dir、bytecode 妥当性＋**実行時**）。既存 `verify-*.py` の系譜に沿う。

- **round-trip**: A を難読化 → マッピング出力 → B を A のマッピング＋JAR で再適用 → **B の A 参照が A 出力の難読名に一致**＋JVM verify＋A・B を一緒にロードして実行（A の元 jar はクラスパス除外）。
- **flatten 衝突**: `--flatten-packages` で A・B を同一宛先に集約したとき、名前衝突が起きないこと（§5.3）。
- **digest-fail**: A を作り直して digest を変え、古いマッピングで B を再適用 → **fail すること**（§8.2）。
- **同一クラス両在**: A と B に同名クラス → **拒否されること**（§8.1）。
- **連鎖 apply**: A→B→C と再適用し、C で A・B 双方の来歴照合が効くこと（§7）。
- **module-info**: B 側で A パッケージ参照を含む `exports`/`opens` が `packageMapping` で正しく書換わること。
- 検証スコープの但し書き（live Paper・全 JVM/OS・CI 非保証）を明記。

---

## 12. 未決事項（実装前に決める）

1. **package 行の構文**（§4.2）: 専用セクション vs 接頭辞。既存 `Mapping.parse` との後方互換を優先して確定。
2. **〈来歴ヘッダ〉の具体表現**（§4.3）: `#` コメント行の key: value 形式か、独立セクションか。
3. **識別子の形**（§4.3・§8.2）: JAR ファイル名か Maven coordinate か。classpath 上の JAR を一意特定できること。
4. **digest のアルゴリズムと対象**（§8.2）: JAR バイト全体か、正規化後か。
5. **新規の型・メンバ・フラグ・タスク名**: 本書は規定しない。実装着手時に決める。

---

## 付録 A: 既存コードとの対応

> 以下は**実在する名前**。新規追加の命名ではない。

- マッピング読み（逆引き）: `Mapping.parse` / `Mapping.read`（[Mapping.kt](../obfaryn-standard/src/main/kotlin/com/peco2282/obfaryn/core/mapping/Mapping.kt)）。
- マッピング書き: `RenameTransformer.mappingText()` ／ `RenamingTransformer.mappingText()` default。
- rename 決定機構: `RenameTransformer`（`classMapping`/`methodMapping`/`fieldMapping`/`packageMapping`、`SequentialNameGenerator`、`occupied`、`Remapper`/`ClassRemapper`）。
- 元 identity キー: `MemberKey`（[NameMapping.kt](../obfaryn-api/src/main/kotlin/com/peco2282/obfaryn/api/transformer/NameMapping.kt)）。
- 階層解決: `ClassHierarchyResolver`（A クラスは classpath 経由で寄与）。
- 出力先検証の拡張点: `core.io.ArtifactPaths`。
- whole-program 集約（対になる運用）: [multi-artifact-assembly.md](multi-artifact-assembly.md)、`AssemblySession`。
