// Corner case: Object used as BOTH receiver AND argument.
// Wrapper.getVal() only reads this.v — safe as receiver.
// But the object is also passed as an argument to consume().
// Our pessimistic check should reject scalar replacement because
// the object appears as an explicit argument at a call site.

class Wrapper {
  int v;

  int getVal() {
    return this.v;
  }
}

public class Test {
  static int consume(Wrapper w) {
    return w.v * 2;
  }

  public static void main(String[] args) {
    long sum = 0;
    for (int i = 0; i < 5000000; i++) {
      Wrapper w = new Wrapper();
      w.v = i;
      sum += w.getVal();   // receiver call — safe
      sum += consume(w);   // argument call — prevents scalar replacement
    }
    System.out.println(sum);
  }
}
