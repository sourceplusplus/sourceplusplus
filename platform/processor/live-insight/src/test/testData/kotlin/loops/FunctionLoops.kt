class FunctionLoops {
    private fun function() {
        for (i in 0..9) {
            System.out.println(i)
        }
        for (o in arrayOfNulls<Object>(10)) {
            System.out.println(o)
        }
        while (true) {
            System.out.println("while")
        }
        do {
            System.out.println("do")
        } while (true)
    }
}