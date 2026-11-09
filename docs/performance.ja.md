# 性能計測

これらのベンチマークは合成的で実行環境に敏感です。固定マシン上での回帰追跡のために相対的で再現
可能な数値を確立するものであり、Paper／Spring Boot サーバの保証でも、実アプリの絶対スループット
の証明でもありません。ビルドやテストとは分けて実行し、共有 CI ランナー上の小さな差はノイズとして
扱ってください。

ここには目的の異なる3種類の計測があり、互いに重複しません。

- `obfaryn-benchmarks`(JMH): 1つの変換パスだけを対象に、ウォームアップ済み・JIT最適化後の定常状態
  での呼び出しごとのオーバーヘッドを、同一の未難読化メソッドと比較します。
- `scripts/performance/run.py`: `original`/`protected`/`prepared`/`hotpath` の全体 tick ワークロード
  を複数 fork・百分位で比較します。
- `scripts/performance/virtualization.py`: 仮想化対象メソッドと素のバイトコードの VM ディスパッチコス
  トを、複数 fork・百分位・呼び出しあたりアロケーションで切り分けます。

これら3種が書き出す `results.json` には、どれも `os`/`cpu`/`cpuCount`/`gitCommit`
(`scripts/performance/environment.py`、または JMH 自身の `# VM version` 行) が含まれるようになり、
結果をどのマシン・どのコミットで取得したか追跡できます。

## 呼び出しごとのオーバーヘッド — `obfaryn-benchmarks`(JMH)

1つの変換パスの呼び出しごとの孤立したコストを計測する JMH モジュールです。`@Setup(Level.Trial)` で
小さな fixture(`bench.Workload`)を、パスを1つだけ有効にして(文字列暗号化、数値難読化、軽量制御フ
ロー、呼び出し間接化、呼び出し間接化+prelink のいずれか)トライアルごとに1回だけ難読化し、元のメソ
ッドと変換後のメソッドをそれぞれ `unreflect` で [`MethodHandle`] として束縛し、各 `@Benchmark` は
JIT がコールを消去できないよう JMH の `Blackhole` シンクを使いながら `invokeExact` で呼び出します。
`ReflectionBenchmark` は別に、直接呼び出し vs `MethodHandle#invokeExact` vs `Method#invoke` を比較
し、他のベンチマークが `invokeExact` を選んだ基準点を与えます(`PassOverheadBenchmark`)。
`VmShapeBenchmark` は VM 仮想化のオーバーヘッドを4種の命令形状 — 通常のメソッド呼び出し、配列アク
セス、割り当て(ボクシング + 配列/`Object[]` 構築)、例外処理(非スロー・スロー両経路) — で計測し
ます。`virtualization.py` が `hot`/`tiny` の対を作る方法と同じ方法(両方のJARで他のパスをすべて無
効化し、計測対象メソッドだけを一方で仮想化する)で構築します。fixture の設定ではリネームを明示的
に無効化しているため、マッピングファイルを追わずに全バリアントが同じクラス/メソッド名で解決できます。

VM ディスパッチの複数fork百分位・分散計測(`virtualization.py` が2種の命令形状で既に計測)や tick
ワークロード全体(`run.py` が既に計測)の代替ではありません。どちらのスクリプトも計測しない、JIT済
み定常状態での1パス(`VmShapeBenchmark`では1命令形状)あたりの呼び出しコストを切り分け、
`virtualization.py` の `hot`/`tiny` よりも多くの形状をカバーするためのものです。

```bash
./gradlew :obfaryn-benchmarks:jmhJar
# 下記の既知の問題のため、生成された fat jar を直接実行することを推奨します。
java -jar obfaryn-benchmarks/build/libs/obfaryn-benchmarks-*-jmh.jar -f 2 -wi 3 -i 5
```

**既知の問題 — `./gradlew :obfaryn-benchmarks:jmh` ではなく jar を直接実行してください。** この
リポジトリの環境では、`jmh` Gradle タスクを直接実行すると、`:obfaryn-benchmarks:dependencies
--configuration jmhRuntimeClasspath` が `org.ow2.asm:*` を一貫して `9.11` に解決しているにもかかわ
らず、すべてのベンチマークが `org.objectweb.asm.util.CheckMethodAdapter`(`--verify-output` のバイト
コード検証)由来の `NoSuchMethodError: ClassWriter.hasFlags` で失敗します。`jmhJar` をビルドしてでき
た fat jar を `java -jar` で直接実行する(JMH 自身が公式に案内する主たる実行方法であり、本プロジェク
ト固有の回避策ではありません)と問題なく実行できます。これは `obfaryn-benchmarks` が宣言する依存関係
ではなく Gradle 自身のワーカープロセスのクラスローディングに起因すると見られますが、これ以上の原因
特定はしていません。Gradle/JMH プラグインのバージョンが変わった際は再確認してください。

