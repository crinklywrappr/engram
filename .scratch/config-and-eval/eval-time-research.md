# Eval-time research (ticket 03)

## Summary

The slow source require is Datalevin. It costs about 12.6 s cold, roughly 93
percent of a cold app load. The dev pain and the prod boot are two separate
problems. The dev pain is the one-time source require in a REPL JVM. The prod
boot is the 5 s AOT-uberjar class load. Source reading of Datalevin 1.0.1
confirms that JRaft, netty, bolt, protobuf, guava, and disruptor sit only behind
the server namespaces. The embedded `datalevin.core` require never reaches them.
So excluding the JRaft server stack is safe for engram, and it trims the
classpath. It does not cut the dev require much. The require never loads those classes anyway. The big levers are the JDK 25 AOT cache for prod boot and a
persistent REPL for dev.

## Q1. Datalevin dependency structure

Datalevin 1.0.1 declares its deps in `deps.edn` at the source repo. The relevant
coordinates:

```
com.alipay.sofa/jraft-core   1.4.1  :exclusions [org.rocksdb/rocksdbjni]
com.taoensso/nippy           3.7.0-beta1
com.taoensso/timbre          6.5.0
org.babashka/sci             0.13.53
babashka/babashka.pods       0.2.0
io.github.nextjournal/markdown 0.7.225
nrepl/bencode                1.2.0
org.bouncycastle/bcprov-jdk15on 1.70
org.bouncycastle/bcpkix-jdk15on 1.70
org.clojure/tools.cli        1.4.256
com.github.luben/zstd-jni    1.5.7-11
org.clojars.huahaiy/dtlvnative-linux-arm64 0.18.8
```

Source: https://github.com/juji-io/datalevin/blob/1.0.1/deps.edn. the file lists
`com.alipay.sofa/jraft-core` "1.4.1" with a rocksdb exclusion, plus sci, pods,
markdown, timbre, tools.cli, bencode, bouncycastle, nippy, and zstd.

Note one surprise from the source. The heavy transitive server libs named in the
measurement note are not direct Datalevin deps. The `deps.edn` names jraft-core
only. netty, bolt, guava, protobuf, disruptor, and dropwizard-metrics arrive
transitively under jraft-core. The measurement note listed them because they show
on the resolved classpath.

Is JRaft a hard dependency of the embedded path? No. A clone of tag 1.0.1 and a
grep show the only files that reference jraft:

```
src/java/datalevin/ha/LMDBLogStorage.java
src/java/datalevin/ha/LMDBJRaftServiceFactory.java
src/datalevin/ha/control.clj
src/datalevin/server/ha.clj
src/datalevin/validate.clj   (keyword :sofa-jraft in a raise message, not an import)
```

The `validate.clj` hit is a bare keyword in a validation error string. It is not
a require and not an import. So the real jraft users are the `datalevin.ha.*` and
`datalevin.server.*` namespaces plus the Java `ha/` classes.

Is there a lite or embedded-only artifact? No. A read of `deps.edn` and
`README.md` shows one artifact and no lite coordinate. The README says Datalevin
"can be used as an embedded library... or it can run in a networked" server. The
split is by namespace, not by artifact.

Source: https://github.com/juji-io/datalevin/blob/1.0.1/README.md. describes the
single library with an embedded mode and a server mode.

Is excluding jraft-core safe for an embedded consumer? Yes, per the source. A server start or an HA control plane start loads the jraft classes. engram never
starts a server. So an exclusion in engram's `deps.edn` is safe:

```clojure
datalevin/datalevin {:mvn/version "1.0.1"
                     :exclusions [com.alipay.sofa/jraft-core]}
```

If a stray reference to a jraft class ever loads at runtime, that path is the
server code, which engram does not call. So the exclusion trims netty, bolt,
guava, protobuf, disruptor, and dropwizard-metrics from the classpath as well.

## Q2. What is needed for embedded use

The embedded API is `datalevin.core`. Its require graph is what matters. Reading
the ns forms from the 1.0.1 source:

