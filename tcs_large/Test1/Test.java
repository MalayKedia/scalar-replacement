class Easy1Obj { 
    int x; 
}

public class Test {
    public static void main(String[] args) {
        for (int i = 0; i < 100; i++) {
            Easy1Obj obj = new Easy1Obj(); // O7
            obj.x = 10;
            System.out.println(obj.x);
        }
    }
}