このJDK 21 Temurinホストでの短時間実行(`-wi 2 -i 3 -w 300ms -r 300ms`、fork1)結果(ns/op、この反復
時間ではノイズが大きいため、絶対値ではなく桁と方向だけを参考にしてください):

| ベンチマーク | 元 | 変換後 |
| --- | --- | --- |
| 文字列暗号化 | 約15.5 | 約24.6 |
| 数値難読化 | 約4.0 | 約5.5 |
| 制御フロー(light) | 約6.2 | 約7.7 |
| 呼び出し間接化 | 約4.1 | 約3.6 |
| Prelink | 約4.1 | 約3.8 |
| リフレクション: 直接呼び出し / `MethodHandle#invokeExact` / `Method#invoke` | 0.4 / 3.7 / 7.1 | — |

`VmShapeBenchmark`(同じ短時間実行): 仮想化のインタプリタオーバーヘッドは予想どおり大きく、形状に
依存します。`exceptionHandlingVirtualized` は非スロー経路のみ、`*Throwing` はスロー経路です(スロー
側はVM自身のスタックトレース生成コストが仮想化の有無に関わらず支配的です):

| 形状 | 元(ns) | 仮想化後(ns) |
| --- | --- | --- |
| メソッド呼び出し | 約5 | 約100〜130 |
| 配列アクセス | 約5 | 約1300〜1850 |
| 割り当て | 約5 | 約600〜3500 |
| 例外処理(非スロー) | 約5 | 約80〜110 |
| 例外処理(スロー) | 約1400〜2300 | 約2200〜2900 |

これらの数値は1フォーク・1〜2反復(各200〜300ms)で得たもので、ベンチマークが正しく動作し、仮想化さ
れた配列/割り当て形状のコストが `hot`/`tiny` の約14倍/約2.3倍より大きく上回ること(タイトな整数ルー
プより配列/割り当て形状の方が1オペレーションあたりに多くのJDK呼び出しをブリッジするためで、妥当)を
確認するには十分ですが、具体的な倍数を安定した値として扱うには不十分です。Phase 3 のメモリ割り当て
作業でどの形状を優先するかを判断する前には、より長時間の実行が必要です。

呼び出し間接化と prelink はこの規模では有意なオーバーヘッドを示しません — 妥当な結果です。
`invokedynamic` コールサイトがリンクされた後(prelink は事前に強制リンクし、呼び出し間接化は初回呼
び出し時に遅延リンクします)、定常状態の呼び出しは通常の `MethodHandle` 呼び出しであり、置き換え前の
直接呼び出しと実質的に差がありません。文字列暗号化の復号コスト(Base64 デコード + バイト単位の XOR
ループ)はキャッシュされないため償却されません: 呼び出しごとに毎回再実行されます。これらを単なるスモ
ークテスト以上の根拠として扱う前に、より長時間・複数フォークの実行(`-f 3 -wi 5 -i 10`、`-w`/`-r` を
指定しない、概ね15〜20分)が必要です。今回はそれを実施していません。

## 合成 tick ワークロード — `scripts/performance/run.py`

Java 8 の tick ワークロードをコンパイルし、変換（strong 保護、制御フロー、呼び出し間接化、整数演算）
したうえで、`original`／`protected`／`prepared`（prelink 済み）／`hotpath`（クラス単位 `@HotPath`
の対照群）を複数 JVM fork で比較します。コールド起動、ウォームのレイテンシ百分位、tick あたりの
アロケーション、各変種で一致するチェックサムを報告し、`build/performance/results.json` を出力します。
任意のローカルゲート: `--max-p99-ratio`、`--max-extra-bytes-per-tick`。

## 仮想化ディスパッチ — `scripts/performance/virtualization.py`

メソッド仮想化のインタプリタコストを切り分けます。両方の JAR を通常パスをすべて無効にしてエンジンに
通し、唯一の違いは計測対象メソッドを一方で仮想化し他方では素のバイトコードのまま残すことです。
2 つの対象を計測します。

