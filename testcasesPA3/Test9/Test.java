class BonusData { int x; }

public class Test {
    static void mutate(BonusData d) { 
        d.x = 99; 
    }
    
    public static void main(String[] args) {
        BonusData b1 = new BonusData(); // O9
        BonusData b2 = b1; // Aliasing
        
        // Modifying b2 modifies the object allocated at O9
        mutate(b2); // O13
    }
}