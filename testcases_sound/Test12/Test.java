class A {
    A f;
    int g;
}

public class Test {
    public static void main(String[] args) {
        A a = new A();
        A c = new A();
        A b;
        // a.f = c;

        if (args.length > 0) {
            b = a;
        } else {
            b = c;
        }

        b.g = 10;
    }
}
