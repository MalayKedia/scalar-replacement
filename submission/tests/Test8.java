/*
 * Virtual dispatch with devirtualization + specialization.
 *
 * The local is declared of the parent type (Shape) but allocated with the
 * child type (Square). The call `s.area()` is a virtualinvoke whose method
 * ref is Shape.area, but Phase-3's resolveConcreteTarget uses the
 * allocation's exact class (Square) to pick Square.area, which reads
 * `this.side`. Scalar-replacement rewrites the whole call into
 * staticinvoke Square.area$scalar_0(int side).
 *
 * Expected: the Square allocation is fully scalar-replaced, and the virtual
 * call becomes a specialized static call on the Square body.
 */

class Shape {
    int area() { return 0; }
}

class Square extends Shape {
    int side;
    Square(int s) { side = s; }
    int area() { return side * side; }
}

public class Test8 {
    public static void main(String[] args) {
        long total = 0;
        for (int i = 0; i < 10000000; i++) {
            Shape s = new Square(i);   // declared Shape, concrete Square
            total += s.area();
        }
        System.out.println(total);
    }
}
