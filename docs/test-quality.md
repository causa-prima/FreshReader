# Test quality: coverage and mutation testing

Both run on the `core` module, where the tests live (the app module has none).

## Coverage (Kover)

```sh
./gradlew :core:koverLog          # line coverage on the console
./gradlew :core:koverHtmlReport   # core/build/reports/kover/html/index.html
```

Branch coverage reads lower than it is: Kotlin compiles null checks, `when` over sealed types and coroutine state
machines into branches that no source line can take.

## Mutation testing (PIT)

```sh
./gradlew :core:pitest            # about 20 minutes; core/build/reports/pitest/index.html
```

PIT changes the compiled code one place at a time and runs the tests against each change (a mutant). A mutant
the tests still pass against has survived: the tests do not notice that change.

Without the commercial Arcmutate Kotlin plugin, PIT also mutates code the Kotlin compiler generates. Survivors of
these kinds are noise, not gaps in the tests:

- `removed call to kotlin/jvm/internal/Intrinsics::checkNotNull…`: Kotlin's own null checks
- `removed call to kotlin/ResultKt::throwOnFailure` and `replaced return value with null` in `invokeSuspend` or
  in a `suspend fun`: the coroutine state machine
- `removed call to kotlin/io/CloseableKt::closeFinally`: closing in `use`
- `throwIndexOverflow`, `throwCountOverflow`: overflow checks of inlined collection functions
- getters of classes the code reads by field (`$$serializer`, private `@Serializable` classes, `Companion` constants)

Mutants that time out count as killed; the many timeouts are mutated loops and waits.
