package org.matsim.core.mobsim.qsim;

/**
 * Optional, synchronous preparation boundary for one QSim run. Bind an
 * implementation in the QSim child module; do not inject QSim into its
 * constructor, since QSim is still being constructed during member injection.
 * A per-run implementation may install observation before preparation and audit
 * the actual prepared agents and engines afterwards. It must not synthesize
 * Initialized callbacks or treat this boundary as an arbitrary-producer seal.
 *
 * <p>Validation failures should be RuntimeExceptions. QSim's existing run/finally
 * cleanup path handles them. Cleanup listeners must not throw in an attempt to
 * report the same failure: a failing BeforeCleanup listener can prevent engine
 * cleanup. Preparation may itself fail, in which case afterPrepared is not called.
 */
@FunctionalInterface
public interface QSimPreparationCheck {
    /** After default handlers are appended; before event processing and agent creation. */
    default void beforePreparation(QSim qsim) { }

    /** After all native preparation returns; before Initialized or initial agent arrangement. */
    void afterPrepared(QSim qsim);
}
