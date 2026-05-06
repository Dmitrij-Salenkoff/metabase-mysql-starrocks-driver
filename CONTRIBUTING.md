# Contributing to Metabase StarRocks Driver

Thank you for your interest in contributing! This document provides guidelines and information for contributors.

## Code of Conduct

Please be respectful and constructive in all interactions.

## How to Contribute

### Reporting Bugs

Before creating a bug report, please check existing issues to avoid duplicates.

When filing a bug report, include:

- **Metabase version** (e.g., v0.52.1)
- **StarRocks version** (e.g., v3.2.0)
- **Driver version** (from the JAR filename or CHANGELOG)
- **Steps to reproduce** the issue
- **Expected behavior** vs **actual behavior**
- **Error messages** or logs (sanitize any sensitive data)
- **Configuration details** (catalog type, connection settings)

### Suggesting Features

Feature requests are welcome! Please:

1. Check if the feature has already been requested
2. Describe the use case and why it would be valuable
3. Provide examples of how it would work

### Pull Requests

1. **Fork the repository** and create your branch from `main`
2. **Make your changes** following the coding standards below
3. **Test your changes** with a real StarRocks instance
4. **Update documentation** if needed
5. **Submit a pull request** with a clear description

## Development Setup

### Prerequisites

- [Clojure CLI tools](https://clojure.org/guides/install_clojure) (v1.11+)
- JDK 11-21
- A StarRocks instance for testing

### Building

```bash
# Clone your fork
git clone https://github.com/Carbon-Arc/metabase-starrocks-driver.git
cd metabase-starrocks-driver

# Build the driver
clojure -T:build uber

# Output: target/mysql-starrocks.metabase-driver.jar
```

### Testing

Currently, testing requires a running StarRocks instance:

1. Build the driver JAR
2. Copy to your Metabase plugins directory
3. Restart Metabase
4. Add a StarRocks database connection
5. Verify sync and queries work correctly

We welcome contributions to add automated testing!

## Coding Standards

### Code Organization

```clojure
;; Group related functionality with section headers
;;; +------------------------------------------------+
;;; |               Section Name                      |
;;; +------------------------------------------------+
```

### Key Files

- **`mysql_starrocks.clj`**: The main driver code. Contains all Metabase driver protocol implementations.
- **`metabase-plugin.yaml`**: Defines connection properties shown in the Metabase UI.
- **`deps.edn`**: Clojure dependencies and build configuration.

## Getting Help

- Open an issue for bugs or feature requests
- Start a discussion for questions or ideas
- Check existing issues and discussions first

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.

---

Thank you for contributing! 🎉
