# jlox in J#

A tree-walking interpreter for Lox, the language from Robert Nystrom's
[*Crafting Interpreters*](https://craftinginterpreters.com), written in J#. It is the first
large program written to find J#'s rough edges: the fixes it led to are D089–D093 in
DECISIONS.md (smart casts on stable members, `is not` bindings, null-conditional chains,
switch arm blocks with `yield`, lifted equality).

- `scanner.jsharp`: source text to tokens (map literals, computed properties, defaults)
- `ast.jsharp`: the syntax tree as sealed interfaces of records
- `parser.jsharp`: recursive descent with error recovery (functions passed by name, `params`,
  switch arm blocks)
- `resolver.jsharp`: static scoping pass
- `interpreter.jsharp`, `runtime.jsharp`: evaluation, environments, closures, classes
  (exhaustive switches over the syntax tree, smart casts, `is not`, `?.` chains, `??`)
- `main.jsharp`: runs a script, or a prompt without arguments

```
jsharp run examples/lox/src -- examples/lox/scripts/fib.lox
jsharp run examples/lox/src          # interactive prompt
```

Exit codes follow jlox: 65 for syntax errors, 70 for runtime errors.
