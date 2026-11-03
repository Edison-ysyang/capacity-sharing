#!/usr/bin/env python3
"""Read-only conservation and native-event verification for the bundled mini inputs."""
from __future__ import annotations

import csv
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def read_rows(path: Path, delimiter: str = ",") -> list[dict[str, str]]:
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream, delimiter=delimiter))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def verify(output: Path) -> dict:
    iteration = output / "ITERS/it.0"
    summaries = read_rows(iteration / "0.coTransport_summary.csv")
    require(len(summaries) == 1, "Expected exactly one iteration summary")
    summary = summaries[0]
    for key, value in {"iteration": "0", "freight_orders": "1", "completed_orders": "1",
                       "on_time_orders": "1", "freight_capacity_units": "2", "freight_delivered_units": "2",
                       "passenger_boardings": "2", "freight_boardings": "2",
                       "passenger_denied_boardings": "0", "freight_denied_boardings": "0",
                       "passenger_stuck_events": "0", "freight_stuck_events": "0",
                       "unfinished_passenger_rides": "0", "exposure_event_state_valid": "true"}.items():
        require(summary[key] == value, f"Unexpected {key}: {summary[key]}")
    loads = read_rows(iteration / "0.coTransport_load_observations.csv")
    require(bool(loads), "No load observations")
    for row in loads:
        total, passengers, freight, capacity = [int(row[k]) for k in (
            "total_occupancy", "passenger_occupancy", "freight_occupancy", "capacity")]
        require(total == passengers + freight and 0 <= total <= capacity == 4, "Capacity conservation failed")
    orders = read_rows(iteration / "0.coTransport_freight_orders.csv")
    require(len(orders) == 1 and orders[0]["agent_balance_state"] == "completed_conserved",
            "Order identity conservation failed")
    require(orders[0]["units_delivered"] == "2" and orders[0]["completion_state"] == "completed",
            "Both units must reach final freight delivery activity")
    atomic = read_rows(iteration / "0.coTransport_whole_order_boardings.csv")
    require(len(atomic) == 1 and atomic[0]["capacity_units"] == "2", "Missing one whole-order boarding batch")
    events = [el.attrib for _, el in ET.iterparse(output / "output_events.xml", events=("end",))
              if el.tag == "event"]
    expected = {"passenger_1", "passenger_2", "cargo_order", "cargo_order_cap_02"}
    enters = [e for e in events if e.get("type") == "PersonEntersPtVehicle" and e.get("person") in expected]
    leaves = [e for e in events if e.get("type") == "PersonLeavesPtVehicle" and e.get("person") in expected]
    require({e["person"] for e in enters} == expected and len(enters) == 4, "Native boarding event mismatch")
    require({e["person"] for e in leaves} == expected and len(leaves) == 4, "Native alighting event mismatch")
    cargo = [e for e in enters if e["person"].startswith("cargo")]
    require(len({(e["time"], e["vehicle"]) for e in cargo}) == 1, "Cargo order split across boarding opportunities")
    require(all(float(e["time"]) >= 27000 and float(e["time"]) < 30600 for e in cargo), "Outside freight boarding window")
    require(not [e for e in events if e.get("type") == "stuckAndAbort"], "Native stuck event present")
    service = read_rows(output / "passenger_service_stats.csv", ";")
    require(len(service) == 1 and service[0]["completed_passenger_trips"] == "2", "Passenger completion mismatch")
    penalties = [e for e in events if e.get("type") == "personScore" and e.get("person") in expected
                 and "coTransportPassenger" in e.get("kind", "")]
    require(len(penalties) == 4 and all(float(e["amount"]) < 0 for e in penalties),
            "Expected native negative score events for both passengers and both exposure types")
    applied = -sum(float(e["amount"]) for e in penalties)
    require(abs(applied - float(summary["passenger_total_penalty"])) <= 0.001, "Applied score/summary mismatch")
    require(not [e for e in events if e.get("type") == "BoardingDeniedEvent"], "Native denied boarding present")
    return {"status": "PASS", "iterations": [0], "completedPassengerTrips": 2,
            "completedOrders": 1, "deliveredCapacityUnits": 2, "nativeBoardings": len(enters),
            "nativeAlightings": len(leaves), "wholeOrderBatches": len(atomic),
            "nativeDeniedBoardings": 0, "stuckEvents": 0, "capacityConserved": True,
            "scope": "Single synthetic M example; no learning convergence or T/D validation"}


if __name__ == "__main__":
    result = verify(Path(sys.argv[1]))
    print(json.dumps(result, ensure_ascii=False, indent=2))
