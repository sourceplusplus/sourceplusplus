class GetReference {
    fun callerMethod() {
        calleeMethod()
    }

    companion object {
        fun calleeMethod() {
        }
    }
}