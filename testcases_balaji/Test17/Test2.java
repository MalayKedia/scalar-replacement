class A{
  public int f;
}


public class Test2 {
  public static void main(String[] args) {
    A a = new A();

    A b = a;

    A c = a;

    if (a.f == 0) {

        b.f = 3;

    } else {

      c.f = 2;

    }
    System.out.println(a.f);
    System.out.println(b.f);
    System.out.println(c.f);
  }
}
