#!/usr/bin/env bash
# Drives iTube with remote-control key events on an emulator (real YouTube, no mock) and captures screenshots.
set -u
OUT=${OUT:-shots}
mkdir -p "$OUT"
n=0
k() { for c in "$@"; do adb shell input keyevent "$c"; sleep 0.6; done; }
shot() { n=$((n+1)); f=$(printf "%s/%02d_%s.png" "$OUT" "$n" "$1"); adb exec-out screencap -p > "$f"; echo "shot $f"; }
open() { adb shell am start -a android.intent.action.VIEW -d "$1" com.itube.tv > /dev/null; }
UP=19; DOWN=20; LEFT=21; RIGHT=22; OK=23; BACK=4; ENTER=66

adb shell wm size
adb shell wm density 320
adb shell settings put secure show_ime_with_hard_keyboard 0
adb logcat -c
adb install -r app.apk
adb shell am start -n com.itube.tv/.MainActivity
sleep 15; shot home_first_launch
# Explore
k $RIGHT; sleep 2; k $RIGHT; sleep 10; shot explore
k $DOWN; sleep 1; k $DOWN; sleep 2; shot explore_grid_focus
k $OK; sleep 25; shot player
sleep 15; shot player_playing
k $DOWN; sleep 3; shot player_info_panel
k $OK; sleep 2; shot player_quality
k $BACK; sleep 1; k $RIGHT; sleep 1; k $OK; sleep 2; shot player_subtitles
k $BACK; sleep 1; k $BACK; sleep 1
k $RIGHT $RIGHT $RIGHT; sleep 1; shot player_seek
sleep 3; k $BACK; sleep 1; k $BACK; sleep 4; shot explore_after_player
# A channel, opened like a shared link; subscribe
open "https://www.youtube.com/@LofiGirl"; sleep 15; shot channel
k $OK; sleep 3; shot channel_subscribed
k $DOWN $DOWN; sleep 2; shot channel_videos
k $BACK; sleep 3
# Subscriptions tab (feed refreshes in the background)
k $UP $UP; sleep 1; k $LEFT; sleep 10; shot subscriptions
# Search through the system keyboard dialog
k $RIGHT; sleep 2; k $RIGHT; sleep 2; k $RIGHT; sleep 4; k $DOWN; sleep 1
k $DOWN $DOWN $DOWN $DOWN $DOWN $DOWN $DOWN; k $OK; sleep 4
adb shell input text "apple%skeynote"; sleep 2; k $ENTER; sleep 15; shot search_results
k $BACK; sleep 1
# Settings
k $RIGHT; sleep 3; shot settings
# Home with history and a subscription
for i in 1 2 3 4 5; do k $LEFT; sleep 1.5; done; sleep 10; shot home_with_data
k $DOWN; sleep 2; shot home_hero_focus
k $DOWN; sleep 2; shot home_rows
# A video opened as a link (resume position, related videos)
open "https://www.youtube.com/watch?v=aqz-KE-bpKQ"; sleep 30; shot player_link
k $DOWN; sleep 3; k $DOWN; sleep 2; shot player_related

echo "==== crashes / errors ===="
adb logcat -d | grep -E "AndroidRuntime|FATAL|iTube|ExoPlayer|com.itube" | grep -vE "ResourcesCompat|chatty" | head -200
