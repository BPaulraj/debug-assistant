# Debug Assistant

Records how data flows through your Java applications in QA, shows it as one call tree across all
applications, and turns any recorded call into a JUnit test that re-runs it with exactly the same data.

Nothing in the applications changes: it is a Java agent added to the start command.

| Works with | |
|---|---|
| Java | 8, 11, 17, 21. One jar for all of them |
| Frameworks | Spring Boot 2 (javax) and 3 (jakarta), plain Java, any servlet container |
| Across applications | Trace id is passed on through RestTemplate / RestClient, Feign and Apache HttpClient 4/5 |

## How it helps

1. **The tester** reports a bug together with the `X-Debug-Trace-Id` response header. It's visible in browser
   DevTools → Network, or in Postman.
2. **The developer** opens the report and sees every method the request went through, in every application.
   Each one shows its arguments, return value, timing and the exact place an exception started.
3. **The developer** clicks *Copy replay command* on the suspicious method. That gives a JUnit test with the
   QA inputs and with its dependencies answering what they answered in QA. They debug it locally in the IDE,
   with no QA environment and no manual data setup.

## 1. Build

```
cd debug-agent
mvn package          # -> debug-agent/target/debug-agent-1.0.0.jar
```

## 2. Attach to each application in QA

```
java -javaagent:/opt/debug/debug-agent.jar=packages=com.yourcompany,app=order-service,out=/shared/debug-recordings \
     -jar order-service.jar
```

When you can't edit the start command (Docker, Kubernetes, Tomcat service), use the environment variable instead:

```
JAVA_TOOL_OPTIONS="-javaagent:/opt/debug/debug-agent.jar=packages=com.yourcompany,app=order-service,out=/shared/debug-recordings"
```

For Tomcat or another WAR container, add the same `-javaagent:...` to `CATALINA_OPTS` (or `setenv.sh`).

Point every application at the same `out` folder, such as a shared drive. Their recordings are then merged by
trace id automatically.

### Options

Pass them inline (comma-separated), in a properties file (`config=/opt/debug/agent.properties`), or as
`-Ddebugagent.<name>=...`. Separate list values with `;`.

| Option | Default | Meaning |
|---|---|---|
| `packages` | *(required)* | Your code, e.g. `com.acme.orders;com.acme.common` |
| `app` | jar name | Name shown in the report |
| `out` | `./debug-recordings` | Where recordings are written |
| `exclude` | | Class-name prefixes to skip, e.g. `com.acme.config;com.acme.generated` |
| `mask` | password, secret, token, cardNumber, cvv, ssn, … | Field / parameter / map-key names whose values are replaced by `****` |
| `maxDepth` | 6 | How deep object graphs are captured |
| `maxString` / `maxCollection` | 2000 / 100 | Size caps for strings and lists/maps |
| `maxCalls` | 5000 | Calls kept per request (protects against huge loops) |
| `captureArgsAfter` | true | Also records an argument again on exit when the method changed it |
| `skipAccessors` | true | Ignores getters, setters and record accessors |
| `enabled` | true | `-Ddebugagent.enabled=false` turns it off without removing the flag |
| `verbose` | false | Logs every instrumented class |

## 3. Look at the flow

```
java -jar debug-agent.jar list   /shared/debug-recordings          # one line per request
java -jar debug-agent.jar report /shared/debug-recordings --out report.html
```

`report.html` is a single file that works offline, so you can attach it to a bug ticket. Open
`report.html#<traceId>` to jump straight to one request. Search covers ids, URLs, class and method names, and
the recorded data itself.

## 4. Reproduce locally

```
java -jar debug-agent.jar replay <recording.json> <callId> --project path/to/order-service
```

Build the module first (`mvn compile`). The generator reads the compiled class under test to work out which
of its fields are dependencies:

- **Mocked:** dependencies. Each answers the recorded calls in order (return value or exception).
- **Run for real:** data objects, private helpers and other calls on the same object.
- **Fixtures:** JSON files in `src/test/resources/debug-replay/<TestName>/` for inputs, mock answers and the
  expected result. Edit them to try variations of the scenario.
- **Assertions:** the test checks the result, the exception or the changed arguments against what QA saw.
  Values the recorder cut short (depth/size limits) or masked are not compared.

Test dependencies are JUnit 5, Mockito (`mockito-junit-jupiter`) and Jackson. `spring-boot-starter-test`
together with `spring-boot-starter-web` provides all of them.

Typical bug-fix loop: replay the method, fix the code, the test now fails and shows the new value, update
`expected-result.json` to the correct value. You end up with a regression test built from real QA data.

## Demo (in `demo/`)

| App | Stack | Shows |
|---|---|---|
| `order-service` (port 8081) | Spring Boot 3, Java 17, jakarta | Calls inventory over HTTP. Planted bug: bulk discount (`PricingService`) |
| `inventory-service` (port 8082) | Spring Boot 2.7, Java 8, javax | Fails with `OutOfStockException` when stock is short |
| `plain-java-app` | Plain Java 8 | Batch job with a JDK dynamic proxy; fails on an unknown employee |

```
cd demo
java -javaagent:../debug-agent/target/debug-agent-1.0.0.jar=packages=com.demo.inventory,app=inventory-service,out=rec-qa -jar inventory-service/target/inventory-service-1.0.0.jar
java -javaagent:../debug-agent/target/debug-agent-1.0.0.jar=packages=com.demo.order,app=order-service,out=rec-qa -jar order-service/target/order-service-1.0.0.jar

curl -i -X POST localhost:8081/orders -H "Content-Type: application/json" \
     -d "{\"customerId\":\"C-2\",\"lines\":[{\"sku\":\"BOOK-1\",\"quantity\":10}]}"
# 10 books at 12.50 -> total 12.50 (should be 112.50). The report shows PricingService.lineTotal(12.50, 10) -> 12.50
```

## Good to know

- **QA only.** Recording every argument costs CPU and disk and stores real data. Don't enable it in production
  without sampling or tighter `packages` and `exclude` settings.
- **Retention.** Recordings are never deleted by the agent. Clean `out/traces` periodically (e.g. a nightly
  job that deletes folders older than 7 days).
- **Safety.** All recording code is wrapped so a recorder failure can never break the application. Classes
  whose class loader can't see the agent are skipped.
- **Thread hops.** Work moved to another thread (`@Async`, executors, `CompletableFuture`, WebFlux) appears as
  a separate trace with its own id.
- **Messaging.** Kafka, JMS and RabbitMQ consumers are recorded, but each message starts a new trace id.
  Passing the id through message headers isn't built in yet.
- **OpenTelemetry.** If callers send a W3C `traceparent` header, its trace id is used. Recordings then share
  ids with Jaeger, Tempo or Zipkin traces.
- **Replay limits.** A class without a usable constructor for Jackson may need its fixture adjusted. That means
  no no-arg constructor and not compiled with `-parameters`.
