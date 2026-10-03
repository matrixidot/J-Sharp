# A tour of J#

J# is a statically typed language for the JVM with syntax drawn from Java and C#. It compiles
straight to JVM bytecode, runs as fast as hand-written Java (see [BENCHMARKS.md](BENCHMARKS.md)),
and mixes freely with Java code in both directions.

This tour walks through the language by example. Every `jsharp` code block below is a complete
program that the test suite compiles and runs (`DocsTest`), and lines ending in `// prints: ...`
show its output.

- [Getting started](#getting-started)
- [Values, types and strings](#values-types-and-strings)
- [Null safety](#null-safety)
- [Functions](#functions)
- [Control flow](#control-flow)
- [Classes and properties](#classes-and-properties)
- [Records, enums and sealed types](#records-enums-and-sealed-types)
- [Pattern matching](#pattern-matching)
- [Generics](#generics)
- [Lambdas and sequences](#lambdas-and-sequences)
- [Async and await](#async-and-await)
- [Errors and resources](#errors-and-resources)
- [Working with Java](#working-with-java)
- [The tools](#the-tools)

## Getting started

Build the compiler once (`./gradlew installDist`, or just use `bin/jsharp`, which builds on first
use), then run a file:

```jsharp
// hello.jsharp
var name = args.length > 0 ? args[0] : "world";
println($"Hello, {name}!");   // prints: Hello, world!
```

```
$ bin/jsharp run hello.jsharp
Hello, world!
$ bin/jsharp run hello.jsharp J#
Hello, J#!
```

Statements at the top level of one file form the program's entry point (like C#). `args` holds
the command-line arguments. `println`, `print`, `readLine`, `require`, `check`, `error` and
`todo` are always in scope, as are `java.lang` and the J# library (`jsharp.*`). Everything else
is imported as in Java: `import java.util.*;` (the compiler suggests the import when you forget
it).

## Values, types and strings

`var` declares a variable, `val` one that cannot be reassigned. Types are inferred, or written
Java-style before the name.

```jsharp
var count = 3;          // int
val pi = 3.14159;       // double, cannot be reassigned
long big = 3_000_000_000L;
char c = 'J';
boolean ok = count > 2;
count += 1;
println(count);          // prints: 4
println(big / 1000);     // prints: 3000000
println(c);              // prints: J
println(ok);             // prints: true

// Strings: interpolation with optional .NET-style format specifiers.
val item = "coffee";
val price = 3.5;
println($"{item}: {price:F2} x {count} = {price * count:F2}");   // prints: coffee: 3.50 x 4 = 14.00
println($"{255:X} {42:D5} {0.256:P1} {1234567:N0}");             // prints: FF 00042 25.6% 1,234,567

// == compares values (equals), === compares references.
val a = new String("x");
val b = new String("x");
println(a == b);    // prints: true
println(a === b);   // prints: false

// Raw strings keep newlines and quotes; indentation is stripped like Java text blocks.
val json = """
    {"name": "J#"}
    """;
print(json);         // prints: {"name": "J#"}
```

Primitive types are Java's (`int long double boolean char byte short float`). Generic type
arguments may be primitive: `List<int>` means `List<Integer>`. Arithmetic wraps like Java
unless you ask for overflow checks with `checked(...)`.

## Null safety

Types are non-null by default. `T?` allows null, and the compiler tracks null checks.

```jsharp
import java.util.*;

String? find(Map<String, String> m, String key) => m.get(key);

var colors = new HashMap<String, String>();
colors.put("sky", "blue");

String? sky = find(colors, "sky");
String? sea = find(colors, "sea");

println(sky?.length());            // prints: 4
println(sea?.length());            // prints: null
println(sea ?? "unknown");         // prints: unknown

if (sky != null) {
    println(sky.toUpperCase());    // prints: BLUE
}

String? note = null;
note ??= "default";
println(note);                     // prints: default

int len = sky!.length();           // '!' asserts non-null (throws if it is null)
println(len);                      // prints: 4
```

Assigning `null` to a `String`, or calling a method on a `String?` without a check, is a
compile-time error. Types coming from Java are "platform" types: J# trusts nullness
annotations such as JSpecify's `@Nullable` and otherwise lets you choose (pass
`--strict-platform-nullness` to get warnings).

## Functions

Functions can live at the top level of a file, with block or expression (`=>`) bodies. Parameters
can have defaults, and calls can name arguments.

```jsharp
int square(int x) => x * x;

String greet(String name, String greeting = "Hello", boolean shout = false) {
    val text = $"{greeting}, {name}!";
    return shout ? text.toUpperCase() : text;
}

// An extension method: callable as if it were a member of String.
int vowels(this String s) => s.count('a') + s.count('e') + s.count('i') + s.count('o') + s.count('u');

println(square(7));                          // prints: 49
println(greet("Ada"));                       // prints: Hello, Ada!
println(greet("Ada", shout: true));          // prints: HELLO, ADA!
println(greet(greeting: "Hi", name: "Bo"));  // prints: Hi, Bo!
println("education".vowels());               // prints: 5
```

Extension methods are static methods with a `this` parameter. Like C#, members always win over
extensions, and extensions must be in scope: `import my.pkg.*` brings in the top-level
functions and extensions of a package. The standard library's extensions (`jsharp.collections`,
`jsharp.text`) are always available.

## Control flow

`if`, `while`, `do`, `for` and `switch` work as in Java. `foreach` iterates, `if` and `switch`
can be expressions, and labeled `break`/`continue` work.

```jsharp
import java.util.List;

var total = 0;
foreach (var n in List.of(1, 2, 3, 4, 5)) {
    if (n % 2 == 0) continue;
    total += n;
}
println(total);                                    // prints: 9

val parity = if (total % 2 == 0) "even" else "odd";
println(parity);                                   // prints: odd

String size(int n) => n switch {
    < 0 => "negative",
    0 => "zero",
    > 0 and < 10 => "small",
    _ => "large",
};
println(size(-1) + " " + size(0) + " " + size(7) + " " + size(70));   // prints: negative zero small large

// Ranges and indices from the end.
int[] xs = { 10, 20, 30, 40, 50 };
println(xs[^1]);                                  // prints: 50
foreach (var i in range(0, 3)) print(i);          // prints: 012
println();

// Classic switch statements remain for Java familiarity; sections never fall through.
String day(int d) {
    switch (d) {
        case 6:
        case 7:
            return "weekend";
        default:
            return "weekday";
    }
}
println(day(6));                                  // prints: weekend
```

## Classes and properties

Classes are `final` unless declared `base`, and overriding needs `override`. Inheritance uses
`:` with the base class first, then interfaces. Properties replace getter and setter
boilerplate, and Java sees them as `getX()`/`setX()`.

```jsharp
public base class Shape {
    public String name { get; }
    public Shape(String name) { this.name = name; }
    public base double area() => 0;
    public override String toString() => $"{name} with area {area():F2}";
}

public class Rect : Shape {
    public double width { get; set; }
    public double height { get; set; }
    public Rect(double w, double h) {
        super("rect");
        width = w;
        height = h;
    }
    public override double area() => width * height;
    public boolean isSquare => width == height;      // computed, read-only
}

public class Account {
    public required String owner { get; init; }    // must be set when constructed
    public String currency { get; init; } = "USD";
    public long cents { get; private set; }
    public void deposit(long amount) {
        require(amount > 0, "deposit must be positive");
        cents += amount;
    }
}

var r = new Rect(2, 3);
println(r);                         // prints: rect with area 6.00
r.width = 3;
println(r.isSquare);                // prints: true

var acct = new Account { owner = "Ada" };   // object initializer
acct.deposit(500);
println($"{acct.owner} {acct.cents} {acct.currency}");   // prints: Ada 500 USD
```

Interfaces may have default, static and private methods. Nested, local and anonymous classes
work as in Java.

## Records, enums and sealed types

```jsharp
public record Point(int x, int y) {
    init {                                 // runs before the fields are set: validate or normalize
        require(x >= 0 && y >= 0, "coordinates must be non-negative");
    }
    public double length() => Math.sqrt(x * x + y * y);
}

public enum Planet(double mass, double radius) {
    Mercury(3.303e+23, 2.4397e6),
    Earth(5.976e+24, 6.37814e6);
    public double gravity() => 6.67300E-11 * mass / (radius * radius);
}

var p = new Point(3, 4);
println(p);                          // prints: Point[x=3, y=4]
println(p.length());                 // prints: 5.0
println(p == new Point(3, 4));       // prints: true
var q = p with { y = 0 };            // a copy with one component changed
println(q);                          // prints: Point[x=3, y=0]
var (x, y) = p;                      // deconstruction
println(x + y);                      // prints: 7

foreach (var planet in Planet.values()) println($"{planet}: {planet.gravity():F2}");
// prints: Mercury: 3.70
// prints: Earth: 9.80

// Tuples: lightweight unnamed records.
(int, String) pair = (1, "one");
println(pair.item2);                 // prints: one
```

A `sealed` interface or class lists (or, within one file, infers) its permitted subtypes, and
the compiler checks that switches over it are exhaustive.

## Pattern matching

```jsharp
public sealed interface Expr permits Num, Add, Mul, Neg;
public record Num(double value) : Expr;
public record Add(Expr left, Expr right) : Expr;
public record Mul(Expr left, Expr right) : Expr;
public record Neg(Expr inner) : Expr;

double eval(Expr e) => e switch {
    Num n => n.value,
    Add(var l, var r) => eval(l) + eval(r),
    Mul(Num { value: 0 }, _) => 0,                  // property pattern
    Mul(var l, var r) => eval(l) * eval(r),
    Neg(var inner) => -eval(inner),
};

String describe(Object? o) => o switch {
    null => "nothing",
    int i when i > 100 => "a big number",
    int i => $"the number {i}",
    String s => $"text of length {s.length()}",
    _ => "something else",
};

println(eval(new Add(new Num(2), new Mul(new Num(3), new Num(4)))));   // prints: 14.0
println(describe(null));          // prints: nothing
println(describe(500));           // prints: a big number
println(describe(5));             // prints: the number 5
println(describe("hey"));         // prints: text of length 3

Object o = 42;
if (o is int n and > 0) println($"positive int {n}");   // prints: positive int 42
```

Patterns: type (`String s`), positional (`Add(var l, var r)`), property (`{ value: 0 }`),
constants, relational (`> 0`), `and`/`or`/`not`, discards (`_`) and guards (`when`). Leaving out
a case of a sealed type or enum is a compile-time error.

## Generics

Generics are Java's, with bounds written `T : Bound` and C#-style declaration-site variance on
interfaces.

```jsharp
import java.util.List;

public interface Source<out T> { T next(); }

public class Counter : Source<Integer> {
    private int n;
    public override Integer next() => ++n;
}

T maxOf<T : Comparable<T>>(List<T> xs) {
    var best = xs.get(0);
    foreach (var x in xs) if (x.compareTo(best) > 0) best = x;
    return best;
}

Source<Number> numbers = new Counter();      // Source<Integer> is a Source<Number> (out)
println(numbers.next());                     // prints: 1
println(maxOf(List.of(3, 9, 4)));            // prints: 9
println(maxOf(List.of("pear", "apple")));    // prints: pear
```

## Lambdas and sequences

Lambdas convert to any Java functional interface. The standard library adds lazy, LINQ-style
operations to every `Iterable`, array and stream.

```jsharp
import java.util.List;
import java.util.function.Function;

record Person(String name, int age, String city);

var people = List.of(
    new Person("Ann", 31, "Oslo"),
    new Person("Bob", 25, "Rome"),
    new Person("Cid", 42, "Oslo"));

Function<int, int> twice = x => x * 2;
println(twice.apply(21));                                          // prints: 42

println(people.where(p => p.age > 30).select(p => p.name).toList());   // prints: [Ann, Cid]
println(people.orderBy(p => p.age).first().name);                  // prints: Bob
println(people.groupBy(p => p.city).keySet());                      // prints: [Oslo, Rome]
println(people.sum(p => p.age));                                    // prints: 98
println(people.any(p => p.city == "Rome"));                         // prints: true
println(range(1, 6).select(i => i * i).sum());                      // prints: 55

people.forEach(p => print(p.name.charAt(0)));                       // prints: ABC
println();

// Method references work too.
println(List.of("b", "a").stream().map(String::toUpperCase).sorted().toList());   // prints: [A, B]
```

Sequences are lazy: nothing runs until a terminal operation such as `toList`, `sum`, `first` or
`foreach`. Primitive sequences (`range`, `int[]` pipelines) never box their elements.

## Async and await

`async` functions return a `Task<T>` (a `CompletableFuture`) and run on virtual threads.
`await` waits for a task without blocking a platform thread.

```jsharp
import java.util.List;

async Task<int> slowSquare(int x) {
    await Task.delay(10);
    return x * x;
}

async Task<int> sumOfSquares() {
    val tasks = List.of(slowSquare(1), slowSquare(2), slowSquare(3));   // run concurrently
    var total = 0;
    foreach (var t in tasks) total += await t;
    return total;
}

println(await sumOfSquares());     // prints: 14
```

## Errors and resources

J# has no checked exceptions. `using` closes resources, and `catch ... when` filters exceptions.

```jsharp
class Resource : AutoCloseable {
    public override void close() => println("closed");
}

int parse(String s) {
    try {
        return Integer.parseInt(s);
    } catch (NumberFormatException e) when (s.isEmpty()) {
        return 0;
    } catch (NumberFormatException e) {
        return -1;
    }
}

using (var r = new Resource()) {
    println("working");                  // prints: working
}                                        // prints: closed

println(parse("12") + " " + parse("") + " " + parse("x"));   // prints: 12 0 -1

val config = System.getenv("SURELY_NOT_SET") ?? "fallback";
println(config);                         // prints: fallback
```

`require(cond, message)` throws `IllegalArgumentException`, `check` throws
`IllegalStateException`, and `error(message)` always throws, with the type `never`, so it can
end any expression.

## Working with Java

Any Java library works: put jars on the class path with `-cp`. J# reads their generics and
nullness annotations, and treats JavaBeans getters as properties.

```jsharp
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

val date = LocalDate.of(2024, 2, 29);
println(date.plusYears(1));                 // prints: 2025-02-28
println(date.dayOfWeek);                    // prints: THURSDAY

var counter = new AtomicInteger();
List.of(1, 2, 3).forEach(n => counter.addAndGet(n));
println(counter.get());                      // prints: 6

var sb = new StringBuilder();
sb.append("J").append('#');
println(sb.length() + " " + sb);             // prints: 2 J#
```

In the other direction, Java sees J# code as ordinary Java:

| J# | Java sees |
|---|---|
| `class C { public int x { get; set; } }` | `getX()` / `setX(int)` |
| `record P(int x)` | a Java record |
| top-level function `f` in `util.jsharp` | `UtilModule.f(...)` |
| `void f(int a, int b = 1)` | `f(int, int)` plus an overload `f(int)` |
| `T?` | `@Nullable T` (JSpecify) |
| `async Task<T> f()` | `Task<T> f()` (a `CompletableFuture<T>`) |
| `sealed interface S permits A, B` | a sealed interface |

## The tools

```
jsharp run <file|dir> [args...]       compile in memory and run
jsharp build <src...> -d out          compile to class files
jsharp build <src...> --jar app.jar --include-runtime
                                      a self-contained jar: java -jar app.jar
jsharp check <src...>                 diagnostics only (add --diagnostics=json for tools)
jsharp lsp                            language server for editors
```

For VS Code, install the extension in `editors/vscode` (see its README) to get highlighting,
errors as you type, hover, go to definition, an outline and completion. Other editors with LSP
support can run `jsharp lsp`.

Every error has a stable code, a source location and usually a suggestion:

```text
error[JS0603]: String has no method 'lenght'
  --> app.jsharp:18:11
   |
18 | println(s.lenght());
   |           ^^^^^^
   = help: did you mean 'length'?
```

To go further, read [`examples/ledger`](../examples/ledger), a 1,000-line program using most of
the language. [DECISIONS.md](../DECISIONS.md) records why things work the way they do, and the
language specification (`docs/LANGUAGE_SPEC.md` in the source tree) has the full details.
