class Easy1Obj { 
    int x; 

    public Easy1Obj(int i) {
        this.x = i;
    }
}

public class Test {
    public static void main(String[] args) {
        for (int i = 0; i < 100; i++) {
            Easy1Obj obj = new Easy1Obj(i); // O7
            obj.x = obj.x + 1;
        }
    }
}