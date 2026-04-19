
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
            C obj = new C(i);
            sum += obj.a + obj.b + obj.c;
        }
        System.out.println(sum);
    }
}