```
datalevin.core  -> conn, db, storage, built-ins, lmdb, query, kv, search,
                   embedding, llm, vector, entity, bits, ... (all datalevin.*)
conn            -> storage, lmdb, async, remote, validate, interface
remote          -> client, client-op        (raw java.nio, no netty)
client          -> protocol, buffer, datom   (SocketChannel/Selector, no netty)
```

So the embedded require reaches the client code. The client uses raw NIO
`SocketChannel` and `Selector`. It does not import netty, jraft, or bolt.

Source (client transport is plain NIO):
https://github.com/juji-io/datalevin/blob/1.0.1/src/datalevin/client.clj. the
`:import` form lists `java.nio.channels SocketChannel Selector SelectionKey` and
no netty.

Now the specific libraries, traced from the 1.0.1 clone.

SCI and babashka.pods. Required only by `datalevin.main` and
`datalevin.interpret`. Those are the CLI and the babashka-pod entry. Not on the
`datalevin.core` path. Safe to exclude for embedded use.

tools.cli. Required only by `datalevin.main`. Not on the embedded path.

nrepl/bencode. A grep for `bencode` in `src/datalevin/*.clj` returns nothing. It
is a transitive or pod-only artifact, not required by the embedded path.

nextjournal/markdown. Required by `datalevin.idoc`. `idoc` is required by
`datalevin.storage` and `datalevin.built-ins`. Both sit on the embedded path
(core -> built-ins, core -> conn -> storage). So markdown IS needed for
embedded use. commonmark arrives transitively under nextjournal/markdown.

Source (markdown is a real require in idoc):
https://github.com/juji-io/datalevin/blob/1.0.1/src/datalevin/idoc.clj. the ns
`:require` lists `[nextjournal.markdown :as md]`.

Source (markdown pulls commonmark):
https://github.com/nextjournal/markdown/blob/main/deps.edn. lists
`org.commonmark/commonmark` and several commonmark extensions.

timbre. Required by `datalevin.async`. `async` sits on the embedded path
(lmdb -> async, conn -> async). So timbre IS needed for embedded use.

Source (timbre is a real require in async):
https://github.com/juji-io/datalevin/blob/1.0.1/src/datalevin/async.clj. the ns
`:require` lists `[taoensso.timbre :as log]`.

bouncycastle. A grep of `src/datalevin/*.clj` for bouncycastle returns nothing.
It is not directly required by any Clojure namespace. It is a transitive or
server-tls artifact. It is not on the embedded require path in source terms.

Summary of the embedded verdict:

```
SAFE to exclude (not on embedded path):  sci, babashka.pods, tools.cli, bencode,
                                         jraft-core (+ its netty/bolt/guava/
                                         protobuf/disruptor/metrics transitives)
LOAD-BEARING (on embedded path):         nextjournal/markdown (+ commonmark),
                                         timbre, nippy, transit, zstd, javacpp +
                                         dtlvnative, roaringbitmap, eclipse-
                                         collections, JavaFastPFOR
UNCLEAR / transitive only:               bouncycastle (no direct require found)
```

## Q3. When Datalevin added the server and JRaft stack

The CHANGELOG shows the server clustering with Raft arrived in 0.10.16.

```
0.10.16 (2026-05-27): High availability (HA) cluster with Raft consensus based
                      auto roll-over and promotion. Non-HA read replicas.
```

Source: https://github.com/juji-io/datalevin/blob/master/CHANGELOG.md. the
0.10.16 entry introduces Raft consensus and read replicas.

Before that series, the 0.9.x line had the embedded API without jraft. The 0.9.22
`project.clj` does not declare jraft-core.

Source: https://github.com/juji-io/datalevin/blob/0.9.22/project.clj. no
`com.alipay.sofa/jraft-core` in the deps. It carries sci, pods, timbre,
bouncycastle, tools.cli, bencode, nippy, and the native libs.

So a jraft-free embedded Datalevin exists at 0.9.x. Downgrading is one path to
drop the server stack without an exclusion.

