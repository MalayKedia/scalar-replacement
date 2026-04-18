// Corner case: Inheritance where scalar replacement should FAIL.
// Base.process() only reads this.val, but Sub overrides it and
// writes this.val. CHA resolves Base.process() to both targets,
// so the receiver is conservatively marked as modified in call.

class Base {
  int val;

  int process() {
    return this.val * 2;
  }
}

class Sub extends Base {
  int process() {
    this.val = this.val + 10;  // writes to this.val!
    return this.val;
  }
}

public class Test {
  public static void main(String[] args) {
    long sum = 0;
    for (int i = 0; i < 5000000; i++) {
      Base b = new Base();
      b.val = i;
      sum += b.process();
    }
    System.out.println(sum);
  }
}
