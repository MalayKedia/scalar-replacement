class A { int x; }
class B {
    A a; 
    static B global;
    void foo(B b) {
        A p = new A(); // O6
        b.a = p;
        global = b;
        return;
    }
}
class Test {
    public static void main(String[] args) {
        A a = new A(); // O14
        B b = new B(); // O15
        b.a = a;
        b.foo(b);
    }
}