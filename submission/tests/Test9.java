/*
 * Negative test. We store the reference n into b.a, letting n escape into
 * the heap. n can't be scalar-replaced after that. b itself is fine — it
 * never escapes, so the outer allocation still gets the Y treatment.
 */

class A {
    int v;
    A(int x) { v = x; }
}

class B {
    A a;
}

public class Test9 {
    public static void main(String[] args) {
        B b = new B(); // scalar-replaced
        long sum = 0;
        for (int i = 0; i < 1000000; i++) {
            A n = new A(i); // cannot be scalar-replaced, since its ref is stored in b.a
            b.a = n; // heap store of n's ref disqualifies n
            sum += n.v;
        }
        System.out.println(sum);  // 499999500000
    }
}
