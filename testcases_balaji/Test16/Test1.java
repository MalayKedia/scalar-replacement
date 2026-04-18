class Point {
  int x, y;
}

class Vec3 {
  int a, b, c;
}

public class Test1 {
  public static void main(String[] args) {
    long sum = 0;

    // Case 1: tight loop allocating Point — scalar replaceable, no callsite
    for (int i = 0; i < 5000000; i++) {
      Point p = new Point();   // O15
      p.x = i;
      p.y = i * 3;
      sum += p.x + p.y;
    }

    // Case 2: tight loop allocating Vec3 — scalar replaceable, no callsite
    for (int i = 0; i < 5000000; i++) {
      Vec3 v = new Vec3();     // O23
      v.a = i;
      v.b = i + 1;
      v.c = i + 2;
      sum += v.a + v.b + v.c;
    }

    System.out.println(sum);
  }
}
