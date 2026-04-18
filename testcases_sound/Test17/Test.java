class M {
  public int x;
}

class N extends M {
  void foo(M mm) {
    System.out.println(mm.x);
  }
}
class O extends N{
    int x;
    void bar(M mm) {
        mm.x = 112;
    }
}



public class Test {
    public static void main(String[] args) {
        M o1 = new M(); //yes scalar replacable
        o1.x = 8;
        System.out.println(o1.x);

        O o2 = new O(); //yes scalar replacable //code wont allow
        N o3 = new N(); //yes scalar replacable
        o2.x = 27;
        o3.foo(o2);
        System.out.println(((M) o2).x);
        System.out.println(o2.x);
        // System.out.println(o3.x);

        M o4 = new M(); //not scalar replacable //code wont allow
        O o5 = new O(); //yes scalar replacable
        o4.x = 343;
        o5.bar(o4);
        System.out.println(o4.x);
    }
}
