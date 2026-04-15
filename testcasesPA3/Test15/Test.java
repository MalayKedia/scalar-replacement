class Point {
    double x;
    double y;
}

class Line {
    static Point pGlobal;
    Point p2;
    public Line(Point p1, Point p2, Point p3) {
        pGlobal = p1;
        this.p2 = new Point(); // O11
        this.p2.x = p2.x;
        this.p2.y = p2.y;
    }
}

public class Test {
    public static void main(String[] args) {
        Point a = new Point(); // O19
        Point b = new Point(); // O20
        Point c = new Point(); // O21
        Line l = new Line(a, b, c); // O22
        System.out.println(a.x);
        System.out.println(b.x);
        System.out.println(c.x);
    }
}
