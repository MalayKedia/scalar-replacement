class A {
    int f;
}

public class Test {
    public static void foo(A x) {
        System.out.println(x.f);
    }
    public static void bar(A x) {
        x.f = 100;
    }

    public static void main(String[] args) {
        A a = new A();
        A b = new A();

        foo(a);
        foo(b);
        bar(b);
    }
}
