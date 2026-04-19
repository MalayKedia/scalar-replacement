/*
 * Bigger scenario. Two Vec3s built per iteration, handed to a helper
 * with cross/dot. u and v get scalar-replaced; the Vec3 that cross
 * returns stays a real ref (it's allocated inside cross), and the dot
 * call that uses it gets specialised only on u.
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
        Vec3Ops ops = new Vec3Ops(); // scalar-replaced
        long sum = 0;
        for (int i = 0; i < 10000000; i++) {
            Vec3 u = new Vec3(i, 3*i + 1, i + 2); // scalar-replaced
            Vec3 v = new Vec3(i + 3, i + 4, 2*i + 5); // scalar-replaced
            Vec3 w = ops.cross(u, v); // transformed to cross$scalar_0_1_2
            Vec3 z = new Vec3(i+5, 2*i+6, 3*i+7); // scalar-replaced
            sum += ops.dot(w, z); // transformed to dot$scalar_0_2
        }
        System.out.println(sum);  // -10496486156736
    }
}
