class HardData { int x; }

public class Test {
    void step1(HardData d) { 
        step2(d);
    } 
    
    void step2(HardData d) { 
        System.out.println(d.x); 
    }
    
    public static void main(String[] args) {
        HardData hd = new HardData(); // O13
        Test t = new Test(); // O14
        hd.x = 80;
        t.step1(hd);
    }
}