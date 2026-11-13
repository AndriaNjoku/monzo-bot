# Finance domain checks

Pure Java 17 domain tests, runnable without the Android SDK or external dependencies.

From the repository root:

```sh
mkdir -p /tmp/monzo-domain-checks
javac --release 17 -d /tmp/monzo-domain-checks android/app/src/main/java/org/monzobot/app/Finance.java android/domain-tests/FinanceTest.java
java -cp /tmp/monzo-domain-checks FinanceTest fixtures/synthetic.csv
```

All data is synthetic. Import results, raw row evidence and report maps are immutable. Exact duplicate IDs are idempotent; changed rows with the same ID reject the entire import for explicit reconciliation. CSV requires equal column counts; Google Sheets values may omit trailing empty cells.

Category and daily values represent signed net outflow: debits positive, credits negative. Total money in/out is cash movement, not account balance. Refund, salary, internal-transfer and pot semantics are not inferred from unverified source Type strings. There are no silent transfer exclusions. Category overrides preserve original evidence separately. Overflow causes an error rather than wrapping or rounding.

The Sheets client currently asks for `FORMATTED_VALUE`. Account Amount cells therefore accept validated UK GBP display formatting (`£1,234.56`, `-£54.28`) as well as plain decimals. Raw display text is preserved. European separators, malformed thousands groups, parentheses and non-GBP symbols are rejected; decimal conventions are never guessed. Budget entry through `minor()` stays strict decimal-only. Dates accept only ISO and dd/MM/yyyy: unsupported custom display formats fail explicitly.

On a runtime with the compiler module but no `javac` launcher/Java 17 symbol archive, the domain can also be checked with `java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 ...`. This checks source/bytecode compatibility but does not replace Android compilation against the configured SDK.
