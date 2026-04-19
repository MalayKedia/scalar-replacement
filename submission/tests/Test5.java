/*
 * Three-level super chain. Each constructor takes the same int, calls
 * super, and writes its own field. The analyser walks C.<init> → B.<init>
 * → A.<init> and inlines all three bodies at the allocation site.
 */

class A {
    int a;
    A(int v) { a = v * 2; }
}

class B extends A {
    int b;
    B(int v) { super(v); b = v + 1; }
}

class C extends B {
    int c;
    C(int v) { super(v); c = v * 3; }
}

public class Test5 {
    public static void main(String[] args) {
        long sum = 0;
        for (int i = 0; i < 10000000; i++) {
            C obj = new C(i); // scalar-replaced
            sum += obj.a + obj.b + obj.c;
        }
        System.out.println(sum);  // 299999980000000
    }
}
