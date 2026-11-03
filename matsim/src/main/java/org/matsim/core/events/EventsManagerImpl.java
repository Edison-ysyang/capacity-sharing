/* *********************************************************************** *
 * project: org.matsim.*
 * EventsManagerImpl.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007, 2009 by the members listed in the COPYING,  *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.core.events;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.AgentWaitingForPtEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.utils.misc.Counter;

/**
 * EventHandling
 * <ol>
 * <li>Create a new class MyEventClass extends Event</li>
 * <li>Create a new interface MyEventHandlerI extends EventHandler</li>
 * <li>add method public void handleEvent(MyEvent event) to it</li>
 * <li>ready to go, just implement the interface somewhere and add a
 * HandlerObject with a call to <code>Events.addHandler(HandlerObject)</code></li>
 * <li>(optional) add an appropriate line in callHandlerFast() for speeding
 * up execution!</li>
 * </ol>
 *
 * @author dstrippgen
 * @author mrieser
 */
public final class EventsManagerImpl implements EventsManager {

	private static final Logger log = LogManager.getLogger(EventsManagerImpl.class);

	static private class HandlerData {

		protected Class<? extends Event> eventClass;
		protected List<EventHandler> handlerList = new CopyOnWriteArrayList<>();
		protected Method method;

		protected HandlerData(final Class<? extends Event> eventClass, final Method method) {
			this.eventClass = eventClass;
			this.method = method;
		}

		protected void removeHandler(final EventHandler handler) {
			this.handlerList.remove(handler);
		}
	}

	static private class HandlerInfo {
		protected final Class<? extends Event> eventClass;
		protected final EventHandler eventHandler;
		protected final Method method;

		protected HandlerInfo(final Class<? extends Event> eventClass, final EventHandler eventHandler,
				final Method method) {
			this.eventClass = eventClass;
			this.eventHandler = eventHandler;
			this.method = method;
		}
	}

	private final List<HandlerData> handlerData = new ArrayList<>();

	private final Map<Class<? extends Event>, HandlerInfo[]> cacheHandlers = new ConcurrentHashMap<>(15);

	private final Counter counter = new Counter("EventsManagerImpl; event #");

