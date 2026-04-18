class Easy2Obj { 
    int x; 
}

public class Test {
    static Easy2Obj global;
    
    public static void main(String[] args) {
        Easy2Obj obj = new Easy2Obj(); // O9
        obj.x = 20;
        global = obj;
    }
}