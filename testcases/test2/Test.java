class M {
  int x, y;

  public static M foo(M n){
    n.x = 10;
    M k = new M();
    M l = new M();
    bar(l);
    return k;
  }
  private static M bar(M n){
    return n;
  }
}

public class Test {
  public static void main(String[] args) {
    M o1 = new M();
    M.foo(o1);
  }
}