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
