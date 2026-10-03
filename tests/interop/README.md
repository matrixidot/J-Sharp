Interop cases. Each directory has `lib/` (compiled first) and `main/` (compiled against `lib/`),
each holding either `.java` or `.jsharp` sources, plus `expected.txt` (stdout of the program).
The entry point is the J# entry point when `main/` is J#, else the Java class named in
`mainclass.txt` (default `Main`). Run by `InteropTest` on a fresh JVM with `-Xverify:all`.
