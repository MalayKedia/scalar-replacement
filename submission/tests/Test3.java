/*
Here, we demonstrate that we can scalar-replace both objects o22 and o25, even though o25 is passed as an argument to a method call.
We succesfully convert the call of b.addTo(a, 5) to a new static call addTo$scalar_0_1(a_x, 5), which allows us to eliminate the allocation of a and the field read of a.x.
*/

class A{
    int x;

    A(int v) {
        x = v;
    }
}

class B {
    int addTo(A a, int v) {
        return a.x + v;
    }
}

public class Test3 {
    public static void main(String[] args) {
        B b = new B(); // o22
        int sum = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A(i); // o25
            sum += b.addTo(a, 5); // transformed to addTo$scalar_0_1
        }
        System.out.println(sum);
    }
}