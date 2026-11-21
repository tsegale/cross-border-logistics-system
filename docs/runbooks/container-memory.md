# Container memory

Alerts: ContainerMemoryNearLimit

**What it means.** A container has used more than 90 % of its memory limit for 10 minutes (ADR 0029). Next is an OOM
kill.

**Check.** [Runtime](http://127.0.0.1:3000/d/runtime): which container; for a JVM, heap against non-heap, and whether heap falls after GC
(a high but flat line is the JVM keeping what it was given; a line that only climbs is a leak).

**Fix.** A JVM that sits high but stable: fine, its heap is sized from the limit. A climbing one: take a heap dump
(`jcmd` from a JDK image on the same network) before it dies, and restart it. A database: its cache, normal. Raise a
limit only with a new measurement (ADR 0029). **Escalate** to the service's owner for a leak.
