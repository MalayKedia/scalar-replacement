class A{
  public int f;
}


public class Test {
  public static void main(String[] args) {

    A a = new A(); //not scalar replacable
    A b = a;
    a = new A(); //scalar replacable
    a.f =0;
    a.f += 10;
    A c = b;
    c.f += 30;
    if(args.length > 0) {
      b.f += 5;
    } else {
      b  = new A(); //not scalar replacable
      b.f += 20;
    }
    
    
    System.out.println(a.f);
    System.out.println(b.f);
    System.out.println(c.f);

    //should print 10,20,30
  }
}
