#!/usr/bin/env bash
# Дымовой тест приложения в эмуляторе (запускается из .github/workflows/emulator.yml внутри android-emulator-runner).
# Что проверяем: запуск без падений, скачивание поста Твиттера (фото и гифки), «Скачать как GIF» для обычного видео,
# что готовый .gif не перекодируется, и случайные нажатия monkey. Итог в $OUT/summary.md, код возврата 1 при любой неудаче.
set -u
PKG=com.vad.downloader
OUT=${OUT:-ci-out}
mkdir -p "$OUT/shots" "$OUT/files"
SUM="$OUT/summary.md"
: > "$SUM"
FAIL=0
say()  { echo "$*" | tee -a "$SUM"; }
ok()   { say "- PASS: $*"; }
bad()  { say "- FAIL: $*"; FAIL=1; }
warn() { say "- SKIP: $*"; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png" 2>/dev/null || true; }
vadlog() { adb shell "run-as $PKG cat files/vad.log" 2>/dev/null | tr -d '\r' || true; }
listdl() { adb shell "ls -1 /sdcard/Download/VAD 2>/dev/null" | tr -d '\r' | sort; }

adb logcat -c || true
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGPID=$!

say "# Эмуляторный тест"
say ""
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
adb install -r -g "$APK" >/dev/null 2>&1 && ok "APK установлен ($(basename "$APK"), $(du -h "$APK" | cut -f1))" || { bad "APK не установился"; exit 1; }

crashes() { grep -E "FATAL EXCEPTION|ANR in|Fatal signal|UnsatisfiedLinkError|has died" "$OUT/logcat.txt" | grep -v "adbd" | head -${1:-10}; }

seed_prefs() { # $1 = gif_video true|false
  adb shell am force-stop $PKG
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/vad.xml'" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <boolean name="classic_ui" value="true" />
  <boolean name="gif_video" value="$1" />
  <boolean name="clip_watch" value="false" />
</map>
EOF
}

# --- 1. холодный запуск
seed_prefs false
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 25
shot 01_start
if adb shell pidof $PKG | grep -q .; then ok "приложение запустилось и живёт через 25 с"; else bad "приложение не запущено (упало при старте?)"; fi
C=$(crashes); [ -n "$C" ] && bad "падения при запуске: $(echo "$C" | head -3 | tr '\n' ' | ')"

# --- функция: вставить ссылку, дождаться файлов, подтянуть результат
run_dl() { # name url regex_of_expected_new_file timeout_s
  local name=$1 url=$2 re=$3 to=${4:-180}
  listdl > "$OUT/before_$name.txt"
  adb shell am start -n $PKG/.MainActivity -a com.vad.downloader.PASTE_DL --es url "$url" --ez bg false >/dev/null
  local t=0
  while [ $t -lt "$to" ]; do
    sleep 5; t=$((t + 5))
    listdl > "$OUT/after_$name.txt"
    if comm -13 "$OUT/before_$name.txt" "$OUT/after_$name.txt" | grep -Eiq "$re"; then break; fi
  done
  sleep 10
  listdl > "$OUT/after_$name.txt"
  comm -13 "$OUT/before_$name.txt" "$OUT/after_$name.txt" > "$OUT/new_$name.txt"
  mkdir -p "$OUT/files/$name"
  adb pull /sdcard/Download/VAD/. "$OUT/files/$name/" >/dev/null 2>&1 || true
  vadlog > "$OUT/vad_$name.log"
  shot "$name"
  say "  новые файлы ($name): $(tr '\n' ';' < "$OUT/new_$name.txt")"
}

# --- 2. пост Твиттера
if [ -n "${TWEET_URL:-}" ]; then
  say ""; say "## Пост Твиттера: $TWEET_URL"
  seed_prefs false; adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 8
  run_dl tweet "$TWEET_URL" '\.(jpg|jpeg|png|webp|gif|mp4)$' 150
  PH=$(grep -Eic '\.(jpg|jpeg|png|webp)$' "$OUT/new_tweet.txt")
  GF=$(grep -Eic '\.gif$' "$OUT/new_tweet.txt")
  if [ -f "$OUT/fxtwitter.json" ] && command -v jq >/dev/null; then
    EP=$(jq '[.tweet.media.all[]? | select(.type=="photo")] | length' "$OUT/fxtwitter.json" 2>/dev/null || echo 0)
    EG=$(jq '[.tweet.media.all[]? | select(.type=="gif")] | length' "$OUT/fxtwitter.json" 2>/dev/null || echo 0)
    EV=$(jq '[.tweet.media.all[]? | select(.type=="video")] | length' "$OUT/fxtwitter.json" 2>/dev/null || echo 0)
    say "  API: фото $EP, гифок $EG, видео $EV; скачано: фото $PH, gif $GF"
    [ "$PH" -ge "$EP" ] && ok "все фото поста скачаны ($PH из $EP)" || bad "фото скачано $PH из $EP"
    [ "$EG" -eq 0 ] || { [ "$GF" -ge "$EG" ] && ok "гифки сохранены как .gif ($GF из $EG)" || bad "гифок .gif $GF из $EG"; }
    if grep -Eiq 'gif[0-9]+\.mp4$' "$OUT/new_tweet.txt"; then bad "после конвертации остался mp4 гифки"; fi
  else
    warn "ответ API fxtwitter не получен, сверить число файлов нельзя (фото $PH, gif $GF)"
  fi
  for f in "$OUT"/files/tweet/*; do
    [ -f "$f" ] || continue
    case "$f" in *.jpg|*.jpeg|*.png|*.webp|*.gif) file -b "$f" | grep -Eiq 'image|GIF' || bad "битый файл: $(basename "$f") ($(file -b "$f"))";; esac
  done
  grep -q "В посте\|Post media" "$OUT/vad_tweet.log" && ok "приложение определило медиа поста" || bad "в логе нет строки про медиа поста"
fi

# --- 3. обычное видео с включённым «Скачать как GIF»
if [ -n "${VIDEO_URL:-}" ]; then
  say ""; say "## Видео как GIF: $VIDEO_URL"
  seed_prefs true; adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 8
  run_dl video "$VIDEO_URL" '\.(gif|mp4|webm|mkv)$' 240
  if grep -Eiq '\.gif$' "$OUT/new_video.txt"; then
    G=$(grep -Ei '\.gif$' "$OUT/new_video.txt" | head -1)
    file -b "$OUT/files/video/$G" | grep -q GIF && ok "видео превращено в настоящий GIF ($G, $(du -h "$OUT/files/video/$G" | cut -f1))" || bad "файл .gif не GIF: $(file -b "$OUT/files/video/$G")"
  else
    bad "GIF не появился"; grep -i "GIF\|ffmpeg\|error" "$OUT/vad_video.log" | tail -5 | sed 's/^/    /' | tee -a "$SUM"
  fi
  grep -Eiq '\.(mp4|webm|mkv)$' "$OUT/new_video.txt" && warn "рядом остался исходник видео (конвертация не удалась или не стёрла его)"
fi

# --- 4. готовый .gif не должен перекодироваться
if [ -n "${GIF_URL:-}" ]; then
  say ""; say "## Готовый GIF с тумблером «как GIF»: $GIF_URL"
  if curl -fsSL -m 60 "$GIF_URL" -o "$OUT/orig.gif" 2>/dev/null; then
    seed_prefs true; adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 8
    run_dl gif "$GIF_URL" '\.gif$' 150
    G=$(grep -Ei '\.gif$' "$OUT/new_gif.txt" | head -1)
    if [ -z "$G" ]; then bad "gif не скачался"
    elif cmp -s "$OUT/orig.gif" "$OUT/files/gif/$G"; then ok "скачанный .gif байт в байт совпадает с оригиналом (не перекодирован)"
    else bad "скачанный .gif отличается от оригинала ($(stat -c %s "$OUT/orig.gif") → $(stat -c %s "$OUT/files/gif/$G") байт): перекодирован?"; fi
  else warn "GIF_URL недоступен из раннера"; fi
fi

# --- 5. monkey: случайные нажатия по интерфейсу
say ""; say "## Monkey"
seed_prefs false
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 8
MK=$(adb shell monkey -p $PKG --throttle 250 --pct-syskeys 0 --pct-appswitch 0 --monitor-native-crashes -s 7 -v 700 2>&1 | tr -d '\r')
echo "$MK" > "$OUT/monkey.txt"
echo "$MK" | grep -q "Monkey finished" && ok "monkey отработал 700 событий" || bad "monkey прервался: $(echo "$MK" | grep -E 'CRASH|ANR|Exception' | head -2 | tr '\n' ' ')"
echo "$MK" | grep -Eq "CRASH|ANR" && bad "monkey поймал падение/ANR"
shot 99_after_monkey

# --- итог
C=$(crashes 20)
if [ -n "$C" ]; then bad "в logcat есть падения:"; echo "$C" | sed 's/^/    /' | tee -a "$SUM"; else ok "в logcat нет FATAL/ANR/UnsatisfiedLinkError"; fi
vadlog > "$OUT/vad.log"
kill $LOGPID 2>/dev/null || true
say ""; [ $FAIL -eq 0 ] && say "**Итог: всё прошло**" || say "**Итог: есть неудачи**"
exit $FAIL
