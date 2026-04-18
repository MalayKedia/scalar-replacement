class A{
    static int counter;
    int f;
    A(){
        f=0;
        counter++;
    }
}
class B{
    static A globl;
    void foo(A a){
        if(a.f < 10) {
            globl = a;
        }
    }
}

public class Test {
    public static void main(String[] args) {
        A aa = new A();     //O20 (??) 
        B b = new B();      //O21 partially replacable
        A c = new A();      //O22 (??)

        aa.f = 5;
        b.foo(aa);
        c.f = 15;
        b.foo(c);
        System.out.println(A.counter);
    }
}
