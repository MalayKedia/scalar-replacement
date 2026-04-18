class A {
    int f;
}

class B {
    A a;
    void foo(B b) {
        return;
    }
}

public class Test {
    public static void main(String[] args) {
        A a = new A();
        B b = new B();

        b.a = a;
        b.foo(b);
    }
}
