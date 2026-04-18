// Corner case: Scalar replacement across control flow in the caller.
// The object is allocated once, fields are written differently on
// each branch, and then a safe receiver call reads the field.
// The scalar local must correctly carry the value from whichever
// branch was taken. Should be Y[callsite].

class Counter {
  int count;

  int getCount() {
    return this.count;
  }
}

public class Test {
  public static void main(String[] args) {
    long sum = 0;
    for (int i = 0; i < 5000000; i++) {
      Counter ctr = new Counter();
      if (i % 2 == 0) {
        ctr.count = i;
      } else {
        ctr.count = i * 3;
      }
      sum += ctr.getCount();
    }
    System.out.println(sum);
  }
}
