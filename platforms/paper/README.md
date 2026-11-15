# Paper cache

`@PaperCache` は、難読化時にキャッシュ用クラスの組み込みを指示するビルド専用アノテーションです。実際の起動時には使用しません。組み込まれるクラス単位の遅延キャッシュは、VM や文字列エンコードに依存せず、繰り返し計算を避けます。

## 適している用途

- 複数の呼び出し元で共有できる、不変の参照dataや計算済みtable
- 引数を取らず、同じclass loader内で結果を1つだけ保持すればよい処理

Bukkit APIを使う初期化をmain threadへ移す機能ではありません。呼び出しごと・instanceごと・引数ごとに異なる結果が必要な処理にも使用しないでください。

## 使い方

Paper platform をコンパイル時のみの依存関係として追加し、引数なしの public コンストラクタを持つ public な `Supplier<T>` 実装に指定します。

```java
import com.peco2282.obfaryn.platform.paper.annotation.PaperCache;
import com.peco2282.obfaryn.platform.paper.cache.PaperCaches;
import java.util.function.Supplier;

@PaperCache
public final class LookupTable implements Supplier<int[]> {
  public LookupTable() {}

  @Override
  public int[] get() {
    int[] table = new int[4096];
    for (int i = 0; i < table.length; i++) table[i] = i * i;
    return table;
  }
}

// イベント処理などから呼び出す。2 回目以降は同じ結果を再利用する。
int[] table = PaperCaches.get(LookupTable.class);

// 設定の再読み込みなど、再計算が必要になったとき。
PaperCaches.invalidate(LookupTable.class);
```

`paper` platform（または Paper の自動検出）で処理すると、アノテーションを検出・検証し、コンパイル済みのキャッシュ用クラス群を出力JARに自動追加します。

変換後は次の状態になります。

- 検出した`@PaperCache`はclassから除去される
- annotation定義classは自動追加されない
- factoryやcacheへの参照は通常の難読化処理で更新される
- 実行時に必要なのは、出力JARへ組み込まれたcache classだけになる

`CLASS`保持は、難読化時に入力bytecodeから読み取るためのものです。実行時にannotationを検索しません。そのため、実行時のObfaryn・annotation JAR・ASM・Kotlin・Java compilerは不要です。factoryの継承元を使う場合は、`Supplier`継承関係を検証できるよう、継承元classも入力JARに含めてください。

## 振る舞いと制約

- キャッシュは引数やインスタンスではなく、ファクトリの `Class` ごとに 1 件です。アノテーションだけでは `Supplier.get()` の直接呼び出しはキャッシュされません。
- 同じ値を全呼び出し元で共有するので、不変データや参照用テーブルに利用してください。Bukkit API を使う初期化をメインスレッドへ移動する機能ではありません。
- 初回の呼び出しスレッドで生成し、同時アクセスでも生成成功は 1 回です。`null` もキャッシュし、例外は保存せず次回再試行します。同じファクトリの再帰的な初期化はエラーになります。ファクトリ同士の循環依存も避けてください。
- 無効化後の呼び出しは新しいキャッシュを使用します。既に処理中の呼び出しは古い値を返す場合があります。
- `ClassValue` を使用するため、クラス名が同じでもクラスローダーが異なれば別のキャッシュになり、グローバルなクラスキーの Map は保持しません。
- アノテーションがない入力には何も追加しません。キャッシュ実装の二重同梱は競合として拒否します。未処理の開発環境では Paper platform の JAR を実行クラスパスにも置いてください。
