class AliasFlow { 
    int val; 
}

public class Test {
    static void mutate(AliasFlow obj) {
        obj.val = 99;
    }
    
    public static void main(String[] args) {
        AliasFlow a = new AliasFlow(); // O11
        AliasFlow b = a;               // 'b' aliases 'a'
        
        b = new AliasFlow();           // O14: 'b' is reassigned, breaking the alias
        
        mutate(b);
        System.out.println(a.val);
    }
}