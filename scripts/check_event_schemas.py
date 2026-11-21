#!/usr/bin/env python3
"""Checks docs/events/*.schema.json against a base git revision (ADR 0023).

A topic is retained for 30 days and replayed, so an event schema must stay compatible both ways: consumers built
against the old schema read new events, and consumers built against the new one read the old events still on the
topic. Allowed without a new topic:

  - a new optional property (consumers are tolerant readers and ignore what they don't know),
  - a new enum value (consumers treat an unknown value as "something else"),
  - annotations: title, description, examples, x-consumers, $comment.

Anything else is breaking: removing or renaming a property, changing a type, a pattern, a format or a length,
requiring a property, dropping an enum value, moving an event to another topic, or deleting a schema. A breaking
change goes to a new freightflow.<aggregate>.v<n+1> topic with new schema files.

Each compatible change to an event raises its schemaVersion by exactly one (a new value in the schemaVersion enum).

It also fails if any schema marks a property as personal data (x-personal-data: true) or names a property the way
personal data is usually named (ADR 0024).

    python3 scripts/check_event_schemas.py --base origin/main
    python3 scripts/check_event_schemas.py --self-test
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys

SCHEMA_DIR = "docs/events"
ANNOTATIONS = {"title", "description", "examples", "x-consumers", "$comment", "x-pseudonymous"}
# Any keyword not handled by a structural rule in compare() must keep its exact value.
PERSONAL_NAME = re.compile(
    r"(?i)^(.*name|.*e-?mail.*|.*phone.*|.*address.*|.*contact.*|.*birth.*|iban|.*passport.*|.*tax-?id.*|"
    r"street|city|postcode|zip(code)?|.*accountnumber)$")
# Property names that end in "name" but are identifiers, not a person's name.
NOT_PERSONAL = {"hostname"}


class Problems:
    def __init__(self):
        self.items = []

    def add(self, path, message):
        self.items.append(f"{path}: {message}")


def types(node):
    value = node.get("type")
    if value is None:
        return None
    return frozenset(value) if isinstance(value, list) else frozenset([value])


def compare(old, new, path, problems):
    """Old and new are schema nodes. Adds a problem for every incompatible difference; returns True if anything
    other than an annotation changed."""
    if not isinstance(old, dict) or not isinstance(new, dict):
        if old != new:
            problems.add(path, f"changed from {json.dumps(old)} to {json.dumps(new)}")
            return True
        return False
    changed = False
    for key in sorted(set(old) | set(new)):
        if key in ANNOTATIONS or (key.startswith("x-") and key not in {"x-topic", "x-producer", "x-personal-data"}):
            continue
        here = f"{path}/{key}"
        if key not in new:
            problems.add(here, "removed")
            changed = True
            continue
        if key not in old:
            if key == "properties":
                for name in new[key]:
                    changed = True
                    if name in new.get("required", []):
                        problems.add(f"{here}/{name}", "new property is required")
                continue
            if key in {"$defs"}:
                changed = True
                continue
            problems.add(here, "new constraint")
            changed = True
            continue
        o, n = old[key], new[key]
        if key == "type":
            if types(old) != types(new):
                problems.add(here, f"type changed from {json.dumps(o)} to {json.dumps(n)}")
                changed = True
        elif key in {"properties", "$defs"}:
            for name in sorted(set(o) | set(n)):
                if name not in n:
                    problems.add(f"{here}/{name}", "removed")
                    changed = True
                elif name not in o:
                    changed = True
                    if key == "properties" and name in new.get("required", []):
                        problems.add(f"{here}/{name}", "new property is required")
                else:
                    changed |= compare(o[name], n[name], f"{here}/{name}", problems)
        elif key == "required":
            if set(o) != set(n):
                added, dropped = set(n) - set(o), set(o) - set(n)
                newly_required = {name for name in added if name in old.get("properties", {})}
                if newly_required:
                    problems.add(here, f"now requires {sorted(newly_required)}")
                if dropped:
                    problems.add(here, f"no longer requires {sorted(dropped)}")
                changed = True
        elif key == "enum":
            dropped = [value for value in o if value not in n]
            if dropped:
                problems.add(here, f"enum values removed: {dropped}")
            if o != n:
                changed = True
        elif key in {"items", "additionalProperties"} and isinstance(o, dict):
            changed |= compare(o, n, here, problems)
        elif key in {"oneOf", "anyOf", "allOf"}:
            if len(o) != len(n):
                problems.add(here, f"{len(o)} alternatives became {len(n)}")
                changed = True
            else:
                for index, (a, b) in enumerate(zip(o, n)):
                    changed |= compare(a, b, f"{here}/{index}", problems)
        elif o != n:
            problems.add(here, f"changed from {json.dumps(o)} to {json.dumps(n)}")
            changed = True
    return changed


def check_version(name, old, new, changed, problems):
    old_versions = old.get("properties", {}).get("schemaVersion", {}).get("enum")
    new_versions = new.get("properties", {}).get("schemaVersion", {}).get("enum")
    if old_versions is None or new_versions is None:
        return
    if changed and max(new_versions) != max(old_versions) + 1:
        problems.add(name, f"changed compatibly, so schemaVersion must gain {max(old_versions) + 1} "
                           f"(enum is {new_versions})")
    if not changed and new_versions != old_versions:
        problems.add(name, "schemaVersion changed but the schema did not")


def personal_data(name, node, path, problems):
    if isinstance(node, dict):
        if node.get("x-personal-data") is True:
            problems.add(f"{name}{path}", "marked x-personal-data: personal data stays out of events (ADR 0024)")
        for prop in node.get("properties", {}):
            if PERSONAL_NAME.match(prop) and prop.lower() not in NOT_PERSONAL:
                problems.add(f"{name}{path}/properties/{prop}",
                             "looks like personal data; events carry ids, look the person up in the owning service")
        for key, value in node.items():
            if key != "examples":
                personal_data(name, value, f"{path}/{key}", problems)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            personal_data(name, value, f"{path}/{index}", problems)


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def base_schemas(base):
    names = git("ls-tree", "--name-only", f"{base}:{SCHEMA_DIR}").split()
    return {name: json.loads(git("show", f"{base}:{SCHEMA_DIR}/{name}"))
            for name in names if name.endswith(".schema.json")}


def current_schemas(root):
    return {path.name: json.loads(path.read_text(encoding="utf-8"))
            for path in sorted((root / SCHEMA_DIR).glob("*.schema.json"))}


def check(old_schemas, new_schemas):
    problems = Problems()
    for name, schema in new_schemas.items():
        personal_data(name, schema, "", problems)
    for name, old in old_schemas.items():
        if name not in new_schemas:
            problems.add(name, "deleted; an event type is retired by a new topic version, never by deleting it")
            continue
        changed = compare(old, new_schemas[name], name, problems)
        check_version(name, old, new_schemas[name], changed, problems)
    return problems.items


def self_test():
    base = {
        "$ref": "envelope.schema.json", "x-topic": "freightflow.hold.v1",
        "properties": {
            "schemaVersion": {"enum": [1]},
            "payload": {"type": "object", "additionalProperties": False, "required": ["holdId", "status"],
                        "properties": {"holdId": {"type": "string"},
                                       "status": {"type": "string", "enum": ["ACTIVE", "CAPTURED"]}}}}}

    def variant(edit, version=None):
        schema = json.loads(json.dumps(base))
        edit(schema["properties"]["payload"])
        if version:
            schema["properties"]["schemaVersion"]["enum"] = version
        return schema

    cases = [
        ("unchanged", base, True),
        ("description only", variant(lambda p: p.update(description="text")), True),
        ("optional property with a version", variant(
            lambda p: p["properties"].update(note={"type": "string"}), [1, 2]), True),
        ("optional property without a version", variant(lambda p: p["properties"].update(note={"type": "string"})),
         False),
        ("required property", variant(lambda p: (p["properties"].update(note={"type": "string"}),
                                                  p["required"].append("note")), [1, 2]), False),
        ("property removed", variant(lambda p: (p["properties"].pop("status"), p["required"].remove("status")),
                                     [1, 2]), False),
        ("type changed", variant(lambda p: p["properties"]["holdId"].update(type="integer"), [1, 2]), False),
        ("nullable now", variant(lambda p: p["properties"]["holdId"].update(type=["string", "null"]), [1, 2]),
         False),
        ("enum value added", variant(lambda p: p["properties"]["status"]["enum"].append("RELEASED"), [1, 2]), True),
        ("enum value removed", variant(lambda p: p["properties"]["status"]["enum"].remove("CAPTURED"), [1, 2]),
         False),
        ("pattern added", variant(lambda p: p["properties"]["holdId"].update(pattern="^h"), [1, 2]), False),
        ("personal data marked", variant(
            lambda p: p["properties"].update(note={"type": "string", "x-personal-data": True}), [1, 2]), False),
        ("personal data by name", variant(lambda p: p["properties"].update(contactEmail={"type": "string"}),
                                          [1, 2]), False),
    ]
    failures = 0
    for label, new, ok in cases:
        problems = check({"x.schema.json": base}, {"x.schema.json": new})
        if (not problems) != ok:
            failures += 1
            print(f"self-test '{label}': expected {'no problems' if ok else 'a problem'}, got {problems}")
    problems = check({"x.schema.json": base}, {})
    if not problems:
        failures += 1
        print("self-test 'deleted': expected a problem")
    print(f"self-test: {len(cases) + 1 - failures} of {len(cases) + 1} cases pass")
    return failures == 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", help="git revision to compare against, e.g. origin/main")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return 0 if self_test() else 1
    root = pathlib.Path(git("rev-parse", "--show-toplevel").strip())
    new_schemas = current_schemas(root)
    try:
        old_schemas = base_schemas(args.base) if args.base else {}
    except subprocess.CalledProcessError:
        # The base has no docs/events yet: everything is new, only the personal-data rule applies.
        old_schemas = {}
    problems = check(old_schemas, new_schemas)
    for problem in problems:
        print(f"::error::{problem}" if "GITHUB_ACTIONS" in os.environ else problem)
    print(f"{len(new_schemas)} schemas checked against {args.base or 'nothing'}: "
          f"{'compatible' if not problems else f'{len(problems)} problem(s)'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
