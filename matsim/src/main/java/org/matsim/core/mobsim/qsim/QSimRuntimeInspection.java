package org.matsim.core.mobsim.qsim;

import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only, assembly-local snapshot of actual QSim roles, not injector declarations.
 * Capture only at a registration-quiescent boundary. Existing mutators remain
 * unchanged and do not share a lock; this is neither an atomic concurrent snapshot
 * nor a registration/producer seal. It does not inspect arbitrary nested callbacks.
 * Default handlers appended by run() are absent before run and present thereafter.
 *
 * <p>Private retained references permit identity comparison within one QSim, but
 * no native component is returned. Public local alias groups describe repeated
 * occurrences; equal descriptions across QSim instances are NOT identity proof.
 * Hidden classes use a non-unique nest-host label without runtime address suffixes.
 * Retaining a snapshot retains its components; release it after the assembly audit.
 */
public final class QSimRuntimeInspection {
    private QSimRuntimeInspection() { }

    public enum Role {
        EVENT_MANAGER, ACTIVE_NETWORK_ENGINE, ACTIVE_ACTIVITY_ENGINE,
        ACTIVE_TELEPORTATION_ENGINE, EARLY_WITHIN_DAY_ENGINE, REGISTERED_ENGINE,
        STEP_ENGINE, ACTIVITY_HANDLER, DEPARTURE_HANDLER, AGENT_SOURCE, AGENT_TRACKER,
        LISTENER_REGISTRATION_PAIRS, INITIALIZED_LISTENER, BEFORE_STEP_LISTENER,
        AFTER_STEP_LISTENER, BEFORE_CLEANUP_LISTENER
    }

    /** Position is zero-based in its role; identityGroup is a one-based local alias. */
    public record Component(int position, int identityGroup, String classLabel) { }
    public record RoleDescription(Role role, List<Component> components) {
        public RoleDescription { Objects.requireNonNull(role); components = List.copyOf(components); }
    }

    public static final class Snapshot {
        private final QSim owner;
        private final Map<Role, List<Object>> identities;
        private final List<RoleDescription> roles;

        private Snapshot(QSim owner, Map<Role, List<?>> raw) {
            this.owner = owner;
            var retained = new LinkedHashMap<Role, List<Object>>();
            var descriptions = new ArrayList<RoleDescription>();
            var aliases = new IdentityHashMap<Object, Integer>();
            for (var role : raw.entrySet()) {
                var objects = new ArrayList<Object>(role.getValue());
                retained.put(role.getKey(), List.copyOf(objects));
                var entries = new ArrayList<Component>();
                for (int i = 0; i < objects.size(); i++) {
                    Object value = objects.get(i);
                    int group = aliases.computeIfAbsent(value, ignored -> aliases.size() + 1);
                    entries.add(new Component(i, group, label(value)));
                }
                descriptions.add(new RoleDescription(role.getKey(), entries));
            }
            identities = Collections.unmodifiableMap(retained);
            roles = List.copyOf(descriptions);
        }

        public List<RoleDescription> roles() { return roles; }
        public List<Component> components(Role role) {
            return roles.stream().filter(entry -> entry.role() == Objects.requireNonNull(role))
                    .findFirst().orElseThrow().components();
        }
        public boolean activeNetworkEngineIs(QNetsimEngineI expected) {
            Objects.requireNonNull(expected);
            var active = identities.get(Role.ACTIVE_NETWORK_ENGINE);
            return active.size() == 1 && active.getFirst() == expected;
        }
        public long registeredEngineOccurrences(QNetsimEngineI expected) {
            Objects.requireNonNull(expected);
            return identities.get(Role.REGISTERED_ENGINE).stream().filter(value -> value == expected).count();
        }
        public long stepEngineOccurrences(QNetsimEngineI expected) {
            Objects.requireNonNull(expected);
            return identities.get(Role.STEP_ENGINE).stream().filter(value -> value == expected).count();
        }
        public boolean sameComponentsAs(Snapshot other) {
            if (other == null || owner != other.owner || !identities.keySet().equals(other.identities.keySet())) return false;
            for (Role role : identities.keySet()) {
                List<Object> left = identities.get(role), right = other.identities.get(role);
                if (left.size() != right.size()) return false;
                for (int i = 0; i < left.size(); i++) if (left.get(i) != right.get(i)) return false;
            }
            return true;
        }
    }

    public static Snapshot inspect(QSim qsim) {
        Objects.requireNonNull(qsim);
        return new Snapshot(qsim, qsim.runtimeComponentsForInspection());
    }

    /**
     * True only on the thread currently executing this QSim's native
     * beforePreparation callback, after default handlers are appended but before
     * event processing, agent creation or engine preparation begins. This scope
     * marker is not a registration lock or a seal of arbitrary event producers.
     */
    public static boolean isAtPreparationStart(QSim qsim) {
        return Objects.requireNonNull(qsim).isAtPreparationStartForInspection();
    }

    /**
     * True only on the thread currently executing this QSim's native
     * afterPrepared callback, after all engine beforeMobsim calls return and
     * before Initialized listeners and initial agent arrangement. A direct
     * invocation outside that synchronous scope does not obtain this evidence.
     */
    public static boolean isAtPreparationCheck(QSim qsim) {
        return Objects.requireNonNull(qsim).isAtPreparationCheckForInspection();
    }

    /** True only while the actual QSim owner invokes its native Before listeners. */
    public static boolean isAtBeforeSimStep(QSim qsim) {
        return Objects.requireNonNull(qsim).isAtBeforeSimStepForInspection();
    }

    /** Successful existing native event-step drain; later After emissions are not covered. */
    public static boolean isAtEventsProcessed(QSim qsim) {
        return Objects.requireNonNull(qsim).isAtEventsProcessedForInspection();
    }

    /** Successful native finish callback, not a terminal derived-event completeness certificate. */
    public static boolean isAtEventsFinished(QSim qsim) {
        return Objects.requireNonNull(qsim).isAtEventsFinishedForInspection();
    }

    /** Constant-time identity check; still neither registration history nor sealing. */
    public static boolean activeNetworkEngineIs(QSim qsim, QNetsimEngineI expected) {
        return Objects.requireNonNull(qsim).activeNetworkEngineIsForInspection(Objects.requireNonNull(expected));
    }

    /** Point-in-time native registration/step multiplicity; not a registration seal. */
    public static boolean hasSingleNetworkEngineRegistration(QSim qsim, QNetsimEngineI expected) {
        return Objects.requireNonNull(qsim).singleNetworkEngineRegistrationForInspection(Objects.requireNonNull(expected));
    }

    static List<?> present(Object value) { return value == null ? List.of() : List.of(value); }

    private static String label(Object value) {
        if (value instanceof Class<?> registrationType) return "registrationType:" + classLabel(registrationType);
        return classLabel(value.getClass());
    }

    private static String classLabel(Class<?> type) {
        if (!type.isHidden()) return type.getName();
        String host = type.getNestHost().getName();
        int suffix = host.indexOf('/');
        return "hidden:" + (suffix < 0 ? host : host.substring(0, suffix));
    }
}
