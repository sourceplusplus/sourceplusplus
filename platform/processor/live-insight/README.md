# ![](https://github.com/sourceplusplus/sourceplusplus/blob/master/.github/media/sourcepp_logo.svg)

[![License](https://camo.githubusercontent.com/93398bf31ebbfa60f726c4f6a0910291b8156be0708f3160bad60d0d0e1a4c3f/68747470733a2f2f696d672e736869656c64732e696f2f6769746875622f6c6963656e73652f736f75726365706c7573706c75732f6c6976652d706c6174666f726d)](LICENSE)
![GitHub release](https://img.shields.io/github/v/release/sourceplusplus/probe-jvm?include_prereleases)
[![Build](https://github.com/sourceplusplus/probe-jvm/actions/workflows/build.yml/badge.svg)](https://github.com/sourceplusplus/probe-jvm/actions/workflows/build.yml)

# What is this?

This projects holds the source code for the Source++ Live Insight Processor. This component is responsible for
maintaining a graph of all the code in your project and providing real-time insights into the codebase.

# Insights

The following runtime metrics are collected:

- Function duration
    - Total invocations
    - Average time per invocation
- Branch probability
    - Total invocations
    - Total branch taken
- Loop iterations
    - Average iterations
    - Average time per iteration
- Parameter value distribution

Using the combination of these runtime metrics, Source++ is able to provide *Live Insights* (i.e. runtime metric-powered
auto-completions and inspections).

## Auto-Completion

### Practical IntelliSense

Auto-completion suggestions based on the runtime behavior of the codebase. For example, if a method has a generic
parameter, Source++ will provide and prioritize suggestions for the most common types used for that parameter.

## Inspections

### Runtime Dead Code / Runtime Stale Flag

Reports code that is never executed at runtime. While static code analysis can detect dead code, it cannot detect dead
code that is only executed under certain conditions. For example, a method that is only called when a certain
configuration setting is enabled. Source++ is able to detect this dead code at runtime. This is useful for cleaning up
code that is no longer needed.

### Performance Impact

> Predicts impact of un-deployed code changes. For example, if a developer adds a slow method to a fast method, Source++
> will predict the performance impact of this change. This is useful for developers to make informed decisions about
> refactoring code.

#### Critical Loop Prediction

Predicts the average total time for newly created loops via average iterations and average time per iteration runtime
metrics. The time considered to be "critical" is configurable with a default value of 5 seconds.

#### Critical Method Prediction

Predicts the average total time for newly created methods via method duration runtime metrics. The time considered to be
"critical" is configurable with a default value of 5 seconds.
