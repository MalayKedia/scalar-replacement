// Corner case: Object escapes via the heap.
// Inner is stored into Outer.ref — the Inner object is reachable
// from an Outer object that itself escapes (passed to printInner).
// Inner should be N because storing it into an escaped object's
// field causes transitive escape.

class Inner {
  int data;
}

class Outer {
  Inner ref;

  void setRef(Inner i) {
    this.ref = i;
  }

  void printInner() {
    System.out.println(this.ref.data);
  }
}

public class Test {
  public static void main(String[] args) {
    long sum = 0;
    for (int i = 0; i < 5000000; i++) {
      Inner inner = new Inner();
      inner.data = i;

      Outer outer = new Outer();
      outer.ref = inner;  // inner stored into outer's field

      sum += outer.ref.data;
    }
    System.out.println(sum);
  }
}
