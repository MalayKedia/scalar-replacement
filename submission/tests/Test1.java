/*
 * Simplest case — one allocation per iteration, written once, read once.
 * Expect the new A() and the field access to both disappear, leaving an
 * integer add in the loop body.
 */


class A { 
    int val;
}

public class Test1 {
    public static void main(String[] args) {
        long sum = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A(); // scalar-replaced
            a.val = i;
            sum += a.val;
        }
        System.out.println(sum);  // 49999995000000
    }
}