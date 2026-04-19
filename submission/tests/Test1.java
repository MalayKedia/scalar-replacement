// Scenario: mixed Y / P / L / disqualified cases in one file.
//   - o1: pure-local M, only direct field access — fully scalar-replaced (Y).
//   - o2, o3: passed to N.foo, which forwards to N.bar — both specialized (Y).
//   - o4: passed to O.foo; CHA sees P.foo override that mutates, so disqualified.
//   - o5: receiver of O.foo, O.foo is pure-read — scalar-replaced (Y).
// Expected output: 8 / 27 / 125 / 343

class M {
    int x;
    int y;
}

class N {
    void foo(M mm) {
        System.out.println(mm.x);
        bar(mm);
    }
    void bar(M mm) {
        System.out.println(mm.y);
    }
}

class O {
    void foo(M mm) {
        System.out.println(mm.x);
    }
}

class P extends O {
    void foo(M mm) {
        mm.x = 112;
    }
}

public class Test1 {
    public static void main(String[] args) {
        {
            M o1 = new M();
            o1.x = 8;
            System.out.println(o1.x);
        }
        {
            M o2 = new M();
            N o3 = new N();
            o2.x = 27;
            o2.y = 125;
            o3.foo(o2);
        }
        {
            M o4 = new M();
            O o5 = new O();
            o4.x = 343;
            o5.foo(o4);
        }
    }
}
