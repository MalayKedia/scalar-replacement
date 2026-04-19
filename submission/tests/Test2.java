/*
This is a slight modification of Test1, where we demonstrate that we can handle multiple fields and constructor arguments. 
We should still scalar-replace the allocation and eliminate the field reads, leaving just integer additions in the loop.
*/


class A { 
    int val;
    int y;

    A(int v) {
        val = v;
        y = val + 1;
    }
}

public class Test2 {
    public static void main(String[] args) {
        int sum1 = 0;
        int sum2 = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A(i);
            sum1 += a.val;
            sum2 += a.y;
        }
        System.out.println(sum1 + sum2);
    }
}