	/**
	 * Describes the actual handler groups and the dispatch order for the requested
	 * event classes without dispatching an event, populating the dispatch cache,
	 * invoking a handler, or changing registration/reset behaviour.
	 *
	 * <p>Call only while registration is quiescent (or under external exclusion of
	 * registration changes). The existing add/remove operations do not participate
	 * in an inspection lock, so this method does not promise an atomic snapshot
	 * across concurrent registration changes. Event processing may continue only
	 * insofar as handlers do not concurrently change registrations. This is a
	 * point-in-time inspection, not a registration seal, a mutation history, or
	 * certification of all event producers or their threads.</p>
	 *
	 * <p>All indices are zero-based. Groups retain their original creation order,
	 * including groups emptied by removal. Dispatch descriptions follow the same
	 * exact-class, superclass, through-Event order as {@code getHandlersForClass};
	 * repeated requested classes and repeated handler registrations are retained.
	 * A group's method is the actual stored method used by the native dispatcher,
	 * not a newly resolved method on each handler implementation.</p>
	 *
	 * <p>Public descriptions contain values only. Identity-group numbers denote
	 * aliases within this snapshot and are assigned at first encounter in group /
	 * registration order; they are not global object IDs. Class labels suppress
	 * hidden-class VM addresses and are deliberately not unique identities. Use
	 * {@link RuntimeInspectionSnapshot#sameRegistrationsAs(RuntimeInspectionSnapshot)}
	 * on the assembly side for exact private-reference comparison. Description
	 * equality alone must not certify the same manager or handler instances.</p>
	 */
	public RuntimeInspectionSnapshot inspectRuntimeRegistrations(final List<Class<? extends Event>> eventClasses) {
		List<Class<? extends Event>> requested = List.copyOf(Objects.requireNonNull(eventClasses, "eventClasses"));
		for (Class<?> requestedClass : requested) {
			if (!Event.class.isAssignableFrom(requestedClass)) {
				throw new IllegalArgumentException("Inspection requires Event classes");
			}
		}

		List<CapturedHandlerGroup> captured = new ArrayList<>();
		List<HandlerGroupDescription> groups = new ArrayList<>();
		IdentityHashMap<EventHandler, Integer> identityGroups = new IdentityHashMap<>();
		for (HandlerData data : List.copyOf(this.handlerData)) {
			List<EventHandler> handlers = List.copyOf(data.handlerList);
			captured.add(new CapturedHandlerGroup(data, data.eventClass, data.method, handlers));
			List<HandlerRegistrationDescription> registrations = new ArrayList<>();
			for (EventHandler handler : handlers) {
				Integer identityGroup = identityGroups.get(handler);
				if (identityGroup == null) {
					identityGroup = identityGroups.size();
					identityGroups.put(handler, identityGroup);
				}
				registrations.add(new HandlerRegistrationDescription(registrations.size(),
						stableClassLabel(handler.getClass()), identityGroup));
			}
			groups.add(new HandlerGroupDescription(groups.size(), stableClassLabel(data.eventClass),
					describeMethod(data.method), registrations));
		}

		List<EventDispatchDescription> dispatches = new ArrayList<>();
		for (Class<? extends Event> requestedClass : requested) {
			List<DispatchRegistrationDescription> dispatch = new ArrayList<>();
			Class<?> eventClass = requestedClass;
			do {
				// Match findHandler's first group for this exact Class identity. Do not
				// consult getHandlersForClass, which would mutate the dispatch cache.
				for (int groupIndex = 0; groupIndex < captured.size(); groupIndex++) {
					if (captured.get(groupIndex).eventClass != eventClass) continue;
					HandlerGroupDescription group = groups.get(groupIndex);
					for (HandlerRegistrationDescription registration : group.handlers()) {
						dispatch.add(new DispatchRegistrationDescription(dispatch.size(), group.groupIndex(),
								registration.registrationPosition(), group.eventClass(), registration.handlerClass(),
								group.method(), registration.handlerIdentityGroup()));
					}
					break;
				}
				eventClass = eventClass.getSuperclass();
			} while (eventClass != null && Event.class.isAssignableFrom(eventClass));
			dispatches.add(new EventDispatchDescription(stableClassLabel(requestedClass), dispatch));
		}
		return new RuntimeInspectionSnapshot(this, captured, groups, dispatches);
	}

	/** Immutable method metadata; no reflective Method or handler object is exposed. */
	public record HandlerMethodDescription(String declaringClass, String methodName,
			List<String> parameterTypes, String returnType) {
		public HandlerMethodDescription { parameterTypes = List.copyOf(parameterTypes); }
	}

	/** One occurrence, not one distinct handler: duplicate registrations stay visible. */
	public record HandlerRegistrationDescription(int registrationPosition, String handlerClass, int handlerIdentityGroup) { }

	public record HandlerGroupDescription(int groupIndex, String eventClass, HandlerMethodDescription method,
			List<HandlerRegistrationDescription> handlers) {
		public HandlerGroupDescription { handlers = List.copyOf(handlers); }
	}

	public record DispatchRegistrationDescription(int dispatchPosition, int groupIndex, int registrationPosition,
			String registeredEventClass, String handlerClass, HandlerMethodDescription method, int handlerIdentityGroup) { }

	public record EventDispatchDescription(String eventClass, List<DispatchRegistrationDescription> handlers) {
		public EventDispatchDescription { handlers = List.copyOf(handlers); }
	}

	/**
	 * Detached descriptions plus private identity references. Retaining a snapshot
	 * retains its manager / handlers; it is an assembly-side audit value, not a
	 * serializable producer certificate. No handler methods are called when comparing.
	 */
	public static final class RuntimeInspectionSnapshot {
		private final EventsManagerImpl source;
		private final List<CapturedHandlerGroup> identities;
		private final List<HandlerGroupDescription> registeredGroups;
		private final List<EventDispatchDescription> dispatches;