API-compatibility risk for engram. This risk is real, and a downgrade is not
free. Between 0.9.x and 1.0.1, the storage format changed:

```
1.0.0 (2026-07-20): storage moved to a DLMDB data format with automatic
                    migration; composite tuples redesigned.
1.0.1 (2026-08-10): schema patching with atomic validation.
```

Source: https://github.com/juji-io/datalevin/blob/master/CHANGELOG.md. the
1.0.0 entry changes the on-disk format and the tuple encoding.

engram uses `d/get-conn`, `d/transact!`, schema, full-text, and syncopate
migrations. Those APIs existed at 0.9.x. But a live 1.0.x database uses the new
DLMDB format. If engram downgrades, do not point the old build at a 1.0.x data
directory without a migration check. Also engram's stats writer relies on a
composite-tuple identity, and 1.0.0 redesigned composite tuples. So a downgrade
needs a full test pass on the stats and migration paths. The safer trim is the
exclusion at 1.0.1, not a version downgrade.

## Q4. Clojure-side startup remedies

AOT compilation. The official compilation page lists faster startup as a reason
to AOT-compile. engram already ships an AOT uberjar. So this lever is spent for
prod.

Source: https://clojure.org/reference/compilation. "speed up application
startup" is a named reason to compile ahead of time.

Direct linking. The flag is `-Dclojure.compiler.direct-linking=true`. It replaces
var indirection with a static call. The page states two startup effects. It makes
many vars unused, so the compiler removes them, which gives "smaller class sizes
and faster startup times."

Source: https://clojure.org/reference/compilation. direct linking "will result
in faster var invocation" and "smaller class sizes and faster startup times."

Direct-linking trade-off. Redefinitions are not seen by directly linked callers.
The page says a `^:dynamic` var is never direct-linked, and `^:redef` opts a var
out. For a REPL where you redefine functions, direct linking hurts the workflow.
So use direct linking for the prod uberjar, not the dev REPL.

Source: https://clojure.org/reference/compilation. "var redefinitions will not
be seen by code that has been compiled with direct linking."

Dev-REPL JVM flags. Two flags cut JIT and GC startup cost.

`-XX:TieredStopAtLevel=1` stops JIT at the C1 tier. It skips the slower C2
compiler. Startup is faster. Peak throughput is lower. For a dev REPL, peak
throughput does not matter, so this is a clean win.

`-Xint` runs interpreter-only. The official java tool page states it disables
compilation to native code. It is the extreme version of the flag above. It cuts
JIT startup cost fully at a large throughput cost.

Source: https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html
`-Xint` "Runs the application in interpreted-only mode." The same page documents
`-XX:TieredStopAtLevel=n` and `-XX:-TieredCompilation` under the tiered options.

Reduced GC. `-XX:+UseSerialGC` picks the simple collector. It has the smallest
startup and heap-setup cost. For a short-lived dev JVM, the serial collector
avoids the parallel or G1 setup work.

Source: https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html
the garbage-collection options section documents `-XX:+UseSerialGC`.

Important scope note. These JIT and GC flags shrink the JVM warmup, not the
Datalevin require. The Datalevin require is class loading and namespace init
work. So these flags help a little in dev, but they do not remove the 12 s
require. A persistent REPL removes it. The require runs one time per JVM.

## Q5. JDK 25 AOT cache (Project Leyden)

engram runs JDK 25. So this is the most important modern lever for the prod boot.

JEP 483 (JDK 24) delivered ahead-of-time class loading and linking. It caches
classes in a loaded and linked state, so a later run skips the read, parse, load,
and link work. The workflow is a training run then a cache create then normal
runs:

```
# record a training run
java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -cp app.jar App
# create the cache
java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -cp app.jar
# production run uses the cache
java -XX:AOTCache=app.aot -cp app.jar App
```

Source: https://openjdk.org/jeps/483. delivered in JDK 24. It reports a Spring
PetClinic startup gain of 42 percent, from 4.486 s to 2.604 s.

