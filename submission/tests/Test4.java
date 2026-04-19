/*
 * Nested specialisation. B.outer(a) calls B.inner(a). Both only read a.x.
 * The analyser specialises outer, and while rewriting outer's body it
 * also specialises inner — the scalar flows through two levels.
 */

class A{
    int x;

    A(int v) {
        x = v;
    }
}

class B {
    int inner(A a){
        return a.x;
    }

    int outer(A a, int v) {
        return inner(a) + v; // transformed to inner$scalar_0_1, which takes an int and returns an int
    }
}

public class Test4 {
    public static void main(String[] args) {
        B b = new B(); // scalar-replaced
        long sum = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A(i); // scalar-replaced
            sum += b.outer(a, 5); // transformed to outer$scalar_0_1
        }
        System.out.println(sum);  // 50000045000000
    }
}