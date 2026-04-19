class A{
    int x;
}

class B{
    static A cached;
    int hits;

    void foo(A a){
        if (cached == a) {
            hits++;
        }
     }
}

public class Test6 {
    public static void main(String[] args) {
        B b = new B();
        A a = new A();
        B.cached = a;
        for (int i = 0; i < 10000000; i++) {
            b.foo(a);
        }
        System.out.println(b.hits);
    }
}