class A{
    int x;

    static int add(A a, A b){
        return a.x + b.x;
    }
}
public class Test {
    public static void main(String[] args) {
        for (int i = 0; i < 10000000; i++) {
            
            A a1 = new A();
            a1.x = 5;

            A a2 = new A();
            a2.x = 10;

            int result = A.add(a1, a2);
            if (i == 9999999) System.out.println(result);
        }
    }
}
