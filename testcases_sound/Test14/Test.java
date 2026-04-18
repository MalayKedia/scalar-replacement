class A {
    int f = 0;
}

public class Test {
    public static void main(String[] args) {
        A b = new A();
        for (int i=0; i<args.length; i++) {
            b = new A();
        }
        b = new A();
        b.f = 10;
    }
}
