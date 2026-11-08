-- Aggregates only: no rows, no user_id / user_session values. Placeholders @CSV@ and @OUT@ are filled by `make profile-data`.
.mode markdown
.output @OUT@
.print # Data profile
.print
.print Aggregates of the real CSV; no row or identifier value is included.
.print

SET temp_directory = '/data/duckdb_tmp';
SET memory_limit = '3GB';

CREATE TEMP TABLE t AS
SELECT *, row_number() OVER () AS file_row_number FROM read_csv('@CSV@', header = true, all_varchar = true);

.print ## Volume
SELECT count(*) AS rows,
       count(DISTINCT substr(event_time, 1, 10)) AS days,
       min(event_time) AS first_event_time,
       max(event_time) AS last_event_time,
       count(DISTINCT user_id) AS distinct_users,
       count(DISTINCT user_session) AS distinct_sessions,
       count(DISTINCT product_id) AS distinct_products
FROM t;

.print
.print ## Nulls (empty or missing) per column
SELECT count(*) - count(NULLIF(event_time, '')) AS event_time,
       count(*) - count(NULLIF(event_type, '')) AS event_type,
       count(*) - count(NULLIF(product_id, '')) AS product_id,
       count(*) - count(NULLIF(category_id, '')) AS category_id,
       count(*) - count(NULLIF(category_code, '')) AS category_code,
       count(*) - count(NULLIF(brand, '')) AS brand,
       count(*) - count(NULLIF(price, '')) AS price,
       count(*) - count(NULLIF(user_id, '')) AS user_id,
       count(*) - count(NULLIF(user_session, '')) AS user_session
FROM t;

.print
.print ## Price range
SELECT min(try_cast(price AS DOUBLE)) AS min_price,
       max(try_cast(price AS DOUBLE)) AS max_price,
       count(*) FILTER (WHERE try_cast(price AS DOUBLE) <= 0) AS zero_or_negative,
       count(*) FILTER (WHERE price <> '' AND try_cast(price AS DOUBLE) IS NULL) AS not_numeric
FROM t;

.print
.print ## Duplicates (identical full rows)
SELECT (SELECT count(*) FROM t) - (SELECT count(*) FROM (SELECT DISTINCT * EXCLUDE (file_row_number) FROM t)) AS duplicate_rows;

.print
.print ## Order in file (rows whose event_time is earlier than the previous row)
SELECT count(*) FILTER (WHERE event_time < prev) AS out_of_order_rows,
       count(*) FILTER (WHERE event_time < prev) = 0 AS sorted_by_event_time
FROM (SELECT event_time, lag(event_time) OVER (ORDER BY file_row_number) AS prev FROM t);

.print
.print ## Rows per day
SELECT substr(event_time, 1, 10) AS day, count(*) AS rows FROM t GROUP BY day ORDER BY day;

.print
.print ## event_type
SELECT event_type, count(*) AS rows FROM t GROUP BY event_type ORDER BY rows DESC LIMIT 10;
.output
