package com.mermergi.dsh;

/**
 * Latches "the Termux bridge is not executing commands" so background retry loops stop
 * spamming the user.
 *
 * <p>Observed on a fresh Meizu 18 (v1.1): when the bootstrap command has not run yet, Termux
 * has no {@code allow-external-apps} and rejects every {@code RUN_COMMAND} with error code 2 —
 * each rejection posts a Termux error notification, and {@link StatusPoller} used to fire one
 * every 2 seconds. Once this gate trips, pollers go silent; it clears itself the moment the
 * status endpoint actually answers (i.e. the Termux side demonstrably works again), or when
 * the user explicitly claims to have fixed things in the wizard.
 */
final class BridgeGate {

    private static volatile boolean broken;

    private BridgeGate() {
    }

    static boolean isBroken() {
        return broken;
    }

    static void markBroken() {
        broken = true;
    }

    static void clear() {
        broken = false;
    }
}
