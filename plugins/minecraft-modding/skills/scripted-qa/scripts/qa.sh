#!/bin/bash
# Append commands from stdin to the live driver's inbox and wait until they have all run.
# usage: qa.sh <runDir> [timeoutSeconds] < commands
# Prints the new outbox lines (minus the echoed commands) and the elapsed time.
# Stops early on the driver's first ERROR, since the driver drops the rest of the queue.
RUN=$1; T=${2:-180}
id="done-$RANDOM$RANDOM"
start=$(wc -l < "$RUN/qa-out.txt")
{ cat; echo "mark $id"; } >> "$RUN/qa-in.txt"
t0=$SECONDS
status=0
while true; do
  new=$(tail -n +$((start+1)) "$RUN/qa-out.txt")
  grep -q "MARK $id" <<< "$new" && break
  grep -q "^ERROR" <<< "$new" && { status=1; break; }
  [ $((SECONDS-t0)) -ge "$T" ] && { echo "TIMEOUT after ${T}s"; status=2; break; }
  sleep 0.5
done
tail -n +$((start+1)) "$RUN/qa-out.txt" | grep -v "^> \|^MARK done-"
echo "[took $((SECONDS-t0))s]"
exit $status
