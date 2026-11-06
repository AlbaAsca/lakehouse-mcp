# Runs inside the tools container: use ./mk <target> (mk.cmd on Windows).
DATASET := mkechinov/ecommerce-behavior-data-from-multi-category-store
MONTH ?= 2019-Oct
# 1% of the sessions, with one hour of the original passing per second: a month in about 12 minutes.
SAMPLE ?= 0.01
SPEEDUP ?= 3600
# Snapshots older than this go, along with the files only they use: time travel can no longer reach them.
TABLE ?= ecommerce.events
RETENTION ?= P7D

.PHONY: up down download-data seed seed-sample test test-board evals chaos demo smoke expire ask
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
	mvn -B -q -pl producer -am -DskipTests package
	java -jar producer/target/producer-*.jar --input /data/raw/$(MONTH).csv --sample-rate $(SAMPLE) --speedup $(SPEEDUP)
# Needs the stack up, nothing else: the synthetic sample is in the repo. All of it, without waiting.
seed-sample:
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
evals chaos demo:
	@echo "$@: not implemented yet, see the board" && exit 1
