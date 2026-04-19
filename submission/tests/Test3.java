/*
 * Here a is passed to B.addTo, which only reads a.x. Since the callee
 * doesn't mutate or hold on to the reference, we specialise addTo to take
 * the field as an int — both B and A are scalar-replaced.
 */

class A{
    int x;

    A(int v) {
        x = v;
    }
}

class B {
    int addTo(A a, int v) {
        return a.x + v;
    }
}

public class Test3 {
    public static void main(String[] args) {
        B b = new B(); // scalar-replaced
        long sum = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A(i); // scalar-replaced
            sum += b.addTo(a, 5); // transformed to addTo$scalar_0_1
        }
        System.out.println(sum);  // 50000045000000
    }
}