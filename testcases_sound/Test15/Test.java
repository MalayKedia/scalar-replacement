class A {
    B f;

    public void foo(A a) {
        bar(a);
    }

    void bar(A a) {
        a.f.g = 10;
    }
}

class B {
    int g;
}

public class Test {
    public static void main(String[] args) {
        A a = new A();
        a.f = new B();
        a.foo(a);
    }
}
