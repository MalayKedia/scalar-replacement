class A {
    Object f;
    static A z;
    A y;
}

public class Test {
    public static void main(String[] args) {
        Object o = new Object();
        A a = new A();
        // a.y = new A(); 
        A.z = a;
        a.f = o;
    }
}
