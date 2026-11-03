package org.matsim.core.mobsim.qsim;

/**
 * Optional owner-thread observation of existing native event-processing boundaries.
 * No additional flush is performed and the order of engines, event processing and
 * After listeners is unchanged. A successful step boundary precedes After listeners,
 * so it does not cover events they subsequently produce at the same simulation time.
 * Bind in the child injector without constructor-injecting the still-constructing QSim.
 * Neither callback certifies arbitrary producers or terminal derived-event completeness.
 */
public interface QSimStepBoundaryCheck {
    /** After native events.afterSimStep returns, before any After listener is called. */
    void afterEventsProcessed(QSim qsim, double time);

    /** Only after native finishProcessing returns successfully; failures do not call this. */
    default void afterEventsFinished(QSim qsim) { }
}
