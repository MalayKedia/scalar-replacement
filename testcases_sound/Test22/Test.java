class Hard3Data { int x; }

public class Test {
    static Hard3Data global;
    
    void maybeEscape(Hard3Data d, boolean flag) {
        if (flag) {
            global = d;
        } else {
            System.out.println(d.x);
        }
    }
    
    public static void main(String[] args) {
        Hard3Data hd = new Hard3Data(); // O15
        Test t = new Test(); // O16
        t.maybeEscape(hd, false);
    }
}