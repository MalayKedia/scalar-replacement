

class A{
    int x;

    A(int v) {
        x = v;
    }
}

class B {
    int inner(A a){
        return a.x;
    }

    int outer(A a, int v) {
        return inner(a) + v;
    }
}

public class Test4 {
    public static void main(String[] args) {
        B b = new B(); 
        int sum = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A(i); // o25
            sum += b.outer(a, 5);
        }
        System.out.println(sum);
    }
}