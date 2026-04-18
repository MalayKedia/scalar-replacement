class Obj {}

public class Test {
    public static void main(String[] args) {
        Test t = new Test();

        t.foo();
    }

    void foo() {
        Obj a = new Obj();

        a = bar(a);
    }

    Obj bar(Obj a) {
        return a;
    }
}
