/*
 * Negative test. B.foo writes a.x. Our rule disqualifies any alloc passed
 * to a callee that modifies its fields — we'd have to read the updated
 * value back after the call, which our specialisation doesn't support.
 */

class A{
    int x;
}

class B {
    void foo(A a){
        a.x = 5;
    }
}

public class Test7 {
    public static void main(String[] args) {
        B b = new B(); // scalar-replaced
        long sum = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A(); // cannot be scalar-replaced, since foo modifies its field
            b.foo(a); // specialised on receiver, not on argument
            sum += a.x;
        }
        System.out.println(sum);  // 50000000
    }
}