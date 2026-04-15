We write our code in Soot in Java framework.

We will start with code to identify scalar replacable objects by doing some sort of naive interprocedural analysis to identify objects which do not escape the lifetime of the allocator function, and are not modified inside the function calls in which it is passed as an argument.

Then, we want to use code transformations to achieve scalar replacement. 

After that, we want to extend our escape analysis to partial escape analysis, and try to do speculative scalar replacement, reconstructing the object if it actually escapes.