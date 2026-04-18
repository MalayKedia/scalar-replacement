class A {
    int a;
}

class B {
    A a;
    static A b;
    void foo(A a1, A a2) {
        System.out.println(a1.a);
        if(a1.a > 10) {
            b = a2;
        }else {
            a1.a = 50;
        }
    }
}

public class Test {
    static A escape;
    public static void main(String[] args) {
        A a1 = new A(); // O21  not partially replacable
        A a2 = new A(); // O22  partial replacable
        A a3 = new A(); // O23  partially replacable
        A a4 = new A(); // O24  fully replacable
        a1.a = 20;
        a2.a = 30;
        a3.a = 40;
        a4.a = 50;

        B b1 = new B(); // O30   fully replacable
        b1.foo(a1, a2);

        if(args.length > 0) {
            escape = a3;
        } else {
            a4.a = a1.a + a2.a;
        }
        System.out.println(a1.a);
        System.out.println(a2.a);
        System.out.println(a3.a);
        System.out.println(a4.a);
    }
}
