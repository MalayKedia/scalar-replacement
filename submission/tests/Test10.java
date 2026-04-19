/*
 * Two distinct allocations per iteration feeding a specialized helper that
 * takes both.
 *
 * Vec2Ops.dot reads from two Vec2 params; Vec2Ops.sum reads from one.
 * Phase-3 specializes each helper against the scalarized-position set
 * implied by the call site:
 *   dot → dot$scalar_0_1_2(int, int, int, int)  (receiver's zero fields,
 *                                                 then u.x, u.y, v.x, v.y)
 *   sum → sum$scalar_0_1(int, int)               (u.x, u.y)
 * Both Vec2 allocations AND the Vec2Ops receiver are eliminated; the
 * transformed loop body is pure integer arithmetic.
 *
 * Expected: three Y[...] verdicts (two Vec2s + the Vec2Ops receiver).
 */

class Vec3 {
    int x, y, z;
    Vec3(int a, int b, int c) { x = a; y = b; z = c; }
}

class Vec3Ops {
    Vec3 add(Vec3 u, Vec3 v) {
        return new Vec3(u.x + v.x, u.y + v.y, u.z + v.z);
    }

    int dot(Vec3 u, Vec3 v) {
        return u.x * v.x + u.y * v.y + u.z * v.z;
    }

    Vec3 cross(Vec3 u, Vec3 v) {
        return new Vec3(u.y * v.z - u.z * v.y,
                        u.z * v.x - u.x * v.z,
                        u.x * v.y - u.y * v.x);
    }
}

public class Test10 {
    public static void main(String[] args) {
        Vec3Ops ops = new Vec3Ops();
        int sum = 0;
        for (int i = 0; i < 10000000; i++) {
            Vec3 u = new Vec3(i, i + 1, i + 2);
            Vec3 v = new Vec3(i + 3, i + 4, i + 5);
            Vec3 w = ops.cross(u, v);
            sum += ops.dot(w, u);
        }
        System.out.println(sum);
    }
}
