# 複数成果物アセンブリ（横断難読化）仕様書

複数のJARを1つの論理applicationとして解析・renameし、元の成果物単位に分けて書き戻す仕組みの設計と実装契約をまとめます。

## 読み方

| 目的 | 読む節 |
| --- | --- |
| 対象になる構成を判断する | [1. 目的とスコープ](#1-目的とスコープ) |
| ownerと処理の流れを理解する | [3. アーキテクチャ](#3-アーキテクチャ)・[6. 処理フロー](#6-処理フロー三相) |
| APIを実装・利用する | [4. 公開契約](#4-公開契約api層-obfaryn-api)・[13. 実装](#13-実装確定した命名と方針) |
| 失敗条件や制約を確認する | [8. 不変条件・エラー処理](#8-不変条件エラー処理) |
| Gradleから利用する | [13.1.1 Gradle利用像](#1311-gradle-利用像実装) |

前半は設計理由、後半の「13. 実装」は確定した現在の契約です。設計途中の候補より、実装節の命名と制約を優先してください。

ステータス: **experimental（エンジン＋ファサード＋Gradle集約＋CLI 実装済み）**
対象読者: Obfaryn 開発者
関連: [assembly.md](assembly.md), [protection.md](protection.md)

> **実装状況（2026-11）**: 本仕様のエンジン機構（obfaryn-api 契約・obfaryn-core 機構）、obfaryn-standard のセッションファサード、obfaryn-gradle-plugin の集約タスク（`obfuscateAssembly`＋`obfarynAssembly` 拡張）、および obfaryn-cli の `assemble` サブコマンドを実装した。実装で確定した命名と §12 の方針は §13 を参照。

> **命名方針**: 本書は**新規に作成する型・メンバ・タスクの名前を規定しない**。すべて役割で記述する。
> 文中で参照の便宜上つける**仮ラベルは `〈 〉` で囲む**（例: 〈セッション〉）。実際の名前は実装時に決める。
> 一方、`RenameTransformer` のような**通常表記は既存コードの実在物**への参照で、これは確定した名前である。

---

## 1. 目的とスコープ

### 1.1 目的

内部APIライブラリ・その実装・利用アプリケーションといった**複数のGradleサブプロジェクト**を、**1つの一貫したリネーム宇宙**で同時に難読化する。API記号を改名しても、実装・利用側の参照が同じ決定に追従し、**成果物間のリンクが壊れない**ことを保証する。各サブプロジェクトは**自分の難読化設定（platform割り当て・適用パス）を個別にカスタマイズ**できる。

### 1.2 前提（適用条件）

- APIは**内部公開のみ**で、ビルド外の第三者が直接利用しない。
- 利用側プロジェクトは**Gradleで管理**（サブプロジェクト等）され、ビルド時に全成果物が揃う。
- 守るべき安定ABIが無い（内部APIなので公開表面を全改名してよい）。

### 1.3 非目標（Non-goals）

- 難読化済みAPIを成果物として配布し、第三者が後からビルドする運用（逐次 `apply-mapping` 方式であり本仕様の対象外）。
- 複数jar書き出しの原子性保証（単一トランザクションではない）。
- ファイルシステムロック、マルチ成果物トランザクション。
- live Paper挙動・全JVM/OS・module-path実行の反復テスト保証（[AGENTS.md] の検証スコープに従う）。

### 1.4 位置づけ

本機能は **experimental**。本命の提供経路は Gradle プラグイン（whole-program）。CLI 対応は任意・後続。

---

## 2. 用語

| 用語 | 定義 |
|---|---|
| モジュール / 子 | 1つの入力成果物（jar）とその設定。難読化の加工単位。 |
| 〈セッション〉 / 親 | 複数モジュールを束ね、横断リネームを1回行う上位単位（型名は未定）。 |
| 命名宇宙 | 全モジュールのクラス・メンバを跨いで一意な内部名の集合。rename はこの単位で決定する。 |
| 〈全体クラスビュー / union〉 | リネーム時に全モジュールのクラスを1つに見せる作業用 `ObfuscationContext`。resource は含めない。 |
| 〈出自台帳〉 | 各内部名（元名）がどのモジュール由来かの対応。分割書き出しに使う。 |
| 〈全体View〉 | platform が cross-module 解決に使う全体ビュー（`PlatformContext` 上の全体参照手段。メンバ名は未定）。 |

---

## 3. アーキテクチャ

### 3.1 基本方針

- **名前（rename・keep・型）はグローバル＝親が所有**。
- **加工（body系パス）・resource・入出力はローカル＝子が所有**。
- **`RenameTransformer` は無改造**。〈全体クラスビュー〉を渡すことでグローバルマッピングを得る。

### 3.2 所有分担（詳細）

「全体で1つでなければ横断一貫性が壊れるもの」を親、「1成果物に閉じるもの」を子が持つ。各項目について**内容・構築タイミング・寿命・参照者・なぜそのスコープか**を示す。

#### 3.2.1 親（〈セッション〉）が持つもの

| 持ち物 | 内容 | 構築 | 寿命 | 参照者 | なぜ親か |
|---|---|---|---|---|---|
| 〈セッション設定〉 | 横断 rename の駆動値（クラス/メソッド改名可否、パッケージ平坦化と宛先、**rename 用 seed**） | セッション生成時 | 全体 | Phase B | rename は全体で1回だから駆動設定も1つ |
| 〈共有 keep 集合〉 | 全モジュールの platform/MANIFEST/services/アノテーション由来 keep を集約した**単一の `KeepRules`** | Phase A で各子が供出し累積 | 全体 | Phase B（rename の名前保持判定） | keep は内部名キーで全体一意。集約しないとモジュール毎に判断がぶれる |
| 〈グローバルマッピング〉 | class/method/field/package の 元→新 名（`classMapping` 相当） | Phase B | 全体 | Phase C（分割・resource remap）、結果出力 | 横断一貫性の本体 |
| 〈全体クラスビュー / union〉 | 全子の `ClassNode` を**同一参照のまま**1つに見せる作業用 context（resource は持たない） | Phase B 直前 | Phase B のみ | `RenameTransformer` | リネーマーに「1命名宇宙」を見せる唯一の手段 |
| 〈グローバル型グラフ〉 | union 上の階層解決（`ClassHierarchyResolver` 相当） | Phase B | Phase B | `VirtualMethodFamilies` | オーバーライド族がモジュールを跨ぐ |
| 〈出自台帳〉 | 元内部名 → 成果物識別子 | Phase A 完了後 | 全体 | Phase C 分割 | どの子へ書き戻すかの唯一の根拠 |
| 〈生成名の採番/namespace 方針〉 | body 系ヘルパーのグローバル一意性を保証する規律 | セッション生成時 | 全体 | Phase A（ヘルパー生成時）／Phase B | 名前衝突回避は全体の責務（§8.3） |
| 〈全体View 供給〉 | 各子の `configure` に渡す `PlatformContext` の全体ビュー | Phase A 前 | Phase A | 各子の platform | cross-module 解決の供給源 |
| 〈集合出力先検証〉 | 全 output と 全 input/classpath/鍵 の相互チェック結果 | Phase 0 | 実行前 | 実行可否判定 | 成果物間の上書き防止は集合単位（§8.1） |
| 〈オーケストレーション状態〉 | フェーズ順序、モジュール登録簿、結果の組み立て | セッション生成〜終了 | 全体 | run 全体 | A→B→C を1箇所で統御 |

> アノテーション方針（`@NoObfuscation` 等）は**値を親が持たない**。実体はクラスノードに同行し（子）、親の Phase B rename がノードから読んで尊重する（§3.2.3）。

#### 3.2.2 子（モジュール）が持つもの

| 持ち物 | 内容 | 構築 | 寿命 | 参照者 | なぜ子か |
|---|---|---|---|---|---|
| 入出力パス | この成果物の入力 jar と出力先 | モジュール登録時 | 全体 | Phase 0/C | 成果物固有 |
| 自分の `ObfuscationContext` | 自分の classes＋resources | Phase A（`JarReader().read`） | A〜C | A（加工）、B（classes を union へ供出＝同一ノード参照）、C（resources 書き出し） | 1成果物に閉じる状態 |
| 自分の設定（`ObfuscationConfig` 相当） | platform 選択、適用する body 系、`noStringEncryption`、`controlFlowMode`、**body 用 seed** 等 | モジュール登録時 | 全体 | Phase A | モジュール個別のカスタマイズ（§7） |
| 自分の platform 群 | 検出された platform と、その keep/hot-path 供出 | Phase A | A | A（供出先は親の共有 keep） | 検出・設定は成果物単位 |
| body パイプライン（rename 除外） | 文字列暗号化・制御フロー・virtualization・整数演算・定数畳み込み 等 | Phase A | A | A | クラス単位で完結し協調不要 |
| TransformationBudget 状態 | クラス単位の commit/rollback | Phase A | A | A | 予算判断はローカル |
| 自分の resource 実体 | MANIFEST/plugin.yml/services/module-info 等 | Phase A | A〜C | C（`module-info` のみ remap して書き出し） | パスが成果物間で衝突するため統合しない（§5.3） |
| ローカル report | 自分のパスの `TransformerReport` | Phase A | 全体 | 結果集約 | 自分の加工の報告 |

#### 3.2.3 境界（持ち主と流れ）

| 事項 | 供出（誰が作る） | 所有（誰が持つ） | 流れ |
|---|---|---|---|
| keep | 子（`platform.configure` と MANIFEST/services 走査） | 親（集約後の単一 `KeepRules`） | 子 → 親へ累積。識別子キーなので集約後はグローバルに効く |
| アノテーション方針 | 子（クラスノード上の `@NoObfuscation` 等） | 実体は子に同行 | 判定は親の Phase B rename で読む（注釈は子、決定は親） |
| 〈出自台帳〉 | 子（Phase A 後の context 内容が出自境界） | 親 | 子の内容を親が記録し、Phase C で親が引く |
| 生成ヘルパー名 | 子（body 系が生成） | 一意性方針は親 | 子が親の採番/namespace 規律に従って命名 |

#### 3.2.4 相互作用（データフロー概略）

```
登録:   親に 子(入出力+設定) を登録
Phase 0: 親が 集合出力先検証
Phase A: 各子が body 加工 →(keep 供出)→ 親の共有 keep へ累積
         各子の platform は 親の〈全体View〉で cross-module 解決
         親が〈出自台帳〉を記録
Phase B: 親が 全子の classes で〈全体クラスビュー〉を構築 → RenameTransformer を1回
         親が〈グローバルマッピング〉を確定
Phase C: 親が マッピングと〈出自台帳〉で各子へ分割
         各子は 自分の resources(module-info remap) と共に自分の jar を書く
結果:   親が 出力先・横断マッピング・per-module report を組み立てて返す
```

---

## 4. 公開契約（API層: obfaryn-api）

> 以下は**役割（何を表現できる必要があるか）**の定義。**型名・メンバ名は規定しない**。既存の単一jar実行は挙動不変であること（全体なし＝従来通り）。

### 4.1 全体View 契約

- 既存の `PlatformContext` に、**全体ビューを取得する手段**を後方互換で追加する（単一実行では「全体なし」を返し、従来の `classes`/`resources` の意味は不変）。
- platform は、`detect` ではローカルビュー、`configure` の cross-module 解決では全体ビュー、と使い分けられること。
- **メンバ名・戻り値型は未定**。少なくとも「全体の classes/resources を同じ検査インターフェースで参照できる」ことを満たす。

### 4.2 成果物記述 契約

1モジュールを表現できること。必要な情報:

- 入力 jar の位置、出力先、成果物の**安定した識別子**。
- そのモジュールの難読化設定（platform、body 系選択、各種フラグ、seed）。

**型名・フィールド名は未定**。

### 4.3 セッション結果 契約

セッション実行の結果を表現できること。必要な情報:

- 各成果物の**出力先**（識別子 → 出力パス）。
- **横断グローバルマッピング**（1本）。
- 成果物ごとの **report**。

**型名・フィールド名は未定**。

---

## 5. データモデル

### 5.1 〈全体クラスビュー / union〉

- classes = 全モジュールの classes を1つに統合（**同一 `ClassNode` 参照を共有**、コピーしない）。
- resources = **空**（resource は統合しない）。
- 名前保持 = 親の〈共有 keep 集合〉に基づく。
- identity 解決 = 全モジュールを跨ぐ入力解決器。

### 5.2 〈出自台帳〉

- 内容: **元の内部名 → 成果物識別子**。
- 構築: Phase A 完了後、各モジュールの `context.classes` に存在する全クラス名で埋める（body 系が生成したヘルパーも自動的に含む）。
- 同一内部名が複数モジュールに現れた場合は**エラー**（§8.1）。
- 分割時: 改名後の名前は `PipelineExecutor` が維持する 改名→元名 の対応（`originalClassNames`）を経由して元名に戻し、台帳を引く。

### 5.3 resource の扱い（非対称）

- class は統合、**resource は各子 context に残す**。理由: `META-INF/MANIFEST.MF`・`plugin.yml`・`META-INF/services/*`・`module-info.class` は成果物間でパスが衝突するため。
- リネーマーが書き換える resource は実質 **class 埋め込み Kotlin `@Metadata`（クラスノードに同行）** と **`module-info`** のみ。plugin.yml の `main:` や services 記載クラスは keep 対象で名前が保持されるため**書換不要**。
- `module-info` は Phase C で各子ごとに package の対応（`packageMapping` 相当）を適用して remap する。

---

## 6. 処理フロー（三相）

```
Phase A (子/ローカル): 各モジュールを rename 抜きで body 加工 → keep 供出 → 〈出自台帳〉記録
Phase B (親/1回):      〈全体クラスビュー〉に RenameTransformer を1回 → グローバルマッピング確定
Phase C (子/ローカル): union の改名クラスを出自で分割 ＋ 各子 resource(module-info remap) → jar 書き出し
```

### 6.1 Phase 0: 事前検証（親）

- 集合出力先検証: 各 output が、いずれの input/classpath/鍵、他の output と**同一でない**こと。hard link / symbolic link / `..` / 将来 destination を含めて判定。
- 入力 jar の存在確認。

### 6.2 Phase A: body 加工（子ごと）

各モジュールについて:

1. `JarReader().read(input)` → 子 `ObfuscationContext`。
2. platform 選択（子の設定の platform）、`PlatformExtensionApplier.apply`、`ObfuscationAnnotationPolicy.validate`。
3. `platform.configure(ctx, opts, 共有keep)` を呼ぶ。ここで ctx の**全体View**に親の全体ビューを供給し、cross-module の entrypoint/`methodCallClosure` を解決可能にする。
4. MANIFEST（`Main-Class` 等）・`META-INF/services/*` を走査し **共有 keep** へ集約。
5. **rename を除外した**パイプラインを子の設定で構築・実行（子が選んだ body 系のみ）。
6. Phase A 完了後、〈出自台帳〉に この子 context の全クラス名 → 成果物識別子 を記録。

> 注: この時点でクラス名はまだ難読化されていない。

### 6.3 Phase B: グローバル rename（親・1回）

1. 〈全体クラスビュー〉を構築（§5.1）。
2. `RenameTransformer(〈セッション設定〉, 共有keep).transform(全体クラスビュー)` を**1回**実行。
   - アロケータは全体の占有名から導出 → グローバル衝突なし。
   - `VirtualMethodFamilies` が全体上で cross-module のオーバーライド族を解決。
   - 参照書換（`ClassRemapper`）が全ノードの参照をグローバルマッピングで書換。
3. グローバルマッピング（class/method/field/package）を確定・保持。
4. body 系が生成したヘルパーも全体に含まれるため、ここで一緒に改名される。

### 6.4 Phase C: 分割書き出し（子ごと）

各モジュールについて:

1. 全体の改名済みクラスから、元名が この成果物識別子 に属するものを選別（改名→元名→〈出自台帳〉）。
2. その子の resources を取得し、`module-info.class` に package 対応を適用して remap。
3. `JarWriter().write(子向けビュー, output, reproducible, verify, framesIfNeeded, classpath)` で書き出し。
4. 全モジュール完了後、結果（出力先・横断マッピング・report）を返す。マッピングファイルは**1本**。

---

## 7. 設定スコープ（カスタマイズ規則）

| 設定 | スコープ | 例 | 根拠 |
|---|---|---|---|
| 適用パス（body系） | **子（ローカル）** | api=文字列暗号化しない / impl=する | クラス単位で完結し協調不要 |
| platform 割り当て | **子（ローカル）** | app=paper / api=none | detect/configure が成果物単位 |
| rename on/off | 子単位は keep で表現 | api を改名しない＝api配下を全 keep | keep は識別子キーで全体整合 |
| rename 本体・keep識別子・型グラフ | **親（グローバル）** | api記号の新名 | 1命名宇宙必須 |
| seed | 分離 | rename=親 seed / body=子 seed | 再現性を両立 |

区別: 「api が文字列を暗号化しない」＝**パス選択（ローカル）**。「api の型 X の名前を残す」＝**keep（グローバル識別子）**。

---

## 8. 不変条件・エラー処理

### 8.1 エラー（実行前に失敗）

- モジュール間の**同名クラス**（union 衝突）→ 拒否。
- 出力先が input/classpath/鍵/他 output と**同一**→ 拒否（集合出力先検証）。
- `prelinkCalls` without `callIndirection` 等、既存の設定妥当性は各子で従来どおり検査。
- パッケージ平坦化に有効な宛先が無い 等、既存規則を踏襲。

### 8.2 不変条件

- **リネーマーは全体でちょうど1つ**: Phase A のパイプラインは rename 0個、Phase B が1個。
- **budget はクラス単位 commit**（Phase A）。rename はグローバル構造パスで body budget 外。
- **アノテーション方針**（`@NoObfuscation` 等）はノードに同行し、グローバル rename 時に尊重される。
- **classpath**: 兄弟の対象 jar は全体ビューに入るため classpath から外す。外部依存のみ read-only classpath。

### 8.3 既知の制約（ドキュメント必須）

- **ヘルパー名衝突**: body系がモジュール間で同名ヘルパーを生成し得る。モジュール namespace 化 or union 構築時の衝突拒否で回避（§12-2 で方式を決定）。
- **kept外クラスを指す resource**: keep していない内部クラス名を独自設定ファイルが参照する場合、改名で壊れる → **keep の明示**を要求。
- **非原子性**: 複数 jar 書き出しは単一トランザクションではない。途中失敗で一部 jar のみ出力され得る。

---

## 9. レイヤリング（モジュール配置）

> 右欄は**役割**。新規型・タスクの名前は規定しない。

| レイヤ | 追加する役割 | 制約 |
|---|---|---|
| obfaryn-api | 全体View 契約、成果物記述 契約、セッション結果 契約 | 公開アセンブリ契約は API に置く |
| obfaryn-core | 〈全体クラスビュー〉構築・〈出自台帳〉・分割書き出し・集合出力先検証・全体View 実装 | **standard/transformer 非依存を維持** |
| obfaryn-standard | 〈セッション〉ファサード（`RenameTransformer`＋`StandardPackage` 結線） | core はリネーマーを知らない |
| obfaryn-gradle-plugin | 集約タスク、サブプロジェクト設定収集 | 既存 `obfuscateJar` は温存 |
| obfaryn-cli（任意） | 集合入力 or `--apply-mapping`（experimental） | 優先低 |

---

## 10. Gradle 利用像

> タスク名・拡張の形は未定。役割のみ示す。

- 各サブプロジェクト: 自分の `obfaryn { … }`（`platform`・`noStringEncryption`・`controlFlowMode` 等）。既存拡張を再利用。nested options と flat alias は同じ値を共有。
- root（集約）: **集約タスク（名称未定）**が全サブの jar 出力に依存し、各サブ設定と jar を〈セッション〉に集約、per-module 出力。
- keep は各サブの Platform（`obfarynPlatform` configuration 経由）が供出し、親が集約。

---

## 11. 検証

[AGENTS.md] のテスト指針に従う（JUnit Jupiter、temp dir、bytecode 妥当性＋**実行時**）。

- **core**: 全体ビュー merge/衝突拒否、集合出力先検証（hard/sym link・`..`・future destination・output重複）、分割ラウンドトリップ。
- **standard**: api+consumer 2モジュールで api 記号改名→consumer 参照追従を、**両 jar を一緒にロードして実行**（元 jar はクラスパス除外）。per-module 差（文字列暗号化 on/off）反映、entrypoint keep、`module-info` exports 書換。
- **gradle**: TestKit で Kotlin＋Groovy、複数サブプロジェクト、横断 rename 一貫性。
- **スクリプト**: `verify-cli-distribution.py` 類似で「api+impl+app をビルド→集合難読化→app 実行」を独立検証。
- 検証スコープの但し書き（live Paper・全 JVM/OS・CI 非保証）を明記。

---

## 12. 未決事項（実装前に決める）

1. **rename 抜きパイプラインの seam**: `StandardPackage` を rename 有無で分割するか、`configurePipeline` で除去するか。（クリティカルパス）
2. **ヘルパー名の衝突回避**: モジュール namespace 化 vs 共有アロケータ（§8.3）。
3. **CLI 対応の要否と方式**: 集合入力 vs 方式B（apply-mapping）。
4. **新規の型・メンバ・タスク名**: 本書は規定しない。実装着手時に決める。

---

## 13. 実装（確定した命名と方針）

本仕様のエンジン層を実装した。確定した名前と、§12 未決事項の確定方針は以下。

### 13.1 確定した型・メンバ

| 役割（仕様中の仮ラベル） | 実装名 | 位置 |
|---|---|---|
| 全体View 契約（§4.1） | `PlatformContext.wholeProgramView: PlatformContext?`（既定 `null`＝全体なし） | obfaryn-api |
| 成果物記述 契約（§4.2） | `AssemblyArtifact`（`stableId`/`input`/`output`） | obfaryn-api `assembly` |
| セッション結果 契約（§4.3） | `AssemblyResult`（`outputs`/`mapping`/`reports`） | obfaryn-api `assembly` |
| 〈全体View〉実装 | `WholeProgramView` | obfaryn-core `assembly` |
| 集合出力先検証（§8.1） | `AssemblyPaths.validate` | obfaryn-core `assembly` |
| 〈全体クラスビュー / union〉＋〈出自台帳〉 | `ProgramUnion`（`context`/`origins`） | obfaryn-core `assembly` |
| 分割書き出し（§6.4） | `ArtifactSplitter` | obfaryn-core `assembly` |
| 〈セッション〉ファサード | `AssemblySession` ＋ `AssemblyModule` | obfaryn-standard |
| 集約タスク（§10） | `obfuscateAssembly` タスク ＋ `obfarynAssembly` 拡張（`ObfarynAssemblyExtension`） | obfaryn-gradle-plugin |
| CLI（§12-3） | `assemble` サブコマンド（`AssembleCommand`） | obfaryn-cli |

`RenameTransformer`・`StandardPackage`・`Obfuscator`・`PipelineExecutor`・`JarWriter` は無改造。`ObfuscationContext`（core）は `wholeProgramView` を可変で上書きするのみ。既存の `obfuscateJar` タスクは温存し、`ObfuscationConfig` 構築を `ObfarynExtension.toObfuscationConfig` に共通化して両タスクで再利用する。

### 13.1.1 Gradle 利用像（実装）

```kotlin
// root（集約プロジェクト）
plugins { id("com.peco2282.obfaryn") }
obfarynAssembly {
  artifact(project(":api"))
  artifact(project(":impl"))
  artifact(project(":app"))
  rename { classes = true; methods = true; fields = true }
  seed = 7L                 // rename（親）seed。body seed は各サブの obfaryn{} が持つ
  outputDirectory = "build/obf"   // 省略時は各サブの build/libs
}
```

各サブプロジェクトは自分の `obfaryn { … }`（`platform`・`stringEncryption`・`applicationPackages` 等）で body 系を個別設定する。`obfuscateAssembly` は各サブの jar に依存し、設定と jar を 1 セッションに集約、per-module 出力＋単一マッピング（既定 `build/obfaryn/assembly.mapping.txt`）を書き出す。兄弟成果物の jar は union に入るため classpath から自動除外する。タスクはプラグイン適用先すべてに登録されるが、`artifact(...)` を宣言したプロジェクトでのみ実行される（他は skip）。

### 13.2 §12 未決事項の確定

1. **§12-1（rename 抜き seam）**: Phase A は各モジュールを `StandardPackage` でパイプライン構築し、`registry.ordered().filterNot { it is RenamingTransformer }` で rename だけ除外して実行。rename は Phase B の単一 `RenameTransformer` に一元化。`StandardPackage` の分割改造は不要。
2. **§12-2（ヘルパー名衝突）**: `ProgramUnion.build` が union 構築時に同名内部クラス（body 系生成ヘルパー含む）を拒否（§8.1 と同方針）。モジュール namespace 化は未採用。
3. **§12-3（CLI 方式）**: 集合入力方式を採用（apply-mapping 方式Bではない）。`assemble` が `--artifact ID=INPUT[=OUTPUT]`（繰り返し）で成果物を列挙し、session 全体の rename オプションを持つ。各成果物の body 設定は `--artifact-args ID=FILE` で `obfuscate` と同じオプションを @file 展開して与える（file 内の rename オプションは無視＝rename は session 全体）。マッピングは 1 本（既定 `assembly.mapping.txt`）。

### 13.3 既知の制約（本実装）

- モジュール個別設定での **`callIndirection` / `prelinkCalls`** は非対応（グローバル rename 後に走る必要があるため、セッション生成時に拒否）。
- モジュール個別設定での **integrity 署名** は非対応（書き出しが分割・非原子のため、拒否）。
- **`*.kotlin_module` リソース**の改名は未対応。クラス埋め込み `@Metadata` は Phase B でノードと共に書換わる（§5.3）。
- 複数 jar 書き出しは**非原子**（§8.3）。
- CLI（`assemble`）でも per-artifact の `callIndirection`/`prelinkCalls`/integrity は（セッションと同様に）拒否される。

---

## 付録 A: 既存コードとの対応

> 以下は**実在する名前**。新規追加の命名ではない。

- 単一jar処理: `TransformationEngine.transform(input, output)`, `Obfuscator.obfuscate(input, output, mapping)`。
- リネーマー: `RenameTransformer(config, keep)`、`context.classes` を命名宇宙として扱う（無改造で全体ビュー対応）。
- rename→出自追跡: `PipelineExecutor` が `originalClassNames[mapped] = original` を維持。
- platform keep 実例: `PaperPlatform.configure`（entrypoint→`keepClassNameOnly`、`keepResource`）。
- 集合検証の拡張点: `core.io.ArtifactPaths`。
- 階層解決・族: `ClassHierarchyResolver`, `VirtualMethodFamilies`。