		private RuntimeInspectionSnapshot(EventsManagerImpl source, List<CapturedHandlerGroup> identities,
				List<HandlerGroupDescription> registeredGroups, List<EventDispatchDescription> dispatches) {
			this.source = source;
			this.identities = List.copyOf(identities);
			this.registeredGroups = List.copyOf(registeredGroups);
			this.dispatches = List.copyOf(dispatches);
		}

		public List<HandlerGroupDescription> registeredGroups() { return registeredGroups; }
		public List<EventDispatchDescription> dispatches() { return dispatches; }

		/**
		 * True only for this same manager and the same complete group / method /
		 * handler-reference sequence. The requested dispatch-class list is irrelevant
		 * to registration equality. Equal snapshots cannot exclude an intervening
		 * removal and re-addition that restored the same registrations.
		 */
		public boolean sameRegistrationsAs(RuntimeInspectionSnapshot other) {
			if (other == null || source != other.source || identities.size() != other.identities.size()) return false;
			for (int groupIndex = 0; groupIndex < identities.size(); groupIndex++) {
				CapturedHandlerGroup left = identities.get(groupIndex);
				CapturedHandlerGroup right = other.identities.get(groupIndex);
				if (left.groupIdentity != right.groupIdentity || left.eventClass != right.eventClass
						|| !left.method.equals(right.method) || left.handlers.size() != right.handlers.size()) return false;
				for (int position = 0; position < left.handlers.size(); position++) {
					if (left.handlers.get(position) != right.handlers.get(position)) return false;
				}
			}
			return true;
		}
	}

	private static final class CapturedHandlerGroup {
		private final HandlerData groupIdentity;
		private final Class<? extends Event> eventClass;
		private final Method method;
		private final List<EventHandler> handlers;
		private CapturedHandlerGroup(HandlerData groupIdentity, Class<? extends Event> eventClass,
				Method method, List<EventHandler> handlers) {
			this.groupIdentity = groupIdentity; this.eventClass = eventClass; this.method = method; this.handlers = handlers;
		}
	}

	private static HandlerMethodDescription describeMethod(Method method) {
		List<String> parameters = new ArrayList<>();
		for (Class<?> parameter : method.getParameterTypes()) parameters.add(stableClassLabel(parameter));
		return new HandlerMethodDescription(stableClassLabel(method.getDeclaringClass()), method.getName(),
				parameters, stableClassLabel(method.getReturnType()));
	}

	private static String stableClassLabel(Class<?> type) {
		if (type.isArray()) return stableClassLabel(type.getComponentType()) + "[]";
		if (!type.isHidden()) return type.getName();
		Class<?> nestHost = type.getNestHost();
		if (nestHost != type) return "hidden:" + stableClassLabel(nestHost);
		// A hidden class created without NESTMATE can be its own nest host.
		// Strip its VM suffix rather than recursing or publishing a /0x address.
		String name = type.getName();
		int suffix = name.indexOf('/');
		return "hidden:" + (suffix < 0 ? name : name.substring(0, suffix));
	}

	private HandlerData findHandler(final Class<? extends Event> evklass) {
		for (HandlerData handler : this.handlerData) {
			if (handler.eventClass == evklass) {
				return handler;
			}
		}
		return null;
	}

	@Override
	public void processEvent(final Event event) {
		counter.incCounter();
		for (HandlerInfo info : getHandlersForClass( event.getClass() )) {
			synchronized(info.eventHandler) {
				if (callHandlerFast(info.eventClass, event, info.eventHandler )) {
					continue;
				}
				try {
					info.method.invoke(info.eventHandler, event );
				} catch (IllegalArgumentException | IllegalAccessException e) {
					throw new RuntimeException("problem invoking EventHandler " + info.eventHandler.getClass().getCanonicalName() + " for event-class " + info.eventClass.getCanonicalName(), e);
				} catch (InvocationTargetException e) {
					throw new RuntimeException("problem invoking EventHandler " + info.eventHandler.getClass().getCanonicalName() + " for event-class " + info.eventClass.getCanonicalName(), e.getCause());
				}
			}
		}
	}

