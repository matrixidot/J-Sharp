# ledger — the J# demo application

A small personal-finance tool in about 1,000 lines of J#. It reads a plain-text ledger and
prints reports, answers queries in its own mini query language, forecasts net worth, splits
bills to the cent and exports JSON.

```
bin/jsharp run examples/ledger/src report   examples/ledger/data/sample.ledger
bin/jsharp run examples/ledger/src query    examples/ledger/data/sample.ledger 'category = food and amount < -50'
bin/jsharp run examples/ledger/src forecast examples/ledger/data/sample.ledger 6
bin/jsharp run examples/ledger/src split    100.00 3
bin/jsharp run examples/ledger/src export   examples/ledger/data/sample.ledger
bin/jsharp run examples/ledger/src check    examples/ledger/data/broken.ledger
```

Or build a self-contained jar and run it with plain Java:
`bin/jsharp build examples/ledger/src --jar ledger.jar --include-runtime`, then
`java -jar ledger.jar report examples/ledger/data/sample.ledger`.

## What it shows

| File | J# features |
|---|---|
| `model/money.jsharp` | a record with a compact constructor, computed properties, default arguments, `Comparable`, an extension method (`total`) |
| `model/model.jsharp` | an enum with constructor parameters, records, init-only properties, nullable returns, LINQ-style sequences |
| `parse/parser.jsharp` | `Result<T, E>`, object initializers, a switch expression with `throw`, `??` with `throw`, exceptions from `java.time` |
| `query/query.jsharp` | a sealed AST, a hand-written lexer and recursive-descent parser, record/property/`not` patterns, `Predicate` composition |
| `report/reports.jsharp` | sealed status types, `groupBy`/`orderBy`/`thenBy`/`take` pipelines, tuples, `async`/`await` with `Task` |
| `report/forecast.jsharp` | tuple keys in `groupBy`, statistics with `aggregate`/`minBy`, generic records |
| `json/json.jsharp` | a JSON model as a sealed hierarchy, guarded patterns, a recursive writer |
| `app.jsharp` | top-level statements and functions, a command dispatcher as a switch with guards, file I/O |

## Ledger format

```
currency USD
account <name> <checking|savings|credit> <opening balance>
budget <category> <monthly limit>
<yyyy-mm-dd> <account> <+/-amount> <category> "<payee>" [#tag ...]
transfer <yyyy-mm-dd> <from> <to> <amount>
```

Categories: housing, food, transport, health, fun, shopping, income, other.

## Tests

`test/*.args` and `test/*.expected` are run by `ExamplesTest` (part of `./gradlew build`):
each case compiles the app, runs it with the given arguments on a fresh JVM with
`-Xverify:all`, and compares the output.
