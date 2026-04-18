class A{
  public int f;
}


public class Test {
  public static void main(String[] args) {

    A a = new A();
    for (int i = 0; i<10000000; i++) {

      A b = a;
      A c = a;

      if (args.length > 0) {
        b.f += 3;
      } else {
        c.f += 2;
      }

      a.f+=1;
      b.f+=1;
      c.f-=1;
      
      if (i==9999909) {
        System.out.println(a.f);
        System.out.println(b.f);
        System.out.println(c.f);
      }
    }
    System.out.println("done");
  }
}
