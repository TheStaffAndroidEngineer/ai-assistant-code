"""Clarify: local retrieval, deterministic timed output and consent-bound tools."""

import re
import json
import uuid
from base import BaseService, Problem, fields, integer, text, run

DOCS = [
    {
        "id": "studio-access",
        "title": "Studio access",
        "text": "The studio opens at 09:00. Use the side entrance for step-free access. The north entrance closes at 18:00.",
    },
    {
        "id": "materials",
        "title": "Shared materials",
        "text": "Pencils, paper and reusable aprons are stored in the green cabinet. Return cleaned tools before leaving.",
    },
    {
        "id": "bookings",
        "title": "Session bookings",
        "text": "Reserve a workbench before attending a workshop. Cancel by the previous evening so another visitor can use the place.",
    },
]


def words(value):
    return set(re.findall(r"[a-z0-9]+", value.lower())) - {
        "the",
        "a",
        "is",
        "at",
        "and",
        "i",
        "to",
        "of",
        "what",
        "how",
        "can",
        "does",
        "for",
    }


class Service(BaseService):
    KIND = "clarify"

    def seed(self):
        return {"generation": 0, "run": None, "notes": []}

    def display(self, run, now):
        if run["status"] == "RUNNING":
            count = min(
                len(run["answer"]), max(len(run["visible"]), max(0, (now - run["startedAt"]) // 40))
            )
            run["visible"] = run["answer"][:count]
            if count == len(run["answer"]):
                run["status"] = "COMPLETE"
        result = dict(run)
        result.pop("answer", None)
        return result

    def route(self, method, path, body):
        if method == "GET" and path == "/view":
            with self.transaction() as db:
                controls = self.controls(db)
                if controls.get("unavailable"):
                    raise Problem("Fixture unavailable", 503)
                now = self.now(controls)
                state = json.loads(db.execute("SELECT value FROM state WHERE id=1").fetchone()[0])
                data = self.view(state, now)
                # Reading acknowledges visible progress durably; a clock reset cannot retract it.
                from base import canonical

                db.execute("UPDATE state SET value=? WHERE id=1", (canonical(state),))
                return 200, {"kind": self.KIND, "observedAt": now, "data": data}, False
        return super().route(method, path, body)

    def view(self, state, now):
        return {
            "generation": state["generation"],
            "run": self.display(state["run"], now) if state["run"] else None,
            "notes": state["notes"],
            "documents": DOCS,
        }

    def apply(self, state, action, args, now):
        if action == "ask":
            fields(args, {"question"})
            question = text(args["question"], 300)
            tokens = words(question)
            ranked = sorted(
                DOCS, key=lambda d: (-len(tokens & words(d["text"] + " " + d["title"])), d["id"])
            )
            doc = (
                ranked[0] if tokens & words(ranked[0]["text"] + " " + ranked[0]["title"]) else None
            )
            answer = (
                doc["text"]
                if doc
                else "The local handbook does not contain a matching passage. Ask the studio team rather than infer a policy."
            )
            state["generation"] += 1
            state["run"] = {
                "id": str(uuid.uuid4()),
                "generation": state["generation"],
                "question": question,
                "status": "RUNNING",
                "startedAt": now,
                "answer": answer,
                "visible": "",
                "citation": doc,
                "proposalId": str(uuid.uuid4()) if doc else None,
                "saved": False,
            }
            return {"accepted": True, "runId": state["run"]["id"]}
        if action == "new":
            fields(args, set())
            state["generation"] += 1
            state["run"] = None
            return {"accepted": True}
        fields(args, {"runId", "generation"} | ({"proposalId"} if action == "confirm" else set()))
        key = text(args["runId"], 40)
        generation = integer(args["generation"], 1)
        r = state["run"]
        if not r or (r["id"], r["generation"]) != (key, generation):
            return {"accepted": False, "reason": "This response belongs to an older conversation"}
        view = self.display(r, now)
        if action == "cancel":
            if view["status"] != "RUNNING":
                return {"accepted": False, "reason": "This response is no longer running"}
            r["visible"] = view["visible"]
            r["status"] = "CANCELLED"
            r["proposalId"] = None
            return {"accepted": True}
        if action == "confirm":
            proposal = text(args["proposalId"], 40)
            if view["status"] != "COMPLETE" or not r["proposalId"] or r["proposalId"] != proposal:
                return {
                    "accepted": False,
                    "reason": "No current completed proposal matches this consent",
                }
            if not r["saved"]:
                if len(state["notes"]) >= 20:
                    return {"accepted": False, "reason": "Local note limit reached"}
                state["notes"].append(
                    {"id": proposal, "text": r["answer"], "source": r["citation"]["id"]}
                )
                r["saved"] = True
            return {"accepted": True, "noteId": proposal}
        raise Problem("Unknown action")


if __name__ == "__main__":
    run(Service, 8924)
