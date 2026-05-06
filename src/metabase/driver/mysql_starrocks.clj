(ns metabase.driver.mysql-starrocks
  "mysql-starrocks driver for Metabase.

   Extends the MySQL driver with StarRocks-specific functionality:
   - Fixes the SHOW GRANTS FOR CURRENT_USER incompatibility
   - Adds proper catalog support for multi-catalog environments
   - Handles StarRocks-specific metadata queries

   Based on Metabase's Starburst driver patterns for catalog handling."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.log :as log])
  (:import
   (java.sql Connection ResultSet)))

(set! *warn-on-reflection* true)

;; Register mysql-starrocks as a MySQL-derived driver, while overriding the MySQL privilege check below.
(driver/register! :mysql-starrocks :parent :mysql)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Driver Features                                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Declare what features StarRocks supports
(doseq [[feature supported?] {:set-timezone                    true
                              :basic-aggregations              true
                              :standard-deviation-aggregations true
                              :expressions                     true
                              :native-parameters               true
                              :expression-aggregations         true
                              :binning                         true
                              :foreign-keys                    false
                              :nested-field-columns            false
                              :connection/multiple-databases   true
                              :metadata/key-constraints        false
                              :describe-fields                 false
                              :describe-fks                    false
                              :now                             true
                              :datetime-diff                   true
                              :temporal-extract                true
                              :date-arithmetics                true
                              :advanced-math-expressions       true}]
  (defmethod driver/database-supports? [:mysql-starrocks feature] [_ _ _] supported?))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Connection Details                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod sql-jdbc.conn/connection-details->spec :mysql-starrocks
  [_ {:keys [host port catalog dbname user password additional-options]
      :or   {host "localhost"
             port 9030
             catalog "default_catalog"}}]
  (let [;; Build the database name as catalog.database if both are provided
        ;; For external catalogs, StarRocks requires catalog.database format
        ;; If only catalog is provided, we use catalog.information_schema as a valid connection target
        ;; This allows us to connect and then query SHOW DATABASES to list all databases
        catalog-trimmed (when catalog (str/trim catalog))
        dbname-trimmed (when dbname (str/trim dbname))

        db-name (cond
                  ;; Both catalog and database provided
                  (and (not (str/blank? catalog-trimmed))
                       (not (str/blank? dbname-trimmed)))
                  (str catalog-trimmed "." dbname-trimmed)

                  ;; Only catalog provided - use information_schema as connection target
                  ;; This is a system database that always exists in every catalog
                  (not (str/blank? catalog-trimmed))
                  (str catalog-trimmed ".information_schema")

                  ;; Fallback to default_catalog
                  :else
                  "default_catalog.information_schema")

        ;; Base JDBC spec using the official MySQL Connector/J driver.
        base-spec {:classname   "com.mysql.cj.jdbc.Driver"
                   :subprotocol "mysql"
                   :subname     (str "//" host ":" port "/" db-name)
                   :user        user
                   :password    password
                   ;; StarRocks-specific settings
                   :tinyInt1isBit "false"
                   :yearIsDateType "false"
                   :serverTimezone "UTC"
                   :useSSL "false"
                   :allowPublicKeyRetrieval "true"
                   :zeroDateTimeBehavior "convertToNull"}]
    ;; Merge any additional options
    (if (and additional-options (not (str/blank? additional-options)))
      (merge base-spec
             (into {}
                   (for [pair (str/split additional-options #"&")
                         :when (not (str/blank? pair))]
                     (let [[k v] (str/split pair #"=" 2)]
                       [(keyword k) (or v "")]))))
      base-spec)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Type Mappings                                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private mysql-starrocks-type->base-type
  "Map of StarRocks types to Metabase base types."
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"(?i)^boolean$"                  :type/Boolean]
    [#"(?i)^tinyint$"                  :type/Integer]
    [#"(?i)^smallint$"                 :type/Integer]
    [#"(?i)^int$"                      :type/Integer]
    [#"(?i)^bigint$"                   :type/BigInteger]
    [#"(?i)^largeint$"                 :type/BigInteger]
    [#"(?i)^float$"                    :type/Float]
    [#"(?i)^double$"                   :type/Float]
    [#"(?i)^decimal.*"                 :type/Decimal]
    [#"(?i)^varchar.*"                 :type/Text]
    [#"(?i)^char.*"                    :type/Text]
    [#"(?i)^string$"                   :type/Text]
    [#"(?i)^text$"                     :type/Text]
    [#"(?i)^json$"                     :type/JSON]
    [#"(?i)^date$"                     :type/Date]
    [#"(?i)^datetime$"                 :type/DateTime]
    [#"(?i)^timestamp$"                :type/DateTime]
    [#"(?i)^array.*"                   :type/Array]
    [#"(?i)^map.*"                     :type/Dictionary]
    [#"(?i)^struct.*"                  :type/*]
    [#"(?i)^bitmap$"                   :type/*]
    [#"(?i)^hll$"                      :type/*]
    [#"(?i)^percentile$"               :type/*]
    [#".*"                             :type/*]]))

(defmethod sql-jdbc.sync/database-type->base-type :mysql-starrocks
  [_ field-type]
  (mysql-starrocks-type->base-type field-type))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Metadata / Sync                                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Schemas to exclude from sync
(def ^:private excluded-schemas
  #{"information_schema" "_statistics_" "INFORMATION_SCHEMA"})

;; CRITICAL: Override the MySQL parent privilege check to avoid SHOW GRANTS FOR CURRENT_USER.
;; StarRocks doesn't support that MySQL syntax.
(defmethod sql-jdbc.sync/current-user-table-privileges :mysql-starrocks
  [_driver _conn-spec & _options]
  ;; Return nil to skip privilege checking - StarRocks handles permissions differently
  nil)

(defn- describe-catalog-sql
  "The SHOW DATABASES statement that will list all schemas/databases for the current catalog."
  [_driver]
  "SHOW DATABASES")

(defn- quote-identifier
  "Quotes a MySQL/StarRocks identifier with backticks."
  [identifier]
  (str "`" (str/replace (str identifier) "`" "``") "`"))

(defn- describe-schema-sql
  "The SHOW TABLES statement that will list all tables for the given schema/database."
  [_driver schema]
  (str "SHOW TABLES FROM " (quote-identifier schema)))

(defn- show-columns-sql
  "The SHOW FULL COLUMNS statement that will list columns for the given table."
  [_driver schema table]
  (str "SHOW FULL COLUMNS FROM " (quote-identifier schema) "." (quote-identifier table)))

(defn- describe-table-sql
  "The DESCRIBE statement that will list information about the given table."
  [_driver schema table]
  (str "DESCRIBE " (quote-identifier schema) "." (quote-identifier table)))

(defn- information-schema-columns-sql
  "The information_schema query that will list columns for a given table."
  [_driver]
  (str "SELECT COLUMN_NAME, DATA_TYPE, ORDINAL_POSITION "
       "FROM information_schema.columns "
       "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
       "ORDER BY ORDINAL_POSITION"))

(defn- field-metadata
  "Builds Metabase field metadata while preserving the database name in the UI."
  [col-name col-type position]
  {:name              col-name
   :display-name      col-name
   :database-type     col-type
   :base-type         (mysql-starrocks-type->base-type col-type)
   :database-position position})

(defn- fields-from-describe
  "Reads StarRocks column metadata where the first two columns are name and type."
  [^ResultSet rs]
  (loop [fields []
         idx 0]
    (if (.next rs)
      (let [col-name (.getString rs 1)
            col-type (.getString rs 2)]
        (recur (conj fields (field-metadata col-name col-type idx))
               (inc idx)))
      (set fields))))

(defn- fields-from-information-schema
  "Reads information_schema.columns output into Metabase field metadata."
  [^ResultSet rs]
  (loop [fields []]
    (if (.next rs)
      (let [col-name (.getString rs 1)
            col-type (.getString rs 2)
            position (dec (.getInt rs 3))]
        (recur (conj fields (field-metadata col-name col-type position))))
      (set fields))))

(defn- try-get-fields
  "Attempts to get fields and returns an empty set if that metadata path fails."
  [source f]
  (try
    (f)
    (catch Exception e
      (log/warnf "Could not get mysql-starrocks fields using %s: %s" source (.getMessage e))
      #{})))

(defn- get-fields
  "Gets fields for the given table using SHOW FULL COLUMNS, then information_schema and DESCRIBE as fallbacks."
  [driver ^Connection conn schema table-name]
  (let [fields (try-get-fields
                "SHOW FULL COLUMNS"
                (fn []
                  (with-open [stmt (.createStatement conn)]
                    (let [sql (show-columns-sql driver schema table-name)]
                      (with-open [rs (.executeQuery stmt sql)]
                        (fields-from-describe rs))))))]
    (if (seq fields)
      fields
      (let [fields (try-get-fields
                    "information_schema.columns"
                    (fn []
                      (with-open [stmt (.prepareStatement conn (information-schema-columns-sql driver))]
                        (.setString stmt 1 schema)
                        (.setString stmt 2 table-name)
                        (with-open [rs (.executeQuery stmt)]
                          (fields-from-information-schema rs)))))]
        (if (seq fields)
          fields
          (try-get-fields
           "DESCRIBE"
           (fn []
             (with-open [stmt (.createStatement conn)]
               (let [sql (describe-table-sql driver schema table-name)]
                 (with-open [rs (.executeQuery stmt sql)]
                   (fields-from-describe rs)))))))))))

(defn- get-schemas
  "Gets all schemas/databases in the current catalog."
  [driver ^Connection conn]
  (with-open [stmt (.createStatement conn)]
    (let [sql (describe-catalog-sql driver)
          rs  (.executeQuery stmt sql)]
      (loop [schemas []]
        (if (.next ^ResultSet rs)
          (let [schema-name (.getString ^ResultSet rs 1)]
            (recur (if (contains? excluded-schemas schema-name)
                     schemas
                     (conj schemas schema-name))))
          schemas)))))

(defn- configured-schema
  "Returns the configured database/schema, if Metabase connection details specify one."
  [database]
  (let [dbname (get-in database [:details :dbname])
        dbname-trimmed (when dbname (str/trim dbname))]
    (when-not (str/blank? dbname-trimmed)
      dbname-trimmed)))

(defn- get-tables-in-schema
  "Gets all tables in the given schema/database."
  [driver ^Connection conn schema]
  (try
    (with-open [stmt (.createStatement conn)]
      (let [sql (describe-schema-sql driver schema)
            rs  (.executeQuery stmt sql)]
        (loop [tables []]
          (if (.next ^ResultSet rs)
            (let [table-name (.getString ^ResultSet rs 1)]
              (recur (conj tables {:name         table-name
                                   :display-name table-name
                                   :schema       schema})))
            tables))))
    (catch Exception e
      (log/warnf "Could not get tables from schema %s: %s" schema (.getMessage e))
      [])))

(defmethod driver/describe-database :mysql-starrocks
  [driver database]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   database
   nil
   (fn [^Connection conn]
     (let [schemas (if-let [schema (configured-schema database)]
                     [schema]
                     (get-schemas driver conn))
           tables  (into #{}
                         (mapcat (fn [schema]
                                   (get-tables-in-schema driver conn schema)))
                         schemas)]
       {:tables tables}))))

(defmethod driver/describe-table :mysql-starrocks
  [driver database {schema :schema, table-name :name}]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   database
   nil
   (fn [^Connection conn]
     (let [schema (or schema (configured-schema database))
           fields (get-fields driver conn schema table-name)]
       (when (empty? fields)
         (log/warnf "Could not find fields for mysql-starrocks table %s.%s" schema table-name))
       {:schema schema
        :name   table-name
        :display-name table-name
        :fields fields}))))

;;; The StarRocks JDBC doesn't support foreign keys
(defmethod driver/describe-table-fks :mysql-starrocks
  [_driver _database _table]
  nil)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Query Processing                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Use MySQL-style quoting since StarRocks is MySQL-compatible
(defmethod sql.qp/quote-style :mysql-starrocks [_] :mysql)

;; Date/time handling
(defmethod sql.qp/unix-timestamp->honeysql [:mysql-starrocks :seconds]
  [_ _ expr]
  [:from_unixtime expr])

(defmethod sql.qp/unix-timestamp->honeysql [:mysql-starrocks :milliseconds]
  [_ _ expr]
  [:from_unixtime [:/ expr 1000]])

(defmethod sql.qp/current-datetime-honeysql-form :mysql-starrocks
  [_]
  :%now)

(defmethod sql.qp/date [:mysql-starrocks :default] [_ _ expr] expr)

(defmethod sql.qp/date [:mysql-starrocks :minute]
  [_ _ expr]
  [:date_trunc "minute" expr])

(defmethod sql.qp/date [:mysql-starrocks :hour]
  [_ _ expr]
  [:date_trunc "hour" expr])

(defmethod sql.qp/date [:mysql-starrocks :day]
  [_ _ expr]
  [:date_trunc "day" expr])

(defmethod sql.qp/date [:mysql-starrocks :week]
  [_ _ expr]
  [:date_trunc "week" expr])

(defmethod sql.qp/date [:mysql-starrocks :month]
  [_ _ expr]
  [:date_trunc "month" expr])

(defmethod sql.qp/date [:mysql-starrocks :quarter]
  [_ _ expr]
  [:date_trunc "quarter" expr])

(defmethod sql.qp/date [:mysql-starrocks :year]
  [_ _ expr]
  [:date_trunc "year" expr])

(defmethod sql.qp/date [:mysql-starrocks :minute-of-hour] [_ _ expr] [:minute expr])
(defmethod sql.qp/date [:mysql-starrocks :hour-of-day]   [_ _ expr] [:hour expr])
(defmethod sql.qp/date [:mysql-starrocks :day-of-month]  [_ _ expr] [:day expr])
(defmethod sql.qp/date [:mysql-starrocks :month-of-year] [_ _ expr] [:month expr])
(defmethod sql.qp/date [:mysql-starrocks :year-of-era]   [_ _ expr] [:year expr])
(defmethod sql.qp/date [:mysql-starrocks :day-of-week]   [_ _ expr] [:dayofweek expr])
(defmethod sql.qp/date [:mysql-starrocks :week-of-year]  [_ _ expr] [:week expr])
(defmethod sql.qp/date [:mysql-starrocks :quarter-of-year] [_ _ expr] [:quarter expr])

(defmethod sql.qp/add-interval-honeysql-form :mysql-starrocks
  [_ hsql-form amount unit]
  [:date_add hsql-form [:interval amount (keyword (name unit))]])

(defmethod sql.qp/datetime-diff [:mysql-starrocks :year]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/datetime-diff [:mysql-starrocks :month]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/datetime-diff [:mysql-starrocks :day]
  [_ unit x y]
  [:datediff y x])

(defmethod sql.qp/datetime-diff [:mysql-starrocks :hour]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/datetime-diff [:mysql-starrocks :minute]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/datetime-diff [:mysql-starrocks :second]
  [_ unit x y]
  [:timestampdiff [:raw (name unit)] x y])

(defmethod sql.qp/cast-temporal-string [:mysql-starrocks :Coercion/ISO8601->DateTime]
  [_ _ expr]
  [:cast expr :datetime])

(defmethod sql.qp/cast-temporal-string [:mysql-starrocks :Coercion/ISO8601->Date]
  [_ _ expr]
  [:cast expr :date])

(defmethod sql.qp/cast-temporal-string [:mysql-starrocks :Coercion/YYYYMMDDHHMMSSString->Temporal]
  [_ _ expr]
  [:cast expr :datetime])

(defmethod sql.qp/cast-temporal-byte [:mysql-starrocks :Coercion/YYYYMMDDHHMMSSBytes->Temporal]
  [_ _ expr]
  [:cast expr :datetime])

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Driver Metadata                                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod driver/display-name :mysql-starrocks [_]
  "mysql-starrocks")

(defmethod driver/db-start-of-week :mysql-starrocks [_]
  :monday)

(defmethod driver/db-default-timezone :mysql-starrocks
  [driver database]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   database
   nil
   (fn [^Connection conn]
     (try
       (with-open [stmt (.createStatement conn)]
         (let [rs (.executeQuery stmt "SELECT @@system_time_zone")]
           (when (.next ^ResultSet rs)
             (.getString ^ResultSet rs 1))))
       (catch Exception _
         "UTC")))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Connection Testing                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod driver/can-connect? :mysql-starrocks
  [driver details]
  (try
    (sql-jdbc.conn/with-connection-spec-for-testing-connection [spec [driver details]]
      ;; Just try a simple query to verify connection
      (jdbc/query spec ["SELECT 1"])
      true)
    (catch Exception e
      (log/errorf "StarRocks connection failed: %s" (.getMessage e))
      false)))

(defmethod driver/humanize-connection-error-message :mysql-starrocks
  [_ message]
  ;; Ensure message is a string
  (let [msg (if (string? message) message (str message))]
    (cond
      (re-find #"(?i)communications link failure" msg)
      "Unable to connect to StarRocks. Please check that the host and port are correct."

      (re-find #"(?i)access denied" msg)
      "Access denied. Please check your username and password."

      (re-find #"(?i)unknown database" msg)
      "Database not found. Please check the catalog and database names."

      (re-find #"(?i)unknown catalog" msg)
      "Catalog not found. Please check the catalog name."

      :else
      msg)))