	@Override
	public void addHandler (final EventHandler handler) {
		Set<Class<?>> addedHandlers = new HashSet<>();
		Class<?> test = handler.getClass();
		if (log.getLevel().isMoreSpecificThan(Level.DEBUG)) {
			log.info("=== Logging of event-handlers skipped ===");
			log.info("To enable debug output, set an environment variable i.e. export LOG_LEVEL='debug', "
				+ "or use Configurator...Level.DEBUG) in your run class.");
		}
		log.debug("adding Event-Handler: " + test.getName());
		do {
			for (Class<?> theInterface : test.getInterfaces()) {
				if (EventHandler.class.isAssignableFrom(theInterface)) {
					Class<? extends EventHandler> eventHandlerInterface = (Class<? extends EventHandler>)theInterface;
					if (!addedHandlers.contains(theInterface)) {
						log.debug("  " + theInterface.getName());
						addHandlerInterfaces(handler, eventHandlerInterface);
						addedHandlers.add(theInterface);
					}
				}
			}
			test = test.getSuperclass();
		} while ((EventHandler.class.isAssignableFrom(test)));

		this.cacheHandlers.clear();
		log.debug("");
	}

	@Override
	public void removeHandler(final EventHandler handler) {
		log.info("removing Event-Handler: " + handler.getClass().getName());
		for (HandlerData handlerList : this.handlerData) {
			handlerList.removeHandler(handler);
		}
		this.cacheHandlers.clear();
	}

	@Override
	public void resetHandlers(final int iteration) {
		log.info("resetting Event-Handlers");
		this.counter.reset();
		Set<EventHandler> resetHandlers = new HashSet<EventHandler>();
		for (HandlerData handlerdata : this.handlerData) {
			for (EventHandler handler : handlerdata.handlerList) {
				if (!resetHandlers.contains(handler)) {
					log.info("  " + handler.getClass().getName());
					handler.reset(iteration);
					resetHandlers.add(handler);
				}
			}
		}
	}

	@Override
	public void initProcessing() {
		// nothing to do in this implementation
	}

	@Override
	public void afterSimStep(double time) {
		// nothing to do in this implementation
	}

	@Override
	public void finishProcessing() {
		// nothing to do in this implementation
	}

	private void addHandlerInterfaces(final EventHandler handler, final Class<? extends EventHandler> handlerClass) {
		Method[] classmethods = handlerClass.getMethods();
		for (Method method : classmethods) {
			if (method.getName().equals("handleEvent")) {
				Class<?>[] params = method.getParameterTypes();
				if (params.length == 1) {
					Class<? extends Event> eventClass = params[0].asSubclass(Event.class);
					log.info("    > " + eventClass.getName());
					HandlerData dat = findHandler(eventClass);
					if (dat == null) {
						dat = new HandlerData(eventClass, method);
						this.handlerData.add(dat);
					}
					dat.handlerList.add(handler);
				}
			}
		}
	}

	private HandlerInfo[] getHandlersForClass(final Class<? extends Event> eventClass) {
		HandlerInfo[] cache = this.cacheHandlers.get(eventClass);
		if (cache != null) {
			return cache;
		}

		ArrayList<HandlerInfo> info = new ArrayList<>();
		// search in class hierarchy
		Class<?> klass = eventClass;
		do {
			Class<? extends Event> eventKlass = (Class<? extends Event>)klass;
			HandlerData dat = findHandler(eventKlass);
			if (dat != null) {
				for (EventHandler handler : dat.handlerList) {
					info.add(new HandlerInfo(eventKlass, handler, dat.method));
				}
			}
			klass = klass.getSuperclass();
		} while (Event.class.isAssignableFrom(klass));

		cache = info.toArray(new HandlerInfo[0]);
		this.cacheHandlers.put(eventClass, cache);
		return cache;
	}

