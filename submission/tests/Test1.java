/*
This is a simple test that allocates an object in a loop and reads from it.
We should scalar-replace the allocation and eliminate the field read, leaving just an integer addition in the loop.
*/


class A { 
    int val;
}

public class Test1 {
    public static void main(String[] args) {
        int sum = 0;
        for (int i = 0; i < 10000000; i++) {
            A a = new A();
            a.val = i;
            sum += a.val;
        }
        System.out.println(sum);
    }
}