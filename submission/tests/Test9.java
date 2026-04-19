/*
 * Negative test — heap escape via storing a reference into another object's
 * field.
 *
 * Inside the loop, A n is freshly allocated and immediately written into
 * B l's `a` field (`l.a = n`). Our heap-store disqualifier fires:
 * any param/alloc ref used as the RHS of a field store is marked as
 * escaping, so A allocations are not replaceable.
 *
 * The B allocation itself is harmless (only has writes to its own
 * `a` field, nothing escapes, no identity use), so the analyzer still
 * reports `l` as Y[] — a nice demonstration that the disqualifier targets
 * the escaping value, not the base.
 *
 * Expected: exactly one Y[] verdict (for the B, allocated outside the
 * loop); the per-iteration A allocation survives as a `new`.
 */

class A {
    int v;
    A(int x) { v = x; }
}

class B {
    A a;
}

public class Test9 {
    public static void main(String[] args) {
        B l = new B();
        long sum = 0;
        for (int i = 0; i < 1000000; i++) {
            A n = new A(i);
            l.a = n;          // heap store of n's ref → disqualifies n
            sum += n.v;
        }
        System.out.println(sum);
    }
}