JEP 514 (JDK 25) simplified the workflow to one command. The new flag is
`-XX:AOTCacheOutput`:

```
# one-step: training run and cache create together
java -XX:AOTCacheOutput=app.aot -cp app.jar App
# production run uses the cache
java -XX:AOTCache=app.aot -cp app.jar App
```

Source: https://openjdk.org/jeps/514. delivered in JDK 25. The `-XX:AOTCacheOutput`
flag consolidates the two-step create into one. It adds `JDK_AOT_VM_OPTIONS`.

JEP 515 (JDK 25) added method profiling to the AOT cache. The cache now stores
method profiles from the training run. So the JIT starts native compilation of
hot methods right at startup, instead of waiting to collect profiles.

Source: https://openjdk.org/jeps/515. delivered in JDK 25. The AOT cache "now
also stores method profiles."

Baking into Docker. Yes, this fits a Docker image. Run the training pass at image
build time against a throwaway data directory. Write the `.aot` file into the
image. Add `-XX:AOTCache=/app/app.aot` to the runtime command. The training run
loads Datalevin and opens a connection, so the cache captures the Datalevin class
That boot is class loading. So this targets the 5 s prod boot directly.
A word of care. The AOT cache is tied to the exact JDK build and the classpath.
If the base image JDK or a dependency changes, rebuild the cache.

AppCDS fallback (JEP 350, JDK 13). For an older JDK without the Leyden cache, use
Dynamic CDS. It archives loaded classes at exit and reuses them:

```
# record at exit
java -XX:ArchiveClassesAtExit=app.jsa -cp app.jar App
# reuse the archive
java -XX:SharedArchiveFile=app.jsa -cp app.jar App
```

Source: https://openjdk.org/jeps/350. delivered in JDK 13. `-XX:ArchiveClassesAtExit`
creates the archive and `-XX:SharedArchiveFile` uses it. It gives startup and
memory benefits over the default CDS. engram runs JDK 25, so the AOT cache is the
first choice and CDS is the fallback.

## Q6. GraalVM native-image

Datalevin officially supports native-image, and it builds its own binary this
way. The source ships the config.

Datalevin ships native-image metadata in the jar:

```
resources/META-INF/native-image/datalevin/datalevin/native-image.properties
resources/META-INF/native-image/datalevin/datalevin/reachability-metadata.json
```

The `native-image.properties` sets direct linking, native access, and init-time
rules:

```
-J-Dclojure.compiler.direct-linking=true
--enable-native-access=ALL-UNNAMED
--features=clj_easy.graal_build_time.InitClojureClasses
--initialize-at-build-time=me.lemire.integercompression,org.slf4j
--initialize-at-run-time=org.bytedeco.javacpp.presets.javacpp,datalevin.dtlvnative,datalevin.cpp,...
-H:ReachabilityMetadataResources=${.}/reachability-metadata.json
```

Source (native-image config in the 1.0.1 tag):
https://github.com/juji-io/datalevin/blob/1.0.1/resources/META-INF/native-image/datalevin/datalevin/native-image.properties
It sets direct linking, graal-build-time feature, and the init-at-run-time list
for the native LMDB and javacpp classes.

Datalevin's README says the JVM library and the CLI share code, and it points to
the GraalVM native-image manual. So embedded LMDB in a native image is supported.

Source: https://github.com/juji-io/datalevin/blob/1.0.1/doc/install.md. "A native
Datalevin is built by compiling into GraalVM native image," linking
https://www.graalvm.org/reference-manual/native-image/.

Source (GraalVM native-image reference):
https://www.graalvm.org/reference-manual/native-image/. native-image compiles
Java ahead of time into a standalone binary with near-instant startup.

Setup cost and platform notes for the Pi target. Datalevin's release CI builds
the native binary per platform on GraalVM JDK 25. It does not cross-compile. It
runs a native build on each OS and arch runner, including a Linux arm64 runner.

