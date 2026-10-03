Interop cases. Each directory has an optional `lib/` (compiled first) and `main/` (compiled against
`lib/`), each holding `.java` or `.jsharp` sources, plus `expected.txt` (stdout of the program).
A directory with both kinds is one module: the J# compiler reads the Java declarations, compiles
the J# sources and then the Java sources with javac (D082).
The entry point is the J# entry point when `main/` is J#, else the Java class named in
`mainclass.txt` (default `Main`). Run by `InteropTest` on a fresh JVM with `-Xverify:all`.
