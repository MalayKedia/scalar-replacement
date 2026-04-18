class A {
    int a;
}

class B {
    A a;
}

public class Test {
    public static void main(String[] args) {
        A a1 = new A(); // O11
        A a2 = new A(); // O12
        B b1 = new B(); // O13
        b1.a = a1;
        a2 = b1.a;
    }
}