	// this method is purely for performance reasons and need not be implemented
	private static boolean callHandlerFast(final Class<? extends Event> klass, final Event ev,
			final EventHandler handler) {
		if (klass == LinkLeaveEvent.class) {
			((LinkLeaveEventHandler)handler).handleEvent((LinkLeaveEvent)ev);
			return true;
		} else if (klass == LinkEnterEvent.class) {
			((LinkEnterEventHandler)handler).handleEvent((LinkEnterEvent)ev);
			return true;
		} else if (klass == VehicleEntersTrafficEvent.class) {
			((VehicleEntersTrafficEventHandler) handler).handleEvent((VehicleEntersTrafficEvent) ev);
			return true;
		} else if (klass == VehicleLeavesTrafficEvent.class) {
			((VehicleLeavesTrafficEventHandler) handler).handleEvent((VehicleLeavesTrafficEvent) ev);
			return true;
		} else if (klass == PersonArrivalEvent.class) {
			((PersonArrivalEventHandler)handler).handleEvent((PersonArrivalEvent)ev);
			return true;
		} else if (klass == PersonDepartureEvent.class) {
			((PersonDepartureEventHandler)handler).handleEvent((PersonDepartureEvent)ev);
			return true;
		} else if (klass == ActivityEndEvent.class) {
			((ActivityEndEventHandler)handler).handleEvent((ActivityEndEvent)ev);
			return true;
		} else if (klass == ActivityStartEvent.class) {
			((ActivityStartEventHandler)handler).handleEvent((ActivityStartEvent)ev);
			return true;
		} else if (klass == TransitDriverStartsEvent.class) {
			((TransitDriverStartsEventHandler) handler).handleEvent((TransitDriverStartsEvent) ev);
			return true;
		} else if (klass == PersonStuckEvent.class) {
			((PersonStuckEventHandler)handler).handleEvent((PersonStuckEvent)ev);
			return true;
		} else if (klass == PersonMoneyEvent.class) {
			((PersonMoneyEventHandler)handler).handleEvent((PersonMoneyEvent)ev);
			return true;
		} else if (klass == AgentWaitingForPtEvent.class) {
			((AgentWaitingForPtEventHandler)handler).handleEvent((AgentWaitingForPtEvent)ev);
			return true;
		} else if (klass == PersonEntersVehicleEvent.class) {
			((PersonEntersVehicleEventHandler)handler).handleEvent((PersonEntersVehicleEvent)ev);
			return true;
		} else if (klass == PersonLeavesVehicleEvent.class) {
			((PersonLeavesVehicleEventHandler)handler).handleEvent((PersonLeavesVehicleEvent)ev);
			return true;
		} else if (klass == PersonContinuesInVehicleEvent.class) {
			((PersonContinuesInVehicleEventHandler) handler).handleEvent((PersonContinuesInVehicleEvent) ev);
			return true;
		} else if (klass == VehicleDepartsAtFacilityEvent.class) {
			((VehicleDepartsAtFacilityEventHandler) handler).handleEvent((VehicleDepartsAtFacilityEvent) ev);
			return true;
		} else if (klass == VehicleArrivesAtFacilityEvent.class) {
			((VehicleArrivesAtFacilityEventHandler) handler).handleEvent((VehicleArrivesAtFacilityEvent) ev);
			return true;
		} else if (klass == Event.class) {
			((BasicEventHandler)handler).handleEvent(ev);
			return true;
		}
		return false;
	}

	public void printEventHandlers() {
		log.info("currently registered event-handlers:");
		for (HandlerData handlerType : this.handlerData) {
			log.info("+ " + handlerType.eventClass.getName());
			for (EventHandler handler : handlerType.handlerList) {
				log.info("  - " + handler.getClass().getName());
			}
		}
	}

}
