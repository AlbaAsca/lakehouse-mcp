# Runs inside the tools container: use ./mk <target> (mk.cmd on Windows).
DATASET := mkechinov/ecommerce-behavior-data-from-multi-category-store
MONTH ?= 2019-Oct

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
test:
	mvn -B -q verify
test-board:
	/opt/py/bin/python -m pytest tests -q
evals seed chaos bench demo:
	@echo "$@: not implemented yet, see the board" && exit 1
