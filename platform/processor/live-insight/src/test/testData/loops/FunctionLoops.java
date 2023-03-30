public class FunctionLoops {
    private void function() {
        for (int i = 0; i < 10; i++) {
            System.out.println(i);
        }
        for (Object o : new Object[10]) {
            System.out.println(o);
        }
        while (true) {
            System.out.println("while");
        }
        do {
            System.out.println("do");
        } while (true);
    }
}