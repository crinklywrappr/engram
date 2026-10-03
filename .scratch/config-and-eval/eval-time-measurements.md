# Eval-time measurements (ticket 03)

Measurements for the slow-eval diagnosis. This file records the numbers only.
The ranked fixes come later, from the research phase.

## Result

The dominant cost is the `datalevin.core` require. It takes 12.0 s of the 15.3 s
source require graph, which is 78 percent. Every other dependency together is
about 3.0 s. The engram code itself is 0.32 s. So the slow eval is Datalevin
load time, not engram code and not the other libraries.

The AOT uberjar boots to a healthy `/healthz` in about 5 s. AOT removes the
source-compile cost, so the residual 5 s is class loading, most of it Datalevin
and its native and server dependencies.

## Environment

- Host: linux x86_64, 8 cores.
- JDK: OpenJDK 25.0.2.
- Clojure: 1.12.5. Clojure CLI: 1.12.4.1618.
- Datalevin: 1.0.1.
- JVM flags for the require runs: `--add-opens=java.base/java.nio=ALL-UNNAMED`
  and `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`.
- The require numbers are warm. The dependency cache was already populated.

## Measurement 1: JVM and Clojure baseline

Startup with nothing from the project required.

```
bare Clojure, empty deps:      ~0.72 s   (first run 1.84 s, cache-cold)
project classpath, no require: ~0.94 s
```

The project classpath adds about 0.2 s over bare Clojure. This is small.

## Measurement 2: per-namespace require cost

One JVM. Datalevin is required first, so its cost is attributed to it. The
engram row is the delta after every dependency is already loaded, so it is
engram's own code.

```
datalevin.core                    12043.6 ms
integrant.core                      187.6 ms
org.httpkit.server                  126.2 ms
muuntaja.core                       241.3 ms
malli.core                          665.8 ms
reitit.ring                         483.6 ms
reitit.coercion.malli               526.0 ms
reitit.swagger                       20.5 ms
reitit.swagger-ui                     7.1 ms
taoensso.telemere                   568.1 ms
syncopate.core                      148.4 ms
jsonista.core                         0.0 ms
engram.main (own code delta)        316.5 ms
TOTAL require graph               15338.4 ms
```

Requiring `datalevin.core` pulls in 116 namespaces. 76 of them are `datalevin.*`.
The other 40 are third-party.

## Measurement 2b: each namespace from a fresh JVM

Each row is a separate JVM. The require is cold, so it includes every shared
transitive dependency. These numbers are not additive across rows, because the
libraries share dependencies.

```
datalevin.core                12622.4 ms
integrant.core                  567.4 ms
org.httpkit.server              377.9 ms
muuntaja.core                  1030.9 ms
malli.core                     1459.5 ms
reitit.ring                    1247.2 ms
reitit.coercion.malli          3216.4 ms
reitit.swagger                  976.2 ms
reitit.swagger-ui              1614.2 ms
taoensso.telemere              2732.8 ms
syncopate.core                10488.0 ms
jsonista.core                   293.6 ms
engram.main (full app, cold)  13492.4 ms
```

Two rows confirm the diagnosis.

- `syncopate.core` is 10.5 s cold. syncopate depends on Datalevin. In the doseq
  it was 148 ms, because Datalevin was loaded first. So its cost is Datalevin.
- `engram.main` cold is 13.5 s. This is the whole app graph. It is close to
  Datalevin alone at 12.6 s. So Datalevin is about 93 percent of a cold app load.

The web-stack rows share dependencies. malli, reitit-core, and encore load one
time and count against several rows. Loaded together on top of Datalevin, they
add a few seconds, not the sum of the rows.

## Measurement 3: uberjar boot to /healthz

The existing AOT uberjar, `target/engram-1.0.42-standalone.jar`, against a fresh
temp data directory.

```
boot-to-healthz: 5.04 s
healthz status:  200
migration apply: ~20 ms
```

The migration is cheap. The time is JVM start plus class loading plus Datalevin
connection open.

## What Datalevin drags in

Datalevin 1.0.1 carries a server and replication stack that an embedded consumer
does not use at runtime. The require still loads its namespaces. The heavy
transitive dependencies are these.

```
consensus and rpc:  com.alipay.sofa/jraft-core, com.alipay.sofa/bolt,
                    io.netty/netty-all, com.google.guava/guava,
                    com.google.protobuf/protobuf-java, com.lmax/disruptor,
                    io.dropwizard.metrics/metrics-core
interpreter:        org.babashka/sci, babashka/babashka.pods
serialization:      com.taoensso/nippy, com.cognitect/transit-clj,
                    cheshire/cheshire, com.github.luben/zstd-jni
logging:            com.taoensso/timbre
native lmdb:        org.clojars.huahaiy/dtlvnative-*, org.bytedeco/javacpp
data structures:    org.roaringbitmap/RoaringBitmap,
                    org.eclipse.collections/eclipse-collections,
                    me.lemire.integercompression/JavaFastPFOR
other:              io.github.nextjournal/markdown, org.commonmark/*,
                    org.bouncycastle/bcprov-jdk15on, nrepl/bencode,
                    org.clojure/tools.cli
```

The boot log shows `javacpp` loading the native LMDB library and
`datalevin.cpp.UnsafeAccess` calling `sun.misc.Unsafe`.

## For the research phase

Open questions to take into research against primary sources.

- Does Datalevin offer an embedded-only load path that avoids the JRaft, netty,
  guava, and protobuf server stack? Can those transitive dependencies be
  excluded from `deps.edn` for an embedded consumer?
- Are SCI, babashka.pods, nextjournal markdown, and timbre needed for embedded
  use? Each one adds Clojure namespaces at require.
- Is the 15 s cost a development concern only? A persistent REPL loads Datalevin
  one time per JVM. AOT already cuts the wall cost to 5 s.
- Can AppCDS or class-data-sharing cut the 5 s class-load residual in the
  container?
- Datalevin ships `graal-build-time` and `dtlvnative`. Does a GraalVM
  native-image build give a near-instant boot, and what does it cost to set up?
- Does a newer or older Datalevin release load faster for embedded use?

## How to reproduce

```
# per-namespace require breakdown
clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED \
        -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -M -e '(defn t [l s] (let [n (System/nanoTime)] (require s) (printf "%-28s %8.1f ms%n" l (/ (- (System/nanoTime) n) 1e6))))
         (doseq [s (quote [datalevin.core integrant.core org.httpkit.server muuntaja.core malli.core reitit.ring reitit.coercion.malli taoensso.telemere syncopate.core])] (t (name s) s))
         (t "engram.main" (quote engram.main))'

# uberjar boot to healthz
ENGRAM_CONFIG=deploy/engram-config.example.edn ENGRAM_DATA_DIR=/tmp/engram-boot PORT=8080 \
  java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -jar target/engram-*-standalone.jar
```
