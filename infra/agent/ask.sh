#!/bin/sh
# Without this check a missing key ends in a Spring stack trace. Exit code 5: the configuration is incomplete.
provider="${AGENT_PROVIDER:-google-genai}"
case "$provider" in
  google-genai) name=GEMINI_API_KEY; key="$GEMINI_API_KEY" ;;
  anthropic)
    name=ANTHROPIC_API_KEY; key="$ANTHROPIC_API_KEY"
    if [ -z "$AGENT_MODEL" ]; then
      echo "AGENT_MODEL is missing in .env: with AGENT_PROVIDER=anthropic there is no default model." >&2
      exit 5
    fi ;;
  *)
    echo "AGENT_PROVIDER in .env must be google-genai or anthropic, not '$provider'." >&2
    exit 5 ;;
esac
if [ -z "$key" ]; then
  echo "$name is missing in .env: the agent cannot ask the model ($provider) without it." >&2
  exit 5
fi
# 512 MB of heap inside the 1 GB limit of the service: it only runs on demand.
exec java -Xmx512m -jar /app/agent.jar "$@"
