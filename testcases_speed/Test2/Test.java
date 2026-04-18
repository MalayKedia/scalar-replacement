class A{
    static int c = 0;

    static int f(B x){
        c = c + 1;
        return x.value + c;
    }
}

class B{
    int value;
}

public class Test {
    int y;

    public static void main(String[] args) {
        B b = new B();
        b.value = 10;

        for (int i = 0; i < 20000000; i++) {
            b.value = A.f(b);
        }
        System.out.println(b.value);
    }
}
