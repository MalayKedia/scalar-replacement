class A {
    A s;
    int i;
}

public class Test {
    static A test(A a) {
        A b = new A();
        A c = new A();
        if (a.i > 0) {
            a.s.s = b;
        } else {
            a.s.i = 3;
        }
        return a;
    }
    
    public static void main(String[] args) {
        A a = new A();
        a.s = new A();
        test(a);
    }
}
