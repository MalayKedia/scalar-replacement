class Medium2Obj { int x; }

public class Test {
    static void modify(Medium2Obj obj) {
        obj.x = 60;
    }
    
    public static void main(String[] args) {
        Medium2Obj obj = new Medium2Obj(); // O9
        modify(obj);
    }
}