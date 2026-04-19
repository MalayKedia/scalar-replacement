/*
 * Negative test. foo compares `cached == a` — identity use on the passed
 * reference. Scalar replacement can't preserve identity (two scalar
 * expansions of the "same" object would have distinct references), so
 * the analyser refuses to touch a.
 */

class A{
    int x;
}

class B{
    static A cached;
    int hits;

    void foo(A a){
        if (cached == a) { // identity check, needs object identity
            hits++;
        }
     }
}

public class Test6 {
    public static void main(String[] args) {
        B b = new B(); // cannot be scalar-replaced, since foo modifies its field
        A a = new A(); // cannot be scalar-replaced, since it might escape to B.cached
        B.cached = a;
        for (int i = 0; i < 10000000; i++) {
            b.foo(a);
        }
        System.out.println(b.hits);  // 10000000
    }
}