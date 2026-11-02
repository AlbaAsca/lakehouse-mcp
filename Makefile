# Runs inside the tools container: use ./mk <target> (mk.cmd on Windows).
DATASET := mkechinov/ecommerce-behavior-data-from-multi-category-store
MONTH ?= 2019-Oct
# 1% of the sessions, with one hour of the original passing per second: a month in about 12 minutes.
SAMPLE ?= 0.01
SPEEDUP ?= 3600

.PHONY: up down download-data seed test test-board evals chaos bench demo smoke
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
evals chaos bench demo:
	@echo "$@: not implemented yet, see the board" && exit 1
