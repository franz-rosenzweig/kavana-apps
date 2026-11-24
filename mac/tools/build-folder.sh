#!/bin/zsh
# Makes mac/build a link to mac/build.noindex, where everything built really lives. Spotlight never looks inside a
# folder whose name ends in .noindex, so the test and release copies of the app don't turn up as more Chataltuls when
# someone searches for it, and every path that says build/ still works. build.sh, selftest.sh and release.sh run it
# first. A build folder from before this becomes build.noindex; if there is one of each, the old one goes (it is only
# what was built).
set -euo pipefail
cd "${0:A:h:h}"
if [[ -d build && ! -L build ]]; then
  if [[ -e build.noindex ]]; then rm -rf build; else mv build build.noindex; fi
fi
mkdir -p build.noindex
[[ -L build ]] || ln -s build.noindex build
