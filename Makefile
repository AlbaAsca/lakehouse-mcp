# Runs inside the tools container: use ./mk <target> (mk.cmd on Windows).
DATASET := mkechinov/ecommerce-behavior-data-from-multi-category-store
MONTH ?= 2019-Oct
# 1% of the sessions, with one hour of the original passing per second: a month in about 12 minutes.
SAMPLE ?= 0.01
SPEEDUP ?= 3600
# Snapshots older than this go, along with the files only they use: time travel can no longer reach them.
TABLE ?= ecommerce.events
RETENTION ?= P7D

.PHONY: up down download-data seed seed-sample test test-board evals chaos demo heal smoke expire ask profile-data
up:
	docker compose up -d --build --wait
down:
	docker compose down
smoke:
	sh infra/smoke.sh
download-data:
	mkdir -p /data/raw
	kaggle datasets download $(DATASET) -f $(MONTH).csv -p /data/raw
# MODULE=<name> tests only that module: its dependencies are built and installed without running their tests.
test:
ifdef MODULE
	mvn -B -q -pl $(MODULE) -am -DskipTests install
	mvn -B -q -pl $(MODULE) verify
else
	mvn -B -q verify
endif
test-board:
	/opt/py/bin/python -m pytest tests -q
# Needs the stack up and the real CSV in /data/raw. Kaggle may deliver the file zipped.
seed:
	test -f /data/raw/$(MONTH).csv || unzip -o /data/raw/$(MONTH).csv.zip -d /data/raw
	rm -f producer/target/producer-*.jar
	mvn -B -q -pl producer -am -DskipTests package
	java -jar producer/target/producer-*.jar --input /data/raw/$(MONTH).csv --sample-rate $(SAMPLE) --speedup $(SPEEDUP)
# Needs the stack up, nothing else: the synthetic sample is in the repo. All of it, without waiting.
seed-sample:
	rm -f producer/target/producer-*.jar
	mvn -B -q -pl producer -am -DskipTests package
	java -jar producer/target/producer-*.jar --input testdata/synthetic-events.csv.gz --sample-rate 1 --speedup 1e12
	@echo "seed-sample: running it again duplicates the rows in the table (docker compose down -v starts clean)."
expire:
	docker compose run --rm --build expire-snapshots --table $(TABLE) --retention $(RETENTION)
# Q="question": read by the shell from the environment (make exports command-line variables), so quotes and
# apostrophes inside it reach the agent untouched. Starts the stack it needs if it is not up.
ask:
	@test -n "$$Q" || { echo 'Usage: ./mk ask Q="your question"' >&2; exit 2; }
	docker compose run --rm --build agent ask "$$Q"
# Aggregates of the real CSV (no rows, no identifiers) into docs/data-profile.md.
profile-data:
	sed -e 's|@CSV@|/data/raw/$(MONTH).csv|' -e 's|@OUT@|docs/data-profile.md|' infra/profile-data.sql | duckdb
# The repair cycle: the agent proposes, you answer y/N on this console, and only then is it approved and applied.
# Needs an interactive terminal (stdin) for the question.
heal:
	docker compose run --rm --build agent heal
# For the person who runs the demo: a question, a failure, and the agent repairing it with your approval.
DEMO_Q ?= Which five brands have the highest purchase revenue?
demo:
	$(MAKE) ask Q="$(DEMO_Q)"
	$(MAKE) chaos
	$(MAKE) heal
# Injects a failure into the real table: ./mk chaos SCENARIO=duplicates|empty-partition [TABLE=ns.table] [DAY=YYYY-MM-DD]
# [PERCENT=10]. Prints the JSON report (with the snapshot before the damage). Runs in the mcp-server image.
DAY ?= 2019-10-01
PERCENT ?= 10
chaos:
	@test -n "$(SCENARIO)" || { echo 'Usage: ./mk chaos SCENARIO=duplicates|empty-partition [TABLE=ns.table] [DAY=YYYY-MM-DD] [PERCENT=10]' >&2; exit 2; }
	@case "$(SCENARIO)" in duplicates|empty-partition) ;; *) echo "Unknown SCENARIO '$(SCENARIO)': use duplicates or empty-partition" >&2; exit 2;; esac
	docker compose run --rm --build --no-deps --entrypoint java mcp-server -Xmx256m -Dloader.main=io.github.albaasca.lakehouse.mcp.ChaosCommand -cp /app/mcp-server.jar org.springframework.boot.loader.launch.PropertiesLauncher $(SCENARIO) --table $(TABLE) --day $(DAY) --percent $(PERCENT)
evals:
	@echo "$@: not implemented yet, see the board" && exit 1
