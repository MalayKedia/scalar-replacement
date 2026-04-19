/*
 * Virtual dispatch on an object whose static type is a superclass but
 * whose actual allocated type we know. We devirtualise to Square.area
 * and specialise it on the scalar `side`.
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
            Shape s = new Square(i);   // scalar-replaced
            total += s.area(); // transformed to area$scalar_0
        }
        System.out.println(total);  // 17247549629376
    }
}
