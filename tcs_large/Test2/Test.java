class A{
    static int f(int x){
        int y = x + 1;
        return y;
    }
}

class Node{
    int value;
}

public class Test {
    int y;

    public static void main(String[] args) {
        Node node = new Node();
        node.value = 10;

        for (int i = 0; i < 10000000; i++) {
            node.value = A.f(node.value);
        }
        System.out.println(node.value);
    }
}
