#!/bin/bash
# Append commands from stdin to the live driver's inbox and wait until they have all run.
# usage: qa.sh <runDir> [timeoutSeconds] < commands
# Prints the new outbox lines (minus the echoed commands) and the elapsed time.
# Stops early on the driver's first ERROR, since the driver drops the rest of the queue.
#        qa.sh <runDir> stop
# asks the client launched from <runDir> to quit, waits for it to exit, and kills it after 60
# seconds. It only touches the java process whose working directory is <runDir>.
RUN=$(cd "$1" && pwd -P)

client_pids() {
  for pid in $(pgrep -f 'net.fabricmc.devlaunchinjector.Main|KnotClient'); do
    cwd=$(lsof -a -d cwd -p "$pid" -Fn 2>/dev/null | sed -n 's/^n//p')
    [ "$cwd" = "$RUN" ] && echo "$pid"
  done
}

if [ "$2" = stop ]; then
  echo quit >> "$RUN/qa-in.txt"
  for _ in $(seq 60); do
    [ -z "$(client_pids)" ] && { echo "client stopped"; exit 0; }
    sleep 1
  done
  pids=$(client_pids); [ -n "$pids" ] && kill $pids; sleep 3
  pids=$(client_pids); [ -n "$pids" ] && kill -9 $pids; sleep 1
  [ -z "$(client_pids)" ] && { echo "client killed"; exit 0; }
  echo "client still running: $(client_pids)"; exit 1
fi

T=${2:-180}
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
