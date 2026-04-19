/*
 * Two fields and a constructor that computes one from the other. Both
 * fields become plain int locals and the <init> body gets inlined at the
 * allocation site.
 */


class A { 
    int val;
    int y;

    A(int v) {
        val = v;
        y = val + 1;
    }
}

public class Test2 {
    public static void main(String[] args) {
        long sum1 = 0;
        long sum2 = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A(i+2); // scalar-replaced
            sum1 += a.val;
            sum2 += a.y;
        }
        System.out.println(sum1 + sum2);  // 100000000000000
    }
}