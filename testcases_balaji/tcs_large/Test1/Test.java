// Corner case: Inheritance where scalar replacement SHOULD still work.
// Child extends Parent but does NOT override getSum().
// So calling child.getSum() resolves to exactly Parent.getSum(),
// which only reads this.x and this.y — safe for scalar replacement.

class Parent {
  int x, y;

  int getSum() {
    return this.x + this.y;
  }
}

class Child extends Parent {
  // does NOT override getSum — inherits Parent.getSum()
  // has its own method that is never called here
  void unrelated() {
    this.x = 999;
  }
}

public class Test {
  public static void main(String[] args) {
    long sum = 0;
    for (int i = 0; i < 5000000; i++) {
      Child c = new Child();
      c.x = i;
      c.y = i + 1;
      sum += c.getSum();
    }
    System.out.println(sum);
  }
}
