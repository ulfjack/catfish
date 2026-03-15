# Catfish Java HTTP Library

TO BE RENAMED.

## Coverage Reports

Run this to collect coverage data:
```
bazel coverage //javatest/...
```

Run this to generate an HTML report:
```
genhtml --prefix "$(pwd)" --ignore-errors unsupported,inconsistent,source --synthesize-missing --branch-coverage -o .coverage/ bazel-out/_coverage/_coverage_report.dat
```

Or this to run a simplified text report:
```
bazel run :coverage_report
```
