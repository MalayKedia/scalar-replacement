class M {
  int x, y;
}

class N {
  static M global;

  void foo(M mm) {
    System.out.println(mm.x);
    bar(mm);
  }

  void bar(M mm) {
    System.out.println(mm.y);
    global = mm;
  }
}

public class Test {
  public static void main(String[] args) {
    
    M o2 = new M(); // O31
    N o3 = new N(); // O32
    o2.x = 27;
    o2.y = 125;
    o3.foo(o2);
    

    return;
  }
}