Source (release CI does per-arch native builds on GraalVM 25):
https://github.com/juji-io/datalevin/blob/1.0.1/.github/workflows/release.binaries.yml
It sets up graalvm distribution java-version 25 and runs `compile-native-linux`
on separate arch matrix entries. There is a Linux arm64 release artifact.

So for the Pi, the native image must build on arm64. GraalVM native-image does
not cross-compile across arch by default. Two paths exist. Build on an arm64
runner or an arm64 machine. Or build inside an arm64 container with emulation,
which is slow. The native build itself is heavy. It needs GraalVM, more RAM, and
several minutes per build. It needs the reachability config, which Datalevin
already ships. engram's own reflection needs a check, but engram's stack is
mostly data libraries, so the extra config is small.

Trade-offs. A native image gives a near-instant boot, on the order of tens of
milliseconds. That beats the AOT cache. The cost is build complexity, a slower CI,
per-arch builds, and a larger maintenance surface. The README also warns that a
native image is less efficient than HotSpot for a long-running server. engram is
long-running, so throughput matters. So native-image trades peak throughput for
boot speed.

## Ranked recommendations

The dev pain and the prod boot are two different problems. State which each fix
targets.

1. Persistent dev REPL. Target: dev-REPL time. Expected win: removes the 12 s
   require after the first load. The require runs one time per JVM.
   Effort: near zero, a workflow habit plus editor integration. Risk: none. This
   is the highest payoff for the dev pain. The 12 s is a one-time cost per JVM,
   not per eval.

2. JDK 25 AOT cache in the Docker image. Target: prod boot. Expected win: a large
   cut to the 5 s boot, in line with the JEP 483 numbers near 40 percent, plus
   the JEP 515 warmup gain. Effort: moderate, a training pass at image build and
   two flags at runtime. Risk: low. If the JDK or a dep changes, rebuild the cache. Best payoff-vs-effort for prod boot given JDK 25.

3. Exclude jraft-core in engram's deps.edn. Target: classpath size and prod boot,
   marginally. Expected win: small. It drops netty, bolt, guava, protobuf,
   disruptor, and metrics from the classpath. Source reading confirms these are
   not on the embedded require path, so the require time barely moves. Effort:
   one line. engram never starts a server, so the risk is low. Do this for hygiene,
   not for a big speed win.

4. Direct-linking on the prod uberjar. Target: prod boot. Expected win: small,
   smaller classes and faster startup. Effort: one build flag. Risk: low for a
   built artifact. It blocks redefinition, so do not use it in the dev REPL.

5. Dev-REPL JVM flags. Target: dev-REPL time. Expected win: small, it trims JVM
   warmup, not the require. Use `-XX:TieredStopAtLevel=1` and `-XX:+UseSerialGC`.
   Effort: one alias. Risk: none in dev. Combine with fix 1.

6. AppCDS as a fallback. Target: prod boot. Use only on a JDK without the Leyden
   cache. engram runs JDK 25, so fix 2 supersedes this.

7. GraalVM native-image. Target: prod boot, to near-instant. Expected win: the
   largest boot cut, tens of milliseconds. Effort: high, per-arch arm64 builds,
   GraalVM CI, reflection review. Risk: medium, plus a throughput cost for a
   long-running server. Choose this only if the 5 s boot after fix 2 is still too
   slow, or if you want a tiny image with no JVM.

8. Downgrade Datalevin to 0.9.x. Target: classpath and require, to drop jraft
   without an exclusion. Expected win: small over fix 3, and it loses 1.0.x
   features. 1.0.0 changed the on-disk format and the composite-tuple encoding.
   So the effort is moderate. Risk: high, a storage-format and API regression on
   the stats and migration paths. Not recommended. Prefer the exclusion at 1.0.1.

## Note on trimming the server deps

Source reading confirms the jraft server stack is NOT on the embedded require
path. So excluding it is safe. But it barely helps the require time. The require
never loads those classes anyway. It only shrinks the classpath and the
image. The needed embedded deps are markdown, commonmark, timbre, nippy,
transit, zstd, the native LMDB libs, and the compression and collection libs.
Those you cannot exclude.
