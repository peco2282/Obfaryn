# Paper cache

`@PaperCache` は、難読化時にキャッシュ用クラスの組み込みを指示するビルド専用アノテーションです。実際の起動時には使用しません。組み込まれるクラス単位の遅延キャッシュは、VM や文字列エンコードに依存せず、繰り返し計算を避けます。

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

`paper` platform（または Paper の自動検出）で処理すると、アノテーションを検出・検証し、コンパイル済みのキャッシュ用クラス群を出力 JAR に自動追加します。検出した `@PaperCache` はクラスから除去し、アノテーション定義クラスは自動追加しません。`CLASS` 保持は難読化時に入力バイトコードから読み取るためであり、実行時のアノテーション検索は行いません。実行時に必要なのは組み込まれたキャッシュ用クラスだけで、Obfaryn、アノテーション用 JAR、ASM、Kotlin、Java コンパイラを用意する必要はありません。ファクトリやキャッシュへの参照は通常の難読化処理で更新されます。ファクトリの継承元を使う場合、その Supplier 継承関係を検証できるよう継承元のクラスも入力 JAR に含めてください。

- キャッシュは引数やインスタンスではなく、ファクトリの `Class` ごとに 1 件です。アノテーションだけでは `Supplier.get()` の直接呼び出しはキャッシュされません。
- 同じ値を全呼び出し元で共有するので、不変データや参照用テーブルに利用してください。Bukkit API を使う初期化をメインスレッドへ移動する機能ではありません。
- 初回の呼び出しスレッドで生成し、同時アクセスでも生成成功は 1 回です。`null` もキャッシュし、例外は保存せず次回再試行します。同じファクトリの再帰的な初期化はエラーになります。ファクトリ同士の循環依存も避けてください。
- 無効化後の呼び出しは新しいキャッシュを使用します。既に処理中の呼び出しは古い値を返す場合があります。
- `ClassValue` を使用するため、クラス名が同じでもクラスローダーが異なれば別のキャッシュになり、グローバルなクラスキーの Map は保持しません。
- アノテーションがない入力には何も追加しません。キャッシュ実装の二重同梱は競合として拒否します。未処理の開発環境では Paper platform の JAR を実行クラスパスにも置いてください。