- `hot` — 固定のディスパッチ主体の整数ループ。呼び出しコストは VM 命令ディスパッチ／デコードが支配。
- `tiny` — 数命令のみ。呼び出しコストは呼び出しごとの付帯処理（フレーム取得／返却、ThreadLocal
  プール、引数整列、戻り値のボクシング）が支配。

`VmBenchmark.java` はコールド初回、ウォーム p50/p95/p99、呼び出しあたりのスレッドアロケーション、
そして元 JAR と仮想化 JAR で一致しなければならないチェックサム（振る舞い等価性のガード）を報告し、
`build/performance-vm/results.json` を出力します。`--max-p99-ratio` は固定で静かなホスト専用の任意
ローカルゲートです。

### 計測による特性（JDK 21 Temurin, Linux x86-64, 共有ホスト）

3 fork（各ウォーム 30 万呼び出し）の代表的な中央値。絶対値はホストにより変動します。

| 対象 | native p50 | 仮想化 p50 | 仮想化／native | 呼び出しあたりalloc |
| --- | --- | --- | --- | --- |
| `hot`（ディスパッチ主体） | 約 0.64 µs | 約 9.1 µs | 約 14 倍 | 約 40 B |
| `tiny`（呼び出し主体） | 約 30 ns | 約 70 ns | 約 2.3 倍 | 約 40 B |

共有ホストでの実行間 p50 分散は約 ±9% でした。約 40 バイト／呼び出しは、ボクシングされたインタプリタ
戻り値と、呼び出し側で生成する引数配列です。

### 最適化メモ

仮想化は実行速度と引き換えに解析耐性を得る設計であり、インタプリタのオーバーヘッドは本質的に大部分が
不可避で、VM を弱めること（たとえば仮想化コードを素の JVM 命令へ戻すこと）で削減してはなりません。
上記ベースラインに対し、保護に中立な 2 つの候補を試作し計測しました。

- 命令ごとの例外ハンドラ探索をディスパッチループ外へ巻き上げる。
- 呼び出しごとの ThreadLocal 参照 2 回を 1 回にまとめる。

いずれも約 ±9% のホスト分散と区別できる変化を生みませんでした（ThreadLocal 変更が最も効くはずの呼び
出し主体 `tiny` で 0%）。JDK 21 の HotSpot は両経路を既に十分最適化しており、どちらの変更も採用しません
でした。インタプリタのマイクロ最適化を採用する前に、固定で低ノイズなホスト上でこのベンチマークにより
再評価してください。レイテンシ重視のメソッドを仮想化対象から完全に外す手段としては `@HotPath`／明示的
除外を引き続き利用してください。

## 難読化処理自体のコスト: パスごとの経過時間

`PipelineExecutor` は各パスの実行時間(そのパス1回分の `transform()` 呼び出しを `System.nanoTime()`
で挟んで計測)を、既存のパス固有メトリクス(`transformed`、`indirectedCalls` など)と並ぶ
`elapsedNanos` メトリクスとして `TransformerReport` に記録します。`TransformationReportWriter` は
これをそのまま書き出すため(schemaVersion 2 の `metrics` マップは元々オープンエンドです — 
[docs/protection.ja.md](protection.ja.md) 参照)、変換レポートのスキーマ変更は不要です。

`scripts/report-summary.py` はこれをパスごとの `elapsedMillis` と、合計の `total.elapsedMillis`
として JSON・テキスト・HTML の出力に反映します — 既に生成済みのレポートに対して `--transformation-
report methods.json` を実行するだけで(難読化の再実行は不要)、処理時間がパスごとにどこへ使われたか
がわかります:

```
[call-indirection] methods=9 applied=8 skipped=1 rejected=0 growth=238.6% near-limit=0 time=23.533ms
```

これは1回・1プロセスの壁時計計測(CLI 自身の `--report`/`--transformation-report` と同じ精神のプロ
ファイリング用途)であり、フォークとウォームアップを伴う JMH 級の定常状態計測ではありません。そちら
は上記の `obfaryn-benchmarks` を使ってください。一方で、与えられた入力に対しエンジン自身の処理時間
のうちどのパスが最もコストを占めるかには直接答えます。クラス階層解析・リネーム・フレーム再計算・JAR
読み書きのコストは、既存のパス単位の粒度を超えて個別には切り分けられておらず、それには
`TransformationEngine`/`JarWriter` 自体への計測器の追加が必要で、これは今回は見送っています。
