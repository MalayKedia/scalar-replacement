class A{
    int f;
}

public class Test {
    public static void foo(){
        A a = new A();
        a = bar(a);
    }
    public static A bar(A x){
        return x;
    }

    public static void main(String[] args){
        foo();
    }
}
