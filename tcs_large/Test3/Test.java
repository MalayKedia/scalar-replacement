class A{
    int x;

    void addTo(A a){
        a.x += this.x;
    }
}
public class Test {
    public static void main(String[] args) {
        for (int i = 0; i < 10000000; i++) {
            
            A a1 = new A();
            a1.x = 5;

            A a2 = new A();
            a2.x = 10;

            a1.addTo(a2);
            if (i == 9999999) System.out.println(a1.x);
        }
    }
}
