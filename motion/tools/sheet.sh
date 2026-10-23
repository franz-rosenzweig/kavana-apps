#!/bin/bash
# sheet.sh <dir-of-rgba> <out.png> <names...>  -> 5-wide sheet at 300px
set -e
dir=$1; out=$2; shift 2
args=(); fl=""; i=0; row=""; rows=""; r=0
for c in "$@"; do args+=(-f rawvideo -pix_fmt rgba -s 512x512 -i "$dir/$c.rgba"); fl+="[${i}]scale=300:300,format=rgba[s${i}];"; row+="[s${i}]"; i=$((i+1))
  if (( i % 5 == 0 )); then fl+="${row}hstack=5[r${r}];"; rows+="[r${r}]"; r=$((r+1)); row=""; fi; done
ffmpeg -v error -y "${args[@]}" -filter_complex "${fl}${rows}vstack=${r},format=rgb24" "$out"
