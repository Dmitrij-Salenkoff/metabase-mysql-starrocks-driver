# Metabase mysql-starrocks Driver

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Metabase](https://img.shields.io/badge/Metabase-v0.50+-blue.svg)](https://www.metabase.com/)
[![StarRocks](https://img.shields.io/badge/StarRocks-v3.2+-green.svg)](https://www.starrocks.io/)

A community Metabase driver for [StarRocks](https://www.starrocks.io/) that appears in Metabase as `mysql-starrocks`, fixes MySQL protocol compatibility issues, and adds proper multi-catalog support.

## Why This Driver?

Metabase's built-in MySQL driver doesn't work properly with StarRocks because:

1. **`SHOW GRANTS FOR CURRENT_USER` is unsupported** — StarRocks uses a different privilege system than MySQL, causing metadata sync to fail with:
   ```
   No viable statement for input 'SHOW GRANTS FOR CURRENT_USER'
   ```

2. **Multi-catalog support** — StarRocks external catalogs (Hive, Iceberg, etc.) require `catalog.database` format which the MySQL driver doesn't handle well.

This driver extends Metabase's MySQL driver so it can reuse MySQL query behavior, but overrides the privilege sync path that would otherwise issue `SHOW GRANTS FOR CURRENT_USER`.

## Installation

### Option 1: Download Pre-built JAR

Download the latest `mysql-starrocks.metabase-driver-vX.X.X.jar` from the [Releases](../../releases) page.

### Option 2: Build from Source

```bash
# Install Clojure CLI (macOS)
brew install clojure/tools/clojure

# Clone and build
git clone https://github.com/Carbon-Arc/metabase-starrocks-driver.git
cd metabase-starrocks-driver
clojure -T:build uber

# Output: target/mysql-starrocks.metabase-driver.jar
```

### Deploy the Driver

Copy the JAR to your Metabase plugins directory:

```bash
# Docker
docker cp mysql-starrocks.metabase-driver.jar metabase:/plugins/

# Local installation
cp mysql-starrocks.metabase-driver.jar /path/to/metabase/plugins/

# Kubernetes
kubectl cp mysql-starrocks.metabase-driver.jar <namespace>/<pod>:/plugins/
```

Then restart Metabase to load the driver.

## Requirements

- **Metabase**: v0.50+ (tested with v0.57)
- **StarRocks**: v3.2+ (for external catalog support)
- **Java**: JDK 11-21 (for building from source)
- **JDBC**: MySQL Connector/J is bundled in the driver JAR

## Configuration

1. Go to **Admin → Databases → Add Database**
2. Select **"mysql-starrocks"** from the database type dropdown
3. Configure the connection:

| Field | Description | Example |
|-------|-------------|---------|
| Host | StarRocks FE hostname | `starrocks-fe.example.com` |
| Port | MySQL protocol port | `9030` |
| Catalog | StarRocks catalog name | `default_catalog` |
| Database | Database within catalog (optional) | `my_database` |
| Username | StarRocks user | `admin` |
| Password | User password | `••••••••` |

### Catalog Examples

- **Internal catalog**: `default_catalog`
- **Hive catalog**: `hive_catalog`
- **Iceberg/Polaris catalog**: `iceberg_catalog`

> **Tip**: Set the **Database** field to sync only that database. Leave it empty to scan all databases in the catalog.

## Side-by-side with the legacy driver

This driver intentionally uses a separate internal Metabase driver id: `mysql-starrocks`.

That means it can be installed next to an older StarRocks driver that registered itself as `starrocks`. Existing production connections that were created with the old `starrocks` driver will keep using that old driver as long as the old JAR remains in `/plugins`. New connections can be created with `mysql-starrocks` and tested independently.

For a gradual migration:

1. Keep the old `starrocks` JAR in `/plugins`.
2. Add `mysql-starrocks.metabase-driver.jar` to `/plugins`.
3. Restart Metabase.
4. Create a new database connection using `mysql-starrocks`.
5. Move questions/dashboards over after validating sync and query behavior.

Do not ship two different JARs that both register `driver.name: starrocks`; Metabase treats them as the same driver and they can override each other during plugin loading.

## Limitations

- Foreign key relationships are not supported (StarRocks limitation)
- Some advanced MySQL features may not be available

## Project Structure

```
metabase-starrocks-driver/
├── deps.edn                         # Dependencies & build config
├── build.clj                        # Build script
├── src/metabase/driver/
│   └── mysql_starrocks.clj          # Driver implementation
├── resources/
│   └── metabase-plugin.yaml         # Plugin manifest
└── docs/
    └── metabase-infrastructure.md   # Deployment notes
```

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

1. Clone the repository
2. Install [Clojure CLI tools](https://clojure.org/guides/install_clojure)
3. Make your changes in `src/metabase/driver/mysql_starrocks.clj`
4. Build and test: `clojure -T:build uber`

## Releases

This project uses [semantic versioning](https://semver.org/). Releases are automated via GitHub Actions.

To create a new release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

This triggers a workflow that builds the JAR and creates a GitHub release with the artifact attached (e.g., `mysql-starrocks.metabase-driver-v1.0.0.jar`).

## Troubleshooting

### Connection Failed

- Verify StarRocks FE is accessible on the MySQL protocol port (default: 9030)
- Check firewall rules allow connections from Metabase
- Ensure the user has appropriate permissions

### Tables Not Showing

- Verify the catalog name is correct
- Check that the user has `SELECT` privileges on the tables
- If you want all databases in a catalog, leave the Database field empty. If you want only one database, set the Database field and re-sync.

### Sync Errors

- This driver inherits from Metabase's MySQL driver but bypasses `SHOW GRANTS FOR CURRENT_USER` during sync. If you see grant-related errors, ensure you're using this StarRocks driver entry, not the built-in MySQL entry.

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.

## Related Projects

- [Metabase](https://github.com/metabase/metabase) — The open source BI tool
- [StarRocks](https://github.com/StarRocks/starrocks) — The analytics database
