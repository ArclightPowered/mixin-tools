package io.izzel.arclight.mixin;

import java.lang.invoke.MethodHandle;

public interface DecorationOps {

    /**
     * Invoke the original callsite, or injection point specified in @At.
     * <p>
     * The type of callsite handler is:
     * <ul>
     *     <li>INVOKE - same type as the INVOKE target method type,</li>
     *     <li>NEW - argument types same as NEW constructor type and return type same as the NEW type,</li>
     *     <li>FIELD - same as @Redirect.</li>
     * </ul>
     *
     * @return callsite handler
     */
    static MethodHandle callsite() {
        throw new IllegalStateException("Not implemented.");
    }

    /**
     * Cancel current target method invocation and return.
     *
     * @return cancel handler, with type () -> &lt;target method return type&gt;
     *
     * @apiNote A return statement <b>MUST</b> be followed after this call. When cases it may eliminate those returns,
     * {@link #blackhole()} shall be used:
     * <pre>
     * {@code
     * @Decorate(method = "test", at = @At(value = "INVOKE", target = "Ljava/io/PrintStream;println(Ljava/lang/String;)V"))
     * public void inject(PrintStream s, String s2, @Local(ordinal = 0) Object o) throws Throwable {
     *     if (s2.equals(o)) {
     *         DecorationOps.callsite().invoke(s, s2);
     *     } else {
     *         DecorationOps.cancel().invoke();
     *         return;
     *     }
     *     DecorationOps.blackhole().invoke();
     * }
     * }
     * </pre>
     */
    static MethodHandle cancel() {
        throw new IllegalStateException("Not implemented.");
    }

    /**
     * A compiler hint that prevents dead code elimination.
     *
     * @return blackhole handler, with type (any) -> ()
     */
    static MethodHandle blackhole() {
        throw new IllegalStateException("Not implemented.");
    }

    static Throwable jumpToLoopStart() {
        return new Throwable("Not implemented.");
    }

    static Throwable jumpToLoopEnd() {
        return new Throwable("Not implemented.");
    }

    static Throwable jumpToCodeBlockEnd() {
        return new Throwable("Not implemented.");
    }
}
