class Node {
    Node f1;
    Node f2;
    Node g;

    Node() {

    }

    public void foo(Node x, Node y, int i) {
        x.f2 = y;
    }
}

public class Test {
    public static void main(String[] args) {
        Node a = new Node();
        a.f1 = new Node();
        Node b = new Node();
        b.f1 = new Node();
        b.f2 = new Node();
        a.foo(a, b,1);
    }
}
