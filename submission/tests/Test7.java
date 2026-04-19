class A{
    int x;
}

class B {
    void foo(A a){
        a.x = 5;
    }
}

public class Test7 {
    public static void main(String[] args) {
        B b = new B(); 
        int sum = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A(); // o25
            b.foo(a);
            sum += a.x;
        }
        System.out.println(sum);
    }
}