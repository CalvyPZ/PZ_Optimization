#!/bin/bash
# Stock dedicated server on this PC for multiplayer client runs (2026-09-21).
#   harness/mp/server.sh setup   # hardlinked copy of the game dir without any pzopt file, first start, ini tuned
#   harness/mp/server.sh start   # start (transient user unit pzsrv.service; stdin = tail -f of /tmp/pzsrv-cmds.txt)
#   harness/mp/server.sh up      # ensure it runs, kick the stale "admin" session (a killed client stays connected)
#   harness/mp/server.sh cmd X   # server console command, e.g. "players", "quit"
# The server keeps its own world under /tmp/pzsrv-home (the single-player bench save's map_meta.bin does not
# load on a server: invalid room metaIDs, then a BufferUnderflow). The ini is rewritten by the server at start
# and shutdown with validated values: edit it only while the server is stopped, and SpeedLimit tops out at 150.
set -u
SRC=/games/steamapps/common/ProjectZomboid/projectzomboid
DST=/games/steamapps/common/ProjectZomboid/pzsrv-stock
H=/tmp/pzsrv-home
start() {
  : > /tmp/pzsrv-cmds.txt
  systemctl --user stop pzsrv.service 2>/dev/null; systemctl --user reset-failed pzsrv.service 2>/dev/null
  systemd-run --user --unit=pzsrv --collect -p WorkingDirectory="$DST" -E LD_LIBRARY_PATH="$DST/natives:$DST/jre64/lib:$DST" -E LD_PRELOAD=libjsig.so \
    bash -c "tail -f -n0 /tmp/pzsrv-cmds.txt | '$DST/jre64/bin/java' -cp './:./projectzomboid.jar' -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Xms1024m -Xmx3072m -XX:+UseZGC -Dzomboid.steam=0 -Dzomboid.znetlog=1 -Djava.library.path=./:./natives/ -XX:-OmitStackTraceInFastThrow -Djava.security.egd=file:/dev/urandom zombie/network/GameServer -cachedir='$H' -servername pzopttest -adminusername admin -adminpassword pzoptadmin -nosteam > '$H/server-stdout.txt' 2>&1" >/dev/null
  for i in $(seq 1 90); do sleep 2; grep -q "SERVER STARTED" "$H/server-stdout.txt" 2>/dev/null && { echo "server started"; return 0; }; grep -q "Server Terminated" "$H/server-stdout.txt" 2>/dev/null && { echo "server terminated"; return 1; }; done
  echo "no SERVER STARTED after 180 s"; return 1
}
stop() { echo quit >> /tmp/pzsrv-cmds.txt; for i in $(seq 1 30); do sleep 2; systemctl --user is-active pzsrv.service | grep -q inactive && return 0; done; systemctl --user stop pzsrv.service; }
case "${1:-}" in
  setup)
    systemctl --user stop pzsrv.service 2>/dev/null; rm -rf "$DST" "$H"; cp -al "$SRC" "$DST"; cd "$DST"
    awk '!/^#/{print $1}' pzopt-installed.txt | while IFS= read -r f; do rm -f "./$f"; done   # manifest lines are "path sha256"
    rm -f pzopt-installed.txt pzopt.properties; rm -rf pzopt media/lua/shared/pzopt media/lua/client/pzopt; rm -f media/shaders/pzopt_*
    find zombie org se pzopt -type d -empty -delete 2>/dev/null
    echo "stock copy: $(find . -name '*.class' | wc -l) loose class files, $(find media/lua -path '*pzopt*' | wc -l) pzopt lua"
    mkdir -p "$H"; start || exit 1; stop
    INI="$H/Server/pzopttest.ini"
    sed -i -e 's/^UPnP=.*/UPnP=false/' -e 's/^SpawnPoint=.*/SpawnPoint=8002,11204,0/' -e 's/^Mods=.*/Mods=/' -e 's/^WorkshopItems=.*/WorkshopItems=/' \
      -e 's/^\(AntiCheat[A-Za-z]*\)=[0-9]*/\1=0/' -e 's/^AntiCheatChecksum=.*/AntiCheatChecksum=2/' -e 's/^PauseEmpty=.*/PauseEmpty=false/' -e 's/^SpeedLimit=.*/SpeedLimit=150/' "$INI"
    start && grep "^SpawnPoint\|^SpeedLimit\|^DoLuaChecksum" "$INI" ;;
  start) start ;;
  stop) stop ;;
  up)
    for try in 1 2 3; do
      if systemctl --user is-active pzsrv.service | grep -q '^active' && grep -q "SERVER STARTED" "$H/server-stdout.txt" && ! grep -q "Shutdown handling started" "$H/server-stdout.txt"; then
        echo "kickuser admin" >> /tmp/pzsrv-cmds.txt; sleep 2; echo players >> /tmp/pzsrv-cmds.txt; sleep 2
        tail -2 "$H/server-stdout.txt" | grep "Players connected"; echo "server up"; exit 0
      fi
      systemctl --user stop pzsrv.service 2>/dev/null; sleep 2; echo "start attempt $try"; start
    done; echo "server could not be started"; exit 1 ;;
  cmd) echo "$2" >> /tmp/pzsrv-cmds.txt; sleep 2; tail -3 "$H/server-stdout.txt" ;;
  *) sed -n 2,7p "$0" ;;
esac
