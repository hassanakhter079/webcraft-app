package java.lang.invoke;

/** Compile-time stub only: android.jar lacks it, and D8 desugars lambdas so it never ships. */
public final class LambdaMetafactory {
    public static CallSite metafactory(MethodHandles.Lookup caller, String name, MethodType type,
            MethodType samType, MethodHandle impl, MethodType instantiatedType) { throw new AssertionError(); }
    public static CallSite altMetafactory(MethodHandles.Lookup caller, String name, MethodType type,
            Object... args) { throw new AssertionError(); }
}
