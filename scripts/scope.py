#!/usr/bin/env python3
"""Decides what a change can reach, so the runner verifies that and nothing else.

Reads changed paths on stdin, one per line, as git diff --name-only prints them, and writes
GitHub output lines:

  java=true|false      whether the Java job has anything to verify
  cpp=true|false       whether the C++ job has anything to verify
  modules=a,b          the Java modules to test, empty meaning the whole reactor

The narrowing rule is deliberately conservative and self-maintaining. Only paths inside a Java
module's own directory narrow the build, because the module graph already knows the blast radius:
Maven's --also-make-dependents adds a rung's lean twin and the benchmarks runner by itself, and a
change to a shared module like the protocol pulls in everything that depends on it, which is the
full build arrived at honestly. Everything the module graph does not carry, the schema, the
corpus, the docs, the scripts, the workflow, the parent pom, rebuilds both languages whole.
matching-gates joins every narrowed list, because it reads files across the whole repository
rather than depending on artifacts, so no diff is outside its remit.

The C++ side is never narrowed below the job: its whole build and test run costs about what one
Java suite costs, so the only question worth asking is whether the diff can reach it at all.

Any event that is not a pull request verifies everything, so the full build stays the law on main
and a hole in this map is caught one merge later rather than never.

Usage: git diff --name-only base...head | scope.py <event-name>
"""

import sys

JAVA = "java/"
CPP = ("cpp/", ".clang-format-version")


def module_of(path):
    """The Java module a path lives in, or None for java-side files outside any module."""
    parts = path.split("/")
    if len(parts) > 2 and parts[1].startswith("matching-"):
        return parts[1]
    return None


def decide(event, paths):
    if event != "pull_request":
        return True, True, ""
    java = False
    cpp = False
    narrowed = set()
    whole_java = False
    for path in paths:
        if path.startswith(JAVA):
            java = True
            module = module_of(path)
            if module is None:
                whole_java = True
            else:
                narrowed.add(module)
        elif path.startswith(CPP):
            cpp = True
        else:
            # Shared ground: the schema, the corpus, the docs, the scripts, the workflow. The
            # module graph does not carry these, so both languages rebuild whole.
            return True, True, ""
    if whole_java:
        return java, cpp, ""
    if narrowed:
        narrowed.add("matching-gates")
        return java, cpp, ",".join(sorted(narrowed))
    return java, cpp, ""


def main():
    event = sys.argv[1] if len(sys.argv) > 1 else ""
    paths = [line.strip() for line in sys.stdin if line.strip()]
    java, cpp, modules = decide(event, paths)
    print(f"java={'true' if java else 'false'}")
    print(f"cpp={'true' if cpp else 'false'}")
    print(f"modules={modules}")


if __name__ == "__main__":
    main()
