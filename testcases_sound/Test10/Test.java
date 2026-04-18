class A {
    int x;
}

class C {
    void foo(A a) {
        a.x = 99;
    }
}

class D extends C {
    void foo(A a) {
        return;
    }
}

public class Test {
    public static void main(String[] args) {
        A a = new A();
        C c = new D();
        c.foo(a);
    }
}
