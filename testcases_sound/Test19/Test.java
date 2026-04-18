class Easy4Obj { 
    int x; 
}

public class Test {
    static void readOnly(Easy4Obj obj) {
        System.out.println(obj.x);
    }
    
    public static void main(String[] args) {
        Easy4Obj obj = new Easy4Obj(); // O11
        obj.x = 40;
        readOnly(obj);
    }
}