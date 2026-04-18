class A { int f; }

public class Test {
    public static void main(String[] args) {
        A a = new A(); // O5
        A c = new A(); // O6
        A b;
        if (args.length > 0) {
            b = a;
            b.f = 10;
        } else {
            b = c;
            b.f = 10;
        }
        int x = b.f;
    